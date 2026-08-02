/**
 * Клиент API Zadarma.
 *
 * Подписывает запросы по схеме
 *   Authorization: <api_key>:<base64(HMAC-SHA1(method + query + md5(query), secret))>
 * и умеет ровно то, что нужно сервису: отправить SMS и настроить вебхуки.
 */

import { buildAuthorizationHeader } from '../lib/signature.js';

export class ZadarmaApiError extends Error {
  constructor(message, { status, body } = {}) {
    super(message);
    this.name = 'ZadarmaApiError';
    this.status = status;
    this.body = body;
  }
}

export class ZadarmaClient {
  /**
   * @param {object} options
   * @param {string} options.apiKey
   * @param {string} options.apiSecret
   * @param {string} [options.baseUrl]
   * @param {number} [options.timeoutMs]
   * @param {typeof fetch} [options.fetchImpl] подмена для тестов
   */
  constructor({ apiKey, apiSecret, baseUrl = 'https://api.zadarma.com', timeoutMs = 8000, fetchImpl = fetch }) {
    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.timeoutMs = timeoutMs;
    this.fetchImpl = fetchImpl;
  }

  get isConfigured() {
    return Boolean(this.apiKey && this.apiSecret);
  }

  /**
   * Выполняет подписанный запрос.
   *
   * @param {string} method путь метода со слешами по краям, например `/v1/sms/send/`
   * @param {Record<string, unknown>} [params]
   * @param {'GET' | 'POST' | 'PUT' | 'DELETE'} [httpMethod]
   * @returns {Promise<any>}
   */
  async request(method, params = {}, httpMethod = 'GET') {
    if (!this.isConfigured) {
      throw new ZadarmaApiError('Не заданы ZADARMA_API_KEY / ZADARMA_API_SECRET');
    }

    const { header, queryString } = buildAuthorizationHeader({
      method,
      params,
      apiKey: this.apiKey,
      apiSecret: this.apiSecret,
    });

    const isBodyRequest = httpMethod === 'POST' || httpMethod === 'PUT';
    const url = `${this.baseUrl}${method}${!isBodyRequest && queryString ? `?${queryString}` : ''}`;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const response = await this.fetchImpl(url, {
        method: httpMethod,
        headers: {
          Authorization: header,
          ...(isBodyRequest ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}),
        },
        ...(isBodyRequest ? { body: queryString } : {}),
        signal: controller.signal,
      });

      const text = await response.text();
      let body;
      try {
        body = text ? JSON.parse(text) : {};
      } catch {
        body = { raw: text };
      }

      if (!response.ok || body?.status === 'error') {
        throw new ZadarmaApiError(body?.message || `Zadarma API вернул HTTP ${response.status}`, {
          status: response.status,
          body,
        });
      }

      return body;
    } catch (error) {
      if (error.name === 'AbortError') {
        throw new ZadarmaApiError(`Таймаут запроса к Zadarma (${this.timeoutMs} мс)`, { status: 504 });
      }
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * Отправляет SMS.
   *
   * @param {object} args
   * @param {string} args.to номер получателя в международном формате
   * @param {string} args.message текст
   * @param {string} [args.sender] senderid, согласованный с Zadarma
   * @param {string} [args.language]
   */
  async sendSms({ to, message, sender, language }) {
    const params = { number: to, message };
    if (sender) params.sender = sender;
    if (language) params.language = language;

    return this.request('/v1/sms/send/', params, 'POST');
  }

  /** Прописывает URL обработчика вебхуков в личном кабинете. */
  async setWebhookUrl(url) {
    return this.request('/v1/pbx/callinfo/url/', { url }, 'POST');
  }

  /**
   * Включает нужные типы уведомлений.
   *
   * @param {Record<string, boolean>} flags например `{ notify_start: true, notify_end: true }`
   */
  async setNotifications(flags) {
    const params = Object.fromEntries(Object.entries(flags).map(([key, value]) => [key, value ? 'true' : 'false']));
    return this.request('/v1/pbx/callinfo/notifications/', params, 'POST');
  }

  /** Текущие настройки вебхуков и уведомлений. */
  async getCallInfoSettings() {
    return this.request('/v1/pbx/callinfo/', {}, 'GET');
  }

  /** Проверка ключей: баланс — самый дешёвый метод для smoke-теста. */
  async getBalance() {
    return this.request('/v1/info/balance/', {}, 'GET');
  }
}
