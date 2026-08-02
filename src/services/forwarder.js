/**
 * Отправка событий во внешнюю систему (Google Sheets через Apps Script,
 * CRM, Make/Zapier). Точка расширения из ТЗ: «интеграция с таблицей учёта лидов».
 *
 * Работает по принципу «отправил и забыл»: любая ошибка внешней системы
 * не должна влиять на обработку звонка.
 */

export class EventForwarder {
  /**
   * @param {object} options
   * @param {string} [options.url]
   * @param {number} [options.timeoutMs]
   * @param {object} options.logger
   * @param {typeof fetch} [options.fetchImpl]
   */
  constructor({ url, timeoutMs = 5000, logger, fetchImpl = fetch }) {
    this.url = url;
    this.timeoutMs = timeoutMs;
    this.logger = logger;
    this.fetchImpl = fetchImpl;
  }

  get enabled() {
    return Boolean(this.url);
  }

  /**
   * @param {Record<string, unknown>} event
   * @returns {Promise<boolean>} удалось ли доставить
   */
  async send(event) {
    if (!this.enabled) return false;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const response = await this.fetchImpl(this.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(event),
        signal: controller.signal,
      });

      if (!response.ok) {
        this.logger.warn('Внешний вебхук ответил ошибкой', { status: response.status });
        return false;
      }
      return true;
    } catch (error) {
      this.logger.warn('Не удалось отправить событие во внешний вебхук', { error });
      return false;
    } finally {
      clearTimeout(timer);
    }
  }
}
