/**
 * Хранилище на SQLite (встроенный модуль node:sqlite, Node >= 22.5).
 *
 * Почему SQLite: одна таблица «номер -> время последнего звонка», нагрузка
 * измеряется звонками в минуту, файл БД легко бэкапить копированием.
 * Драйвер синхронный, но операции занимают микросекунды и не блокируют
 * обработку вебхука заметным образом.
 *
 * Чтобы перейти на Redis/Postgres, достаточно реализовать те же методы
 * (см. docs/05-api-and-extensions.md).
 */

import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import { decideCallAction } from '../domain/decision.js';
import { SCHEMA_SQL } from './schema.sql.js';

export class SqliteStore {
  #db;

  /**
   * @param {object} options
   * @param {string} options.path путь к файлу базы
   */
  constructor({ path }) {
    const absolutePath = resolve(path);
    mkdirSync(dirname(absolutePath), { recursive: true });

    this.path = absolutePath;
    this.#db = new DatabaseSync(absolutePath);
    this.#db.exec(SCHEMA_SQL);
  }

  /**
   * Атомарно определяет, что делать со звонком, и фиксирует результат.
   *
   * Чтение прошлой метки времени и её обновление выполняются в одной
   * транзакции: два одновременных звонка с одного номера не смогут
   * оба получить решение «соединить».
   *
   * @param {object} args
   * @param {string} args.pbxCallId идентификатор звонка (для защиты от повторов вебхука)
   * @param {string} args.phone нормализованный номер
   * @param {string} [args.rawCallerId] номер в исходном виде
   * @param {string} [args.calledDid] номер, на который звонили
   * @param {string} [args.callStart] время начала по данным АТС
   * @param {number} args.now текущее время, мс
   * @param {number} args.cooldownMs окно тишины, мс
   * @returns {import('../domain/decision.js').CallDecision & { replayed: boolean, previousCallAt: number | null }}
   */
  resolveCall({ pbxCallId, phone, rawCallerId, calledDid, callStart, now, cooldownMs }) {
    this.#db.exec('BEGIN IMMEDIATE');
    try {
      if (pbxCallId) {
        const existing = this.#db
          .prepare('SELECT decision, reason, elapsed_ms, previous_call_at FROM calls WHERE pbx_call_id = ?')
          .get(pbxCallId);

        if (existing) {
          this.#db.exec('COMMIT');
          return {
            decision: existing.decision,
            reason: existing.reason ?? 'Повторная доставка того же вебхука.',
            elapsedMs: existing.elapsed_ms ?? null,
            elapsedMinutes: existing.elapsed_ms === null ? null : Math.round((existing.elapsed_ms / 60_000) * 10) / 10,
            isFirstCall: false,
            updateLastCallAt: false,
            replayed: true,
            previousCallAt: existing.previous_call_at ?? null,
          };
        }
      }

      const caller = this.#db.prepare('SELECT * FROM callers WHERE phone = ?').get(phone);
      const previousCallAt = caller ? caller.last_call_at : null;

      const outcome = decideCallAction({ lastCallAt: previousCallAt, now, cooldownMs });

      this.#upsertCaller({ caller, phone, now, outcome, pbxCallId });
      this.#insertCall({ pbxCallId, phone, rawCallerId, calledDid, callStart, now, outcome, previousCallAt });

      this.#db.exec('COMMIT');
      return { ...outcome, replayed: false, previousCallAt };
    } catch (error) {
      this.#db.exec('ROLLBACK');
      throw error;
    }
  }

  #upsertCaller({ caller, phone, now, outcome, pbxCallId }) {
    const counters = {
      autoreply: outcome.decision === 'autoreply' ? 1 : 0,
      connect: outcome.decision === 'connect' ? 1 : 0,
      ignore: outcome.decision === 'ignore' ? 1 : 0,
    };

    if (!caller) {
      this.#db
        .prepare(
          `INSERT INTO callers
             (phone, last_call_at, first_seen_at, total_calls,
              autoreply_count, connect_count, ignore_count,
              last_decision, last_pbx_call_id, updated_at)
           VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?)`,
        )
        .run(phone, now, now, counters.autoreply, counters.connect, counters.ignore, outcome.decision, pbxCallId ?? null, now);
      return;
    }

    // Метка времени сдвигается только для «автоответа» и «соединения»;
    // звонок внутри окна тишины окно не продлевает.
    const lastCallAt = outcome.updateLastCallAt ? now : caller.last_call_at;

