/**
 * Сквозные тесты HTTP-слоя: подпись, сценарии звонка, служебные эндпоинты.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';

import { loadConfig } from '../src/config.js';
import { createApp, WEBHOOK_PATH } from '../src/http/app.js';
import { MemoryStore } from '../src/store/memoryStore.js';
import { silentLogger } from '../src/lib/logger.js';
import { buildNotifySignatureString, encodeSignature } from '../src/lib/signature.js';
import { minutesToMs } from '../src/domain/decision.js';

const SECRET = 'webhook-secret';
const ADMIN_TOKEN = 'admin-token';
const CALLER = '998901234567';
const DID = '998712000000';

const BASE_ENV = {
  NODE_ENV: 'test',
  OWNER_PHONE: '998909999999',
  ZADARMA_API_KEY: 'key',
  ZADARMA_API_SECRET: SECRET,
  AUTOREPLY_MODE: 'both',
  AUTOREPLY_VOICE_FILE_ID: '1122',
  AUTOREPLY_LANGUAGE: 'ru',
  CALL_COOLDOWN_MINUTES: '40',
  ADMIN_TOKEN,
  STORE_DRIVER: 'memory',
};

/** Поднимает приложение на случайном порту и возвращает помощники для запросов. */
async function startTestServer(envOverrides = {}) {
  const config = loadConfig({ ...BASE_ENV, ...envOverrides });
  const store = new MemoryStore();

  const sentSms = [];
  const zadarma = {
    isConfigured: true,
    async sendSms(args) {
      sentSms.push(args);
      return { status: 'success', messages: 1 };
    },
  };

  const forwardedEvents = [];
  const forwarder = {
    enabled: true,
    async send(event) {
      forwardedEvents.push(event);
      return true;
    },
  };

  let currentTime = Date.UTC(2026, 0, 15, 10, 0, 0);
  const deps = { config, store, zadarma, forwarder, logger: silentLogger, now: () => currentTime };

  const server = createServer(createApp(deps));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');

  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  return {
    baseUrl,
    store,
    sentSms,
    forwardedEvents,
    config,
    setTime: (value) => {
      currentTime = value;
    },
    advanceMinutes: (minutes) => {
      currentTime += minutesToMs(minutes);
    },
    now: () => currentTime,
    /** POST вебхука с корректной подписью (если signature не передан явно). */
    async postWebhook(payload, { signature, contentType = 'application/x-www-form-urlencoded' } = {}) {
      const body = new URLSearchParams(payload).toString();
      const response = await fetch(`${baseUrl}${WEBHOOK_PATH}`, {
        method: 'POST',
        headers: {
          'Content-Type': contentType,
          Signature: signature ?? encodeSignature(buildNotifySignatureString(payload), SECRET),
        },
        body,
      });
      return { status: response.status, body: await response.json() };
    },
    async admin(path, { method = 'GET', token = ADMIN_TOKEN, body } = {}) {
      const response = await fetch(`${baseUrl}${path}`, {
        method,
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      return { status: response.status, body: await response.json() };
    },
    async close() {
      server.close();
      await once(server, 'close');
    },
  };
}

function startPayload(overrides = {}) {
  return {
    event: 'NOTIFY_START',
    caller_id: CALLER,
    called_did: DID,
    call_start: '2026-01-15 10:00:00',
    pbx_call_id: 'in_test_1',
    ...overrides,
  };
}

test('проверка адреса вебхука: zd_echo возвращается как есть', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const response = await fetch(`${app.baseUrl}${WEBHOOK_PATH}?zd_echo=abc123xyz`);

  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'abc123xyz');
});

test('вебхук с неверной подписью отклоняется', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const result = await app.postWebhook(startPayload(), { signature: 'ZmFrZS1zaWduYXR1cmU=' });

  assert.equal(result.status, 403);
  assert.equal(app.store.getCaller(CALLER), null, 'состояние не должно меняться');
});

