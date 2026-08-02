/**
 * Хранилище в памяти — для тестов и локальной отладки без файла БД.
 * Реализует тот же интерфейс, что и SqliteStore; данные теряются при рестарте.
 */

import { decideCallAction } from '../domain/decision.js';

export class MemoryStore {
  #callers = new Map();
  #calls = new Map();
  #settings = new Map();

  resolveCall({ pbxCallId, phone, rawCallerId, calledDid, callStart, now, cooldownMs }) {
    if (pbxCallId && this.#calls.has(pbxCallId)) {
      const existing = this.#calls.get(pbxCallId);
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

    const caller = this.#callers.get(phone) ?? null;
    const previousCallAt = caller ? caller.last_call_at : null;
    const outcome = decideCallAction({ lastCallAt: previousCallAt, now, cooldownMs });

    const counters = {
      autoreply: outcome.decision === 'autoreply' ? 1 : 0,
      connect: outcome.decision === 'connect' ? 1 : 0,
      ignore: outcome.decision === 'ignore' ? 1 : 0,
    };

    if (!caller) {
      this.#callers.set(phone, {
        phone,
        last_call_at: now,
        first_seen_at: now,
        total_calls: 1,
        autoreply_count: counters.autoreply,
        connect_count: counters.connect,
        ignore_count: counters.ignore,
        last_decision: outcome.decision,
        last_pbx_call_id: pbxCallId ?? null,
        updated_at: now,
      });
    } else {
      caller.last_call_at = outcome.updateLastCallAt ? now : caller.last_call_at;
      caller.total_calls += 1;
      caller.autoreply_count += counters.autoreply;
      caller.connect_count += counters.connect;
      caller.ignore_count += counters.ignore;
      caller.last_decision = outcome.decision;
      caller.last_pbx_call_id = pbxCallId ?? null;
      caller.updated_at = now;
    }

    const id = pbxCallId || `local_${now}_${Math.random().toString(36).slice(2, 10)}`;
    this.#calls.set(id, {
      pbx_call_id: id,
      phone: phone ?? null,
      raw_caller_id: rawCallerId ?? null,
      called_did: calledDid ?? null,
      call_start: callStart ?? null,
      decision: outcome.decision,
      reason: outcome.reason ?? null,
      elapsed_ms: outcome.elapsedMs ?? null,
      previous_call_at: previousCallAt ?? null,
      response_json: null,
      sms_status: null,
      duration: null,
      disposition: null,
      is_recorded: null,
      created_at: now,
      ended_at: null,
    });

    return { ...outcome, replayed: false, previousCallAt };
  }

  recordResponse({ pbxCallId, response, smsStatus }) {
    const call = this.#calls.get(pbxCallId);
    if (!call) return;
    call.response_json = response === undefined ? null : JSON.stringify(response);
    if (smsStatus) call.sms_status = smsStatus;
  }

  recordCallEnd({ pbxCallId, duration, disposition, isRecorded, endedAt }) {
    const call = this.#calls.get(pbxCallId);
    if (!call) return false;
    call.duration = duration === undefined ? null : Number(duration);
    call.disposition = disposition ?? null;
    call.is_recorded = isRecorded === undefined || isRecorded === null ? null : Number(isRecorded) ? 1 : 0;
    call.ended_at = endedAt ?? Date.now();
    return true;
  }

  getCaller(phone) {
    return this.#callers.get(phone) ?? null;
  }

  listCallers({ limit = 50, offset = 0 } = {}) {
    return [...this.#callers.values()]
      .sort((a, b) => b.last_call_at - a.last_call_at)
      .slice(offset, offset + limit);
  }

  listCalls({ limit = 50, offset = 0, phone } = {}) {
    return [...this.#calls.values()]
      .filter((call) => (phone ? call.phone === phone : true))
      .sort((a, b) => b.created_at - a.created_at)
      .slice(offset, offset + limit);
  }

  deleteCaller(phone) {
    return this.#callers.delete(phone);
  }

  getStats() {
    const callsByDecision = {};
    for (const call of this.#calls.values()) {
      callsByDecision[call.decision] = (callsByDecision[call.decision] ?? 0) + 1;
    }
    const dayAgo = Date.now() - 24 * 60 * 60 * 1000;

    return {
      callers: this.#callers.size,
      callsByDecision,
      callsLast24h: [...this.#calls.values()].filter((call) => call.created_at >= dayAgo).length,
    };
  }

  getSetting(key) {
    return this.#settings.get(key) ?? null;
  }

  setSetting(key, value) {
    this.#settings.set(key, String(value));
  }

  deleteSetting(key) {
    this.#settings.delete(key);
  }

  purgeOldCalls(retentionDays) {
    if (!retentionDays || retentionDays <= 0) return 0;
    const cutoff = Date.now() - retentionDays * 24 * 60 * 60 * 1000;
    let removed = 0;
    for (const [id, call] of this.#calls) {
      if (call.created_at < cutoff) {
        this.#calls.delete(id);
        removed += 1;
      }
    }
    return removed;
  }

  close() {
    this.#callers.clear();
    this.#calls.clear();
    this.#settings.clear();
  }
}
