/**
 * Оба драйвера хранилища должны вести себя одинаково — тесты параметризованы.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { MemoryStore } from '../src/store/memoryStore.js';
import { SqliteStore } from '../src/store/sqliteStore.js';
import { minutesToMs } from '../src/domain/decision.js';

const COOLDOWN_MS = minutesToMs(40);
const PHONE = '998901234567';

const drivers = [
  { name: 'MemoryStore', create: () => ({ store: new MemoryStore(), cleanup: () => {} }) },
  {
    name: 'SqliteStore',
    create: () => {
      const dir = mkdtempSync(join(tmpdir(), 'call-router-'));
      const store = new SqliteStore({ path: join(dir, 'test.db') });
      return {
        store,
        cleanup: () => {
          store.close();
          rmSync(dir, { recursive: true, force: true });
        },
      };
    },
  },
];

for (const driver of drivers) {
  test(`${driver.name}: полный сценарий первый звонок -> повтор -> новое обращение`, () => {
    const { store, cleanup } = driver.create();
    try {
      const t0 = Date.UTC(2026, 0, 15, 10, 0, 0);

      const first = store.resolveCall({ pbxCallId: 'call-1', phone: PHONE, now: t0, cooldownMs: COOLDOWN_MS });
      assert.equal(first.decision, 'autoreply');
      assert.equal(first.replayed, false);
      assert.equal(store.getCaller(PHONE).last_call_at, t0);

      const tooSoon = store.resolveCall({
        pbxCallId: 'call-2',
        phone: PHONE,
        now: t0 + minutesToMs(10),
        cooldownMs: COOLDOWN_MS,
      });
      assert.equal(tooSoon.decision, 'ignore');
      assert.equal(store.getCaller(PHONE).last_call_at, t0, 'окно тишины не должно сдвигаться');

      const newRequest = store.resolveCall({
        pbxCallId: 'call-3',
        phone: PHONE,
        now: t0 + minutesToMs(41),
        cooldownMs: COOLDOWN_MS,
      });
      assert.equal(newRequest.decision, 'connect');
      assert.equal(store.getCaller(PHONE).last_call_at, t0 + minutesToMs(41));

      const caller = store.getCaller(PHONE);
      assert.equal(caller.total_calls, 3);
      assert.equal(caller.autoreply_count, 1);
      assert.equal(caller.ignore_count, 1);
      assert.equal(caller.connect_count, 1);
    } finally {
      cleanup();
    }
  });

  test(`${driver.name}: повторная доставка того же вебхука не меняет состояние`, () => {
    const { store, cleanup } = driver.create();
    try {
      const now = Date.now();
      const first = store.resolveCall({ pbxCallId: 'dup-1', phone: PHONE, now, cooldownMs: COOLDOWN_MS });
      const replay = store.resolveCall({
        pbxCallId: 'dup-1',
        phone: PHONE,
        now: now + 1000,
        cooldownMs: COOLDOWN_MS,
      });

      assert.equal(first.replayed, false);
      assert.equal(replay.replayed, true);
      assert.equal(replay.decision, first.decision);
      assert.equal(store.getCaller(PHONE).total_calls, 1, 'счётчик не должен увеличиваться повторно');
    } finally {
      cleanup();
    }
  });

  test(`${driver.name}: разные номера не влияют друг на друга`, () => {
    const { store, cleanup } = driver.create();
    try {
      const now = Date.now();
      store.resolveCall({ pbxCallId: 'a-1', phone: PHONE, now, cooldownMs: COOLDOWN_MS });
      const other = store.resolveCall({ pbxCallId: 'b-1', phone: '998900000000', now, cooldownMs: COOLDOWN_MS });

      assert.equal(other.decision, 'autoreply');
      assert.equal(store.getStats().callers, 2);
    } finally {
      cleanup();
    }
  });

  test(`${driver.name}: сброс номера возвращает его в состояние «звонит впервые»`, () => {
    const { store, cleanup } = driver.create();
    try {
      const now = Date.now();
      store.resolveCall({ pbxCallId: 'reset-1', phone: PHONE, now, cooldownMs: COOLDOWN_MS });

      assert.equal(store.deleteCaller(PHONE), true);
      assert.equal(store.getCaller(PHONE), null);

      const again = store.resolveCall({ pbxCallId: 'reset-2', phone: PHONE, now: now + 1000, cooldownMs: COOLDOWN_MS });
      assert.equal(again.decision, 'autoreply');
    } finally {
      cleanup();
    }
  });

  test(`${driver.name}: журнал звонков и данные завершения`, () => {
    const { store, cleanup } = driver.create();
    try {
      const now = Date.now();
      store.resolveCall({
        pbxCallId: 'log-1',
        phone: PHONE,
        calledDid: '998712000000',
        callStart: '2026-01-15 10:00:00',
        now,
        cooldownMs: COOLDOWN_MS,
      });

      store.recordResponse({ pbxCallId: 'log-1', response: { hangup: 1 }, smsStatus: 'sent' });
      assert.equal(store.recordCallEnd({ pbxCallId: 'log-1', duration: 12, disposition: 'answered', isRecorded: 0 }), true);

      const [call] = store.listCalls({ limit: 10 });
      assert.equal(call.pbx_call_id, 'log-1');
      assert.equal(call.decision, 'autoreply');
      assert.equal(call.sms_status, 'sent');
      assert.equal(call.duration, 12);
      assert.equal(call.disposition, 'answered');
      assert.equal(JSON.parse(call.response_json).hangup, 1);

      assert.equal(store.listCalls({ limit: 10, phone: PHONE }).length, 1);
      assert.equal(store.listCalls({ limit: 10, phone: '000000000' }).length, 0);
    } finally {
      cleanup();
    }
  });

  test(`${driver.name}: настройки времени сохраняются и сбрасываются`, () => {
    const { store, cleanup } = driver.create();
    try {
      assert.equal(store.getSetting('cooldown_minutes'), null);
      store.setSetting('cooldown_minutes', 25);
      assert.equal(store.getSetting('cooldown_minutes'), '25');
      store.deleteSetting('cooldown_minutes');
      assert.equal(store.getSetting('cooldown_minutes'), null);
    } finally {
      cleanup();
    }
  });

  test(`${driver.name}: очистка журнала по сроку хранения`, () => {
    const { store, cleanup } = driver.create();
    try {
      const old = Date.now() - 400 * 24 * 60 * 60 * 1000;
      store.resolveCall({ pbxCallId: 'old-1', phone: PHONE, now: old, cooldownMs: COOLDOWN_MS });
      store.resolveCall({ pbxCallId: 'new-1', phone: '998900000001', now: Date.now(), cooldownMs: COOLDOWN_MS });

      assert.equal(store.purgeOldCalls(180), 1);
      assert.equal(store.listCalls({ limit: 10 }).length, 1);
      assert.equal(store.purgeOldCalls(0), 0, 'нулевой срок означает «хранить всё»');
    } finally {
      cleanup();
    }
  });
}

test('SqliteStore: данные переживают переоткрытие файла', () => {
  const dir = mkdtempSync(join(tmpdir(), 'call-router-persist-'));
  const path = join(dir, 'persist.db');

  try {
    const now = Date.UTC(2026, 0, 15, 10, 0, 0);
    const first = new SqliteStore({ path });
    first.resolveCall({ pbxCallId: 'p-1', phone: PHONE, now, cooldownMs: COOLDOWN_MS });
    first.close();

    const reopened = new SqliteStore({ path });
    assert.equal(reopened.getCaller(PHONE).last_call_at, now);

    const afterRestart = reopened.resolveCall({
      pbxCallId: 'p-2',
      phone: PHONE,
      now: now + minutesToMs(5),
      cooldownMs: COOLDOWN_MS,
    });
    assert.equal(afterRestart.decision, 'ignore', 'история должна сохраняться между перезапусками');
    reopened.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
