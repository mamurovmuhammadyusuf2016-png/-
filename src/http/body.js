/**
 * Чтение и разбор тела запроса без внешних зависимостей.
 * Zadarma присылает вебхуки как application/x-www-form-urlencoded.
 */

const MAX_BODY_BYTES = 64 * 1024;

export class BodyTooLargeError extends Error {
  constructor() {
    super('Тело запроса превышает допустимый размер');
    this.name = 'BodyTooLargeError';
    this.statusCode = 413;
  }
}

/**
 * @param {import('node:http').IncomingMessage} request
 * @returns {Promise<string>}
 */
export function readRawBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;

    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new BodyTooLargeError());
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });

    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

/**
 * Разбирает тело в объект: поддерживаются form-urlencoded и JSON.
 *
 * @param {string} raw
 * @param {string} [contentType]
 * @returns {Record<string, any>}
 */
export function parseBody(raw, contentType = '') {
  if (!raw) return {};

  if (contentType.includes('application/json')) {
    try {
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch {
      return {};
    }
  }

  const params = new URLSearchParams(raw);
  const result = {};

  for (const [key, value] of params) {
    // Вложенные структуры вида wait_dtmf[digits]=1 приводим к объекту.
    const match = key.match(/^([^[]+)\[([^\]]*)\]$/);
    if (match) {
      const [, outer, inner] = match;
      if (typeof result[outer] !== 'object' || result[outer] === null) result[outer] = {};
      result[outer][inner] = value;
    } else {
      result[key] = value;
    }
  }

  return result;
}