    this.#db
      .prepare(
        `UPDATE callers
            SET last_call_at     = ?,
                total_calls      = total_calls + 1,
                autoreply_count  = autoreply_count + ?,
                connect_count    = connect_count + ?,
                ignore_count     = ignore_count + ?,
                last_decision    = ?,
                last_pbx_call_id = ?,
                updated_at       = ?
          WHERE phone = ?`,
      )
      .run(lastCallAt, counters.autoreply, counters.connect, counters.ignore, outcome.decision, pbxCallId ?? null, now, phone);
  }

  #insertCall({ pbxCallId, phone, rawCallerId, calledDid, callStart, now, outcome, previousCallAt }) {
    const id = pbxCallId || `local_${now}_${Math.random().toString(36).slice(2, 10)}`;

    this.#db
      .prepare(
        `INSERT INTO calls
           (pbx_call_id, phone, raw_caller_id, called_did, call_start,
            decision, reason, elapsed_ms, previous_call_at, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        id,
        phone ?? null,
        rawCallerId ?? null,
        calledDid ?? null,
        callStart ?? null,
        outcome.decision,
        outcome.reason ?? null,
        outcome.elapsedMs ?? null,
        previousCallAt ?? null,
        now,
      );
  }

  /** Дописывает в журнал, что именно было отправлено в ответ провайдеру. */
  recordResponse({ pbxCallId, response, smsStatus }) {
    if (!pbxCallId) return;
    this.#db
      .prepare('UPDATE calls SET response_json = ?, sms_status = COALESCE(?, sms_status) WHERE pbx_call_id = ?')
      .run(response === undefined ? null : JSON.stringify(response), smsStatus ?? null, pbxCallId);
  }

  /** Данные из NOTIFY_END: длительность и итог звонка. */
  recordCallEnd({ pbxCallId, duration, disposition, isRecorded, endedAt }) {
    if (!pbxCallId) return;
    const updated = this.#db
      .prepare(
        `UPDATE calls
            SET duration = ?, disposition = ?, is_recorded = ?, ended_at = ?
          WHERE pbx_call_id = ?`,
      )
      .run(
        duration === undefined ? null : Number(duration),
        disposition ?? null,
        isRecorded === undefined || isRecorded === null ? null : Number(isRecorded) ? 1 : 0,
        endedAt ?? Date.now(),
        pbxCallId,
      );

    return updated.changes > 0;
  }

  getCaller(phone) {
    return this.#db.prepare('SELECT * FROM callers WHERE phone = ?').get(phone) ?? null;
  }

  listCallers({ limit = 50, offset = 0 } = {}) {
    return this.#db
      .prepare('SELECT * FROM callers ORDER BY last_call_at DESC LIMIT ? OFFSET ?')
      .all(limit, offset);
  }

  listCalls({ limit = 50, offset = 0, phone } = {}) {
    if (phone) {
      return this.#db
        .prepare('SELECT * FROM calls WHERE phone = ? ORDER BY created_at DESC LIMIT ? OFFSET ?')
        .all(phone, limit, offset);
    }
    return this.#db.prepare('SELECT * FROM calls ORDER BY created_at DESC LIMIT ? OFFSET ?').all(limit, offset);
  }

  /** Сбрасывает историю номера — звонок снова будет считаться первым. */
  deleteCaller(phone) {
    const result = this.#db.prepare('DELETE FROM callers WHERE phone = ?').run(phone);
    return result.changes > 0;
  }

  getStats() {
    const callers = this.#db.prepare('SELECT COUNT(*) AS total FROM callers').get();
    const byDecision = this.#db.prepare('SELECT decision, COUNT(*) AS count FROM calls GROUP BY decision').all();
    const last24h = this.#db
      .prepare('SELECT COUNT(*) AS count FROM calls WHERE created_at >= ?')
      .get(Date.now() - 24 * 60 * 60 * 1000);

    return {
      callers: callers.total,
      callsByDecision: Object.fromEntries(byDecision.map((row) => [row.decision, row.count])),
      callsLast24h: last24h.count,
    };
  }

  getSetting(key) {
    const row = this.#db.prepare('SELECT value FROM settings WHERE key = ?').get(key);
    return row ? row.value : null;
  }

  setSetting(key, value) {
    this.#db
      .prepare(
        `INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
      )
      .run(key, String(value), Date.now());
  }

  deleteSetting(key) {
    this.#db.prepare('DELETE FROM settings WHERE key = ?').run(key);
  }

  /** Чистка журнала по сроку хранения. */
  purgeOldCalls(retentionDays) {
    if (!retentionDays || retentionDays <= 0) return 0;
    const cutoff = Date.now() - retentionDays * 24 * 60 * 60 * 1000;
    return this.#db.prepare('DELETE FROM calls WHERE created_at < ?').run(cutoff).changes;
  }

  close() {
    this.#db.close();
  }
}
