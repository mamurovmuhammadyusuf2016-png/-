/**
 * HTTP-слой на встроенном node:http — без Express и прочих зависимостей.
 * Маршрутов немного, а отсутствие внешних пакетов упрощает деплой и снимает
 * вопрос обновления зависимостей на боевом сервере.
 */

import { randomUUID } from 'node:crypto';

import { verifyWebhookSignature } from '../lib/signature.js';
import { maskPhone, normalizePhone } from '../lib/phone.js';
import { handleWebhookEvent } from '../services/callFlow.js';
import { getCooldown, resetCooldownMinutes, setCooldownMinutes } from '../services/settings.js';
import { buildSmsText } from '../domain/messages.js';
import { BodyTooLargeError, parseBody, readRawBody } from './body.js';

export const WEBHOOK_PATH = '/zadarma/webhook';

/**
 * Создаёт обработчик запросов.
 *
 * @param {object} deps config, store, zadarma, forwarder, logger, now
 * @returns {(req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse) => void}
 */
export function createApp(deps) {
  const { logger } = deps;

  return async function handleRequest(request, response) {
    const requestId = randomUUID();
    const startedAt = Date.now();
    const url = new URL(request.url, `http://${request.headers.host ?? 'localhost'}`);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    try {
      await route({ request, response, url, path, deps, requestId });
    } catch (error) {
      const status = error instanceof BodyTooLargeError ? 413 : 500;
      logger.error('Необработанная ошибка запроса', { requestId, path, error });
      if (!response.headersSent) sendJson(response, status, { status: 'error', message: 'Внутренняя ошибка сервера' });
    } finally {
      logger.debug('HTTP-запрос обработан', {
        requestId,
        method: request.method,
        path,
        status: response.statusCode,
        durationMs: Date.now() - startedAt,
      });
    }
  };
}

async function route({ request, response, url, path, deps, requestId }) {
  const { method } = request;

  if (path === '/health' || path === '/healthz') {
    return handleHealth(response, deps);
  }

  if (path === '/' && method === 'GET') {
    return sendJson(response, 200, {
      service: 'zadarma-call-router',
      status: 'ok',
      webhook: WEBHOOK_PATH,
      docs: 'см. README.md и каталог docs/',
    });
  }

  if (path === WEBHOOK_PATH) {
    if (method === 'GET') return handleWebhookVerification({ url, response, deps });
    if (method === 'POST') return handleWebhookPost({ request, response, deps, requestId });
    return sendJson(response, 405, { status: 'error', message: 'Метод не поддерживается' });
  }

  if (path.startsWith('/admin')) {
    return handleAdmin({ request, response, url, path, method, deps });
  }

  return sendJson(response, 404, { status: 'error', message: 'Не найдено' });
}

function handleHealth(response, deps) {
  const { store, config } = deps;
  const cooldown = getCooldown(store, config);

  return sendJson(response, 200, {
    status: 'ok',
    uptimeSeconds: Math.round(process.uptime()),
    cooldownMinutes: cooldown.minutes,
    cooldownSource: cooldown.source,
    autoreplyMode: config.autoreplyMode,
    storeDriver: config.storeDriver,
    signatureVerification: config.zadarma.verifySignature,
  });
}

/**
 * При сохранении адреса вебхука Zadarma дёргает URL с параметром zd_echo
 * и ждёт этот же код в теле ответа обычным текстом.
 */
function handleWebhookVerification({ url, response, deps }) {
  const echo = url.searchParams.get('zd_echo');

  if (echo === null) {
    return sendJson(response, 200, { status: 'ok', message: 'Обработчик вебхука активен' });
  }

  deps.logger.info('Проверка адреса вебхука Zadarma (zd_echo)');
  response.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  response.end(echo);
}

async function handleWebhookPost({ request, response, deps, requestId }) {
  const { config, logger } = deps;

  const raw = await readRawBody(request);
  const payload = parseBody(raw, request.headers['content-type'] ?? '');

  if (!payload.event) {
    logger.warn('Вебхук без поля event', { requestId });
    return sendJson(response, 400, { status: 'error', message: 'Отсутствует параметр event' });
  }

  if (config.zadarma.verifySignature) {
    const valid = verifyWebhookSignature({
      payload,
      signature: request.headers.signature,
      secret: config.zadarma.apiSecret,
    });

    if (!valid) {
      logger.warn('Отклонён вебхук с неверной подписью', {
        requestId,
        event: payload.event,
        caller: maskPhone(payload.caller_id),
      });
      return sendJson(response, 403, { status: 'error', message: 'Неверная подпись' });
    }
  }

  const result = handleWebhookEvent({ payload, deps });

  sendJson(response, 200, result.body);

  // Фоновая часть выполняется уже после ответа провайдеру.
  if (result.after) {
    result.after().catch((error) => logger.error('Ошибка фоновой обработки звонка', { requestId, error }));
  }
}

// --- Служебные эндпоинты ---------------------------------------------------

