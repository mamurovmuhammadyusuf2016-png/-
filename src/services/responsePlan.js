/**
 * Перевод решения в ответ, понятный АТС Zadarma.
 *
 * Допустимые ключи ответа на NOTIFY_START (из официальной библиотеки Zadarma):
 *   ivr_play, ivr_saypopular, ivr_saydigits, ivr_saynumber, language,
 *   wait_dtmf, redirect, return_timeout, rewrite_forward_number, hangup, caller_name
 *
 * Меню с выбором цифр (wait_dtmf) не используется — по ТЗ оно не нужно.
 */

import { Decision } from '../domain/decision.js';

/**
 * @typedef {object} ResponsePlan
 * @property {Record<string, unknown>} body JSON, который получит Zadarma
 * @property {{ send: boolean, reason?: string }} sms нужно ли отправлять SMS-автоответ
 * @property {string} summary краткое описание для журнала
 */

/**
 * @param {object} args
 * @param {string} args.decision значение из {@link Decision}
 * @param {object} args.config конфигурация приложения
 * @param {string | null} [args.phone] нормализованный номер звонящего
 * @returns {ResponsePlan}
 */
export function buildResponsePlan({ decision, config, phone }) {
  switch (decision) {
    case Decision.AUTOREPLY:
      return buildAutoreplyPlan({ config });

    case Decision.CONNECT:
      return {
        body: buildRedirectBody({ config, phone }),
        sms: { send: false },
        summary: `Соединение с владельцем (${config.ownerPhone})`,
      };

    case Decision.IGNORE:
      return config.repeatCallAction === 'hangup'
        ? { body: { hangup: 1 }, sms: { send: false }, summary: 'Повтор внутри окна тишины — сброс' }
        : { body: {}, sms: { send: false }, summary: 'Повтор внутри окна тишины — штатный сценарий АТС' };

    case Decision.UNIDENTIFIED:
      return { body: {}, sms: { send: false }, summary: 'Скрытый номер — штатный сценарий АТС' };

    default:
      return { body: {}, sms: { send: false }, summary: `Неизвестное решение: ${decision}` };
  }
}

function buildAutoreplyPlan({ config }) {
  const mode = config.autoreplyMode;
  const body = {};
  const parts = [];

  if ((mode === 'voice' || mode === 'both') && config.autoreplyVoiceFileId) {
    body.ivr_play = config.autoreplyVoiceFileId;
    body.language = config.voiceLanguage;
    parts.push('голосовое сообщение');
  }

  // Звонок не должен уйти на живого сотрудника: кладём трубку после автоответа.
  if (config.hangupAfterAutoreply) {
    body.hangup = 1;
  }

  const sendSms = mode === 'sms' || mode === 'both';
  if (sendSms) parts.push('SMS');

  return {
    body,
    sms: { send: sendSms },
    summary: parts.length > 0 ? `Автоответ: ${parts.join(' + ')}` : 'Первый звонок, автоответ отключён',
  };
}

function buildRedirectBody({ config, phone }) {
  const body = {
    redirect: config.ownerPhone,
    return_timeout: config.redirectReturnTimeout,
  };

  if (config.callerNamePrefix && phone) {
    // Владелец сразу видит, что это повторное обращение.
    body.caller_name = `${config.callerNamePrefix} ${phone}`.trim();
  }

  return body;
}