test('вебхук без подписи отклоняется', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const body = new URLSearchParams(startPayload()).toString();
  const response = await fetch(`${app.baseUrl}${WEBHOOK_PATH}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });

  assert.equal(response.status, 403);
});

test('запрос без event отклоняется', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const result = await app.postWebhook({ caller_id: CALLER });
  assert.equal(result.status, 400);
});

test('сценарий 1: первый звонок — голосовой автоответ, SMS и никакого соединения', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const result = await app.postWebhook(startPayload());

  assert.equal(result.status, 200);
  assert.equal(result.body.ivr_play, '1122');
  assert.equal(result.body.hangup, 1);
  assert.equal(result.body.redirect, undefined, 'первый звонок не должен соединяться с человеком');

  assert.equal(app.store.getCaller(CALLER).last_call_at, app.now());

  await waitFor(() => app.sentSms.length === 1);
  assert.equal(app.sentSms[0].to, `+${CALLER}`);
  assert.match(app.sentSms[0].message, /40 минут/);
});

test('сценарий 3: повторный звонок внутри окна — пустой ответ, SMS не отправляется', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  await waitFor(() => app.sentSms.length === 1);

  const lastCallAt = app.store.getCaller(CALLER).last_call_at;
  app.advanceMinutes(39);

  const result = await app.postWebhook(startPayload({ pbx_call_id: 'in_test_2' }));

  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {}, 'звонок отдаётся штатному сценарию линии');
  assert.equal(app.sentSms.length, 1, 'повторный автоответ не отправляется');
  assert.equal(app.store.getCaller(CALLER).last_call_at, lastCallAt, 'окно тишины не сдвигается');
});

test('сценарий 2: через 40 минут звонок соединяется с владельцем', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  app.advanceMinutes(40);

  const result = await app.postWebhook(startPayload({ pbx_call_id: 'in_test_3' }));

  assert.equal(result.body.redirect, '998909999999');
  assert.equal(result.body.return_timeout, 25);
  assert.equal(result.body.ivr_play, undefined);
  assert.equal(app.store.getCaller(CALLER).last_call_at, app.now(), 'время последнего звонка обновляется');
});

test('повторная доставка того же вебхука не удваивает SMS', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const payload = startPayload();
  const first = await app.postWebhook(payload);
  await waitFor(() => app.sentSms.length === 1);

  const replay = await app.postWebhook(payload);

  assert.equal(replay.status, 200);
  assert.deepEqual(replay.body, first.body);
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(app.sentSms.length, 1);
  assert.equal(app.store.getCaller(CALLER).total_calls, 1);
});

test('скрытый номер обрабатывается штатным сценарием и не попадает в базу', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const result = await app.postWebhook(startPayload({ caller_id: 'anonymous', pbx_call_id: 'in_hidden' }));

  assert.deepEqual(result.body, {});
  assert.equal(app.store.getStats().callers, 0);
  assert.equal(app.sentSms.length, 0);
});

test('режим AUTOREPLY_MODE=sms не проигрывает файл, но кладёт трубку', async (t) => {
  const app = await startTestServer({ AUTOREPLY_MODE: 'sms', AUTOREPLY_VOICE_FILE_ID: '' });
  t.after(() => app.close());

  const result = await app.postWebhook(startPayload());

  assert.equal(result.body.ivr_play, undefined);
  assert.equal(result.body.hangup, 1);
  await waitFor(() => app.sentSms.length === 1);
});

test('режим REPEAT_CALL_ACTION=hangup сбрасывает повторные звонки', async (t) => {
  const app = await startTestServer({ REPEAT_CALL_ACTION: 'hangup' });
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  app.advanceMinutes(5);
  const result = await app.postWebhook(startPayload({ pbx_call_id: 'in_test_4' }));

  assert.deepEqual(result.body, { hangup: 1 });
});

test('узбекский текст SMS выбирается конфигурацией', async (t) => {
  const app = await startTestServer({ AUTOREPLY_LANGUAGE: 'uz' });
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  await waitFor(() => app.sentSms.length === 1);

  assert.match(app.sentSms[0].message, /Assalomu alaykum/);
  assert.match(app.sentSms[0].message, /40 daqiqadan/);
});

test('произвольный текст из конфигурации перекрывает встроенный', async (t) => {
  const app = await startTestServer({ AUTOREPLY_SMS_TEXT: 'Наш текст. Перезвоните через {{cooldownMinutes}} мин.' });
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  await waitFor(() => app.sentSms.length === 1);

  assert.equal(app.sentSms[0].message, 'Наш текст. Перезвоните через 40 мин.');
});

test('NOTIFY_END дополняет журнал длительностью и статусом', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  await app.postWebhook(startPayload());

  const endPayload = {
    event: 'NOTIFY_END',
    caller_id: CALLER,
    called_did: DID,
    call_start: '2026-01-15 10:00:00',
    pbx_call_id: 'in_test_1',
    duration: '18',
    disposition: 'answered',
    is_recorded: '1',
  };

  const result = await app.postWebhook(endPayload);
  assert.equal(result.status, 200);

  const [call] = app.store.listCalls({ limit: 5 });
  assert.equal(call.duration, 18);
  assert.equal(call.disposition, 'answered');
  assert.equal(call.is_recorded, 1);
});

test('события отправляются во внешнюю систему для аналитики', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  await waitFor(() => app.forwardedEvents.length >= 1);

  const event = app.forwardedEvents[0];
  assert.equal(event.type, 'call_decision');
  assert.equal(event.phone, `+${CALLER}`);
  assert.equal(event.decision, 'autoreply');
  assert.equal(event.isFirstCall, true);
  assert.equal(event.cooldownMinutes, 40);
});

test('неизвестные события не ломают обработчик', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const result = await app.postWebhook({
    event: 'NOTIFY_RECORD',
    caller_id: CALLER,
    called_did: DID,
    call_start: '2026-01-15 10:00:00',
  });

  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {});
});

// --- Служебные эндпоинты ---------------------------------------------------

test('админка требует токен', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  assert.equal((await app.admin('/admin/stats', { token: null })).status, 401);
  assert.equal((await app.admin('/admin/stats', { token: 'wrong-token' })).status, 401);
  assert.equal((await app.admin('/admin/stats')).status, 200);
});

test('окно тишины меняется на лету без перезапуска', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  app.advanceMinutes(20);

  // По умолчанию 40 минут — звонок через 20 минут игнорируется.
  assert.deepEqual((await app.postWebhook(startPayload({ pbx_call_id: 'c-2' }))).body, {});

  const updated = await app.admin('/admin/config', { method: 'PUT', body: { cooldownMinutes: 15 } });
  assert.equal(updated.status, 200);
  assert.equal(updated.body.cooldownMinutes, 15);
  assert.equal(updated.body.cooldownSource, 'runtime');

  // С новым окном тот же интервал уже считается новым обращением.
  const afterChange = await app.postWebhook(startPayload({ pbx_call_id: 'c-3' }));
  assert.equal(afterChange.body.redirect, '998909999999');

  const reset = await app.admin('/admin/config', { method: 'PUT', body: {} });
  assert.equal(reset.body.cooldownMinutes, 40);
  assert.equal(reset.body.cooldownSource, 'env');
});

test('некорректное значение окна отклоняется', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const result = await app.admin('/admin/config', { method: 'PUT', body: { cooldownMinutes: -5 } });
  assert.equal(result.status, 400);
});

test('журнал и статистика доступны через админку', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  await app.postWebhook(startPayload());

  const callers = await app.admin('/admin/callers');
  assert.equal(callers.body.callers[0].phone, `+${CALLER}`);
  assert.equal(callers.body.callers[0].totalCalls, 1);

  const calls = await app.admin(`/admin/calls?phone=%2B${CALLER}`);
  assert.equal(calls.body.calls[0].decision, 'autoreply');

  const stats = await app.admin('/admin/stats');
  assert.equal(stats.body.callers, 1);
  assert.equal(stats.body.callsByDecision.autoreply, 1);
});

test('сброс номера через админку возвращает его в состояние «первый звонок»', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  await app.postWebhook(startPayload());
  const removed = await app.admin(`/admin/callers/%2B${CALLER}`, { method: 'DELETE' });
  assert.equal(removed.body.removed, true);

  app.advanceMinutes(1);
  const result = await app.postWebhook(startPayload({ pbx_call_id: 'in_after_reset' }));
  assert.equal(result.body.ivr_play, '1122', 'номер снова считается новым');
});

test('/health отдаёт действующие настройки', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const response = await fetch(`${app.baseUrl}/health`);
  const body = await response.json();

  assert.equal(response.status, 200);
  assert.equal(body.status, 'ok');
  assert.equal(body.cooldownMinutes, 40);
  assert.equal(body.signatureVerification, true);
});

test('неизвестный маршрут отдаёт 404', async (t) => {
  const app = await startTestServer();
  t.after(() => app.close());

  const response = await fetch(`${app.baseUrl}/нет-такого`);
  assert.equal(response.status, 404);
});

/** Ждёт выполнения фонового действия (отправка SMS происходит после ответа). */
async function waitFor(predicate, { timeoutMs = 1000, intervalMs = 10 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error('Условие не выполнилось за отведённое время');
}