async function handleAdmin({ request, response, url, path, method, deps }) {
  const { config, store, logger } = deps;

  if (!config.adminToken) {
    return sendJson(response, 503, { status: 'error', message: 'Служебный API отключён: не задан ADMIN_TOKEN' });
  }

  const provided = extractToken(request.headers.authorization, url);
  if (provided !== config.adminToken) {
    return sendJson(response, 401, { status: 'error', message: 'Требуется корректный ADMIN_TOKEN' });
  }

  const limit = clampInt(url.searchParams.get('limit'), 50, 1, 500);
  const offset = clampInt(url.searchParams.get('offset'), 0, 0, 1_000_000);

  if (path === '/admin/stats' && method === 'GET') {
    return sendJson(response, 200, { status: 'ok', ...store.getStats() });
  }

  if (path === '/admin/callers' && method === 'GET') {
    return sendJson(response, 200, {
      status: 'ok',
      callers: store.listCallers({ limit, offset }).map(formatCaller),
    });
  }

  if (path === '/admin/calls' && method === 'GET') {
    const phone = normalizePhone(url.searchParams.get('phone'));
    return sendJson(response, 200, {
      status: 'ok',
      calls: store.listCalls({ limit, offset, phone: phone ?? undefined }).map(formatCall),
    });
  }

  const callerMatch = path.match(/^\/admin\/callers\/([^/]+)$/);
  if (callerMatch) {
    const phone = normalizePhone(decodeURIComponent(callerMatch[1]));
    if (!phone) return sendJson(response, 400, { status: 'error', message: 'Некорректный номер' });

    if (method === 'GET') {
      const caller = store.getCaller(phone);
      if (!caller) return sendJson(response, 404, { status: 'error', message: 'Номер не найден' });
      return sendJson(response, 200, { status: 'ok', caller: formatCaller(caller) });
    }

    if (method === 'DELETE') {
      const removed = store.deleteCaller(phone);
      logger.info('Сброшена история номера', { caller: maskPhone(phone), removed });
      return sendJson(response, 200, { status: 'ok', removed });
    }
  }

  if (path === '/admin/config') {
    if (method === 'GET') {
      const cooldown = getCooldown(store, config);
      return sendJson(response, 200, {
        status: 'ok',
        config: {
          cooldownMinutes: cooldown.minutes,
          cooldownSource: cooldown.source,
          cooldownMinutesFromEnv: config.cooldownMinutes,
          autoreplyMode: config.autoreplyMode,
          autoreplyLanguage: config.autoreplyLanguage,
          repeatCallAction: config.repeatCallAction,
          ownerPhone: config.ownerPhone,
          redirectReturnTimeout: config.redirectReturnTimeout,
          smsPreview: buildSmsText({
            customText: config.autoreplySmsText,
            language: config.autoreplyLanguage,
            cooldownMinutes: cooldown.minutes,
          }),
        },
      });
    }

    if (method === 'PUT' || method === 'PATCH') {
      const raw = await readRawBody(request);
      const body = parseBody(raw, request.headers['content-type'] ?? 'application/json');

      try {
        if (body.cooldownMinutes === undefined || body.cooldownMinutes === null || body.cooldownMinutes === '') {
          resetCooldownMinutes(store);
          logger.info('Окно тишины сброшено к значению из переменной окружения');
        } else {
          const minutes = setCooldownMinutes(store, body.cooldownMinutes);
          logger.info('Окно тишины изменено', { cooldownMinutes: minutes });
        }
      } catch (error) {
        return sendJson(response, 400, { status: 'error', message: error.message });
      }

      const cooldown = getCooldown(store, config);
      return sendJson(response, 200, {
        status: 'ok',
        cooldownMinutes: cooldown.minutes,
        cooldownSource: cooldown.source,
      });
    }
  }

  return sendJson(response, 404, { status: 'error', message: 'Не найдено' });
}

function extractToken(authorizationHeader, url) {
  if (typeof authorizationHeader === 'string') {
    const match = authorizationHeader.match(/^Bearer\s+(.+)$/i);
    if (match) return match[1].trim();
  }
  return url.searchParams.get('token');
}

function clampInt(value, fallback, min, max) {
  const parsed = Number.parseInt(value ?? '', 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(Math.max(parsed, min), max);
}

function formatCaller(row) {
  return {
    phone: `+${row.phone}`,
    lastCallAt: new Date(row.last_call_at).toISOString(),
    firstSeenAt: new Date(row.first_seen_at).toISOString(),
    totalCalls: row.total_calls,
    autoreplyCount: row.autoreply_count,
    connectCount: row.connect_count,
    ignoreCount: row.ignore_count,
    lastDecision: row.last_decision,
  };
}

function formatCall(row) {
  return {
    pbxCallId: row.pbx_call_id,
    phone: row.phone ? `+${row.phone}` : null,
    calledDid: row.called_did,
    decision: row.decision,
    reason: row.reason,
    minutesSincePreviousCall: row.elapsed_ms === null ? null : Math.round((row.elapsed_ms / 60_000) * 10) / 10,
    smsStatus: row.sms_status,
    duration: row.duration,
    disposition: row.disposition,
    createdAt: new Date(row.created_at).toISOString(),
    endedAt: row.ended_at ? new Date(row.ended_at).toISOString() : null,
  };
}

function sendJson(response, statusCode, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  response.end(body);
}
