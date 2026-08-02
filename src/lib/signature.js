/**
 * Подписи Zadarma.
 *
 * Две разные схемы:
 *  1. Входящие вебхуки — заголовок `Signature`:
 *     base64(HMAC-SHA1(<строка подписи события>, secret))
 *     Строка подписи для NOTIFY_START / NOTIFY_INTERNAL / NOTIFY_END:
 *       caller_id + called_did + call_start
 *     для NOTIFY_ANSWER:
 *       caller_id + destination + call_start
 *
 *  2. Исходящие запросы к API — заголовок `Authorization: <key>:<sign>`:
 *     base64(HMAC-SHA1(method + queryString + md5(queryString), secret))
 *     где queryString — параметры, отсортированные по имени и закодированные
 *     как http_build_query (RFC 1738: пробел кодируется знаком «+»).
 */

import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

/**
 * Строка, из которой Zadarma считает подпись конкретного события.
 *
 * @param {Record<string, unknown>} payload разобранное тело вебхука
 * @returns {string}
 */
export function buildNotifySignatureString(payload) {
  const callerId = payload.caller_id ?? '';
  const callStart = payload.call_start ?? '';

  // У NOTIFY_ANSWER вместо called_did приходит destination.
  const target = payload.event === 'NOTIFY_ANSWER' ? (payload.destination ?? '') : (payload.called_did ?? '');

  return `${callerId}${target}${callStart}`;
}

/**
 * base64(HMAC-SHA1(data, secret)) — общая для обеих схем операция.
 *
 * @param {string} data
 * @param {string} secret
 * @returns {string}
 */
export function encodeSignature(data, secret) {
  return createHmac('sha1', secret).update(data, 'utf8').digest('base64');
}

/**
 * Сравнение подписей за постоянное время (защита от timing-атак).
 *
 * @param {string | undefined | null} received
 * @param {string} expected
 * @returns {boolean}
 */
export function signaturesMatch(received, expected) {
  if (typeof received !== 'string' || received.length === 0) return false;

  const receivedBuffer = Buffer.from(received, 'utf8');
  const expectedBuffer = Buffer.from(expected, 'utf8');
  if (receivedBuffer.length !== expectedBuffer.length) return false;

  return timingSafeEqual(receivedBuffer, expectedBuffer);
}

/**
 * Полная проверка подписи вебхука.
 *
 * @param {object} args
 * @param {Record<string, unknown>} args.payload разобранное тело запроса
 * @param {string | undefined} args.signature значение заголовка Signature
 * @param {string} args.secret секретный ключ API
 * @returns {boolean}
 */
export function verifyWebhookSignature({ payload, signature, secret }) {
  if (!secret) return false;
  const expected = encodeSignature(buildNotifySignatureString(payload), secret);
  return signaturesMatch(signature, expected);
}

/**
 * Строка параметров для подписи запроса к API (аналог http_build_query в PHP).
 *
 * @param {Record<string, string | number | string[]>} params
 * @returns {string}
 */
export function buildQueryString(params) {
  const parts = [];

  for (const key of Object.keys(params).sort()) {
    const value = params[key];
    if (value === undefined || value === null) continue;

    if (Array.isArray(value)) {
      // PHP-стиль массивов: number[0]=...&number[1]=...
      value.forEach((item, index) => {
        parts.push(`${rfc1738(`${key}[${index}]`)}=${rfc1738(String(item))}`);
      });
    } else {
      parts.push(`${rfc1738(key)}=${rfc1738(String(value))}`);
    }
  }

  return parts.join('&');
}

/** RFC 1738: как encodeURIComponent, но пробел — «+», а не «%20». */
function rfc1738(value) {
  return encodeURIComponent(value)
    .replace(/%20/g, '+')
    .replace(/[!'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`);
}

/**
 * Заголовок Authorization для запроса к API Zadarma.
 *
 * @param {object} args
 * @param {string} args.method путь метода, например `/v1/sms/send/`
 * @param {Record<string, unknown>} args.params параметры запроса
 * @param {string} args.apiKey
 * @param {string} args.apiSecret
 * @returns {{ header: string, queryString: string }}
 */
export function buildAuthorizationHeader({ method, params, apiKey, apiSecret }) {
  const queryString = buildQueryString(params);
  const md5 = createHash('md5').update(queryString, 'utf8').digest('hex');
  const sign = encodeSignature(`${method}${queryString}${md5}`, apiSecret);

  return { header: `${apiKey}:${sign}`, queryString };
}
