/**
 * Оркестрация обработки вебхука: разбор события -> решение -> ответ АТС.
 *
 * Всё, что может выполняться медленно (отправка SMS, доставка события во
 * внешнюю систему), вынесено в `after`: ответ провайдеру уходит сразу,
 * иначе АТС успеет отвалиться по таймауту и звонок обработается штатно.
 */

import { Decision, minutesToMs, unidentifiedCallDecision } from '../domain/decision.js';
import { buildSmsText } from '../domain/messages.js';
import { maskPhone, normalizePhone, toE164 } from '../lib/phone.js';
import { buildResponsePlan } from './responsePlan.js';
import { getCooldown } from './settings.js';

export const EVENTS = Object.freeze({
  START: 'NOTIFY_START',
  INTERNAL: 'NOTIFY_INTERNAL',
  ANSWER: 'NOTIFY_ANSWER',
  END: 'NOTIFY_END',
  IVR: 'NOTIFY_IVR',
  OUT_START: 'NOTIFY_OUT_START',
  OUT_END: 'NOTIFY_OUT_END',
  RECORD: 'NOTIFY_RECORD',
});

/**
 * @typedef {object} WebhookResult
 * @property {Record<string, unknown>} body ответ для Zadarma
 * @property {(() => Promise<void>) | null} after фоновая работа после ответа
 * @property {Record<string, unknown>} log поля для журнала
 */

/**
 * @param {object} args
 * @param {Record<string, any>} args.payload разобранное тело вебхука
 * @param {object} args.deps зависимости: config, store, zadarma, forwarder, logger, now
 * @returns {WebhookResult}
 */
export function handleWebhookEvent({ payload, deps }) {
  switch (payload.event) {
    case EVENTS.START:
      return handleNotifyStart({ payload, deps });

    case EVENTS.END:
      return handleNotifyEnd({ payload, deps });

    default:
      // Остальные события нам не нужны, но отвечать нужно корректно.
      return { body: {}, after: null, log: { event: payload.event, handled: false } };
  }
}

function handleNotifyStart({ payload, deps }) {
  const { config, store, logger, forwarder } = deps;
  const now = deps.now ? deps.now() : Date.now();

  const rawCallerId = payload.caller_id ?? null;
  const phone = normalizePhone(rawCallerId);
  const pbxCallId = payload.pbx_call_id ?? null;
  const { minutes: cooldownMinutes, source: cooldownSource } = getCooldown(store, config);

  let outcome;
  if (!phone) {
    outcome = { ...unidentifiedCallDecision(), replayed: false, previousCallAt: null };
  } else {
    outcome = store.resolveCall({
      pbxCallId,
      phone,
      rawCallerId,
      calledDid: payload.called_did ?? null,
      callStart: payload.call_start ?? null,
      now,
      cooldownMs: minutesToMs(cooldownMinutes),
    });
  }

  const plan = buildResponsePlan({ decision: outcome.decision, config, phone });

  // Повторная доставка того же вебхука не должна приводить к повторному SMS.
  const shouldSendSms = plan.sms.send && !outcome.replayed && Boolean(phone);

  if (phone && pbxCallId && !outcome.replayed) {
    store.recordResponse({ pbxCallId, response: plan.body });
  }

  const logContext = {
    event: EVENTS.START,
    pbxCallId,
    caller: maskPhone(rawCallerId),
    calledDid: payload.called_did ?? null,
    decision: outcome.decision,
    reason: outcome.reason,
    elapsedMinutes: outcome.elapsedMinutes,
    cooldownMinutes,
    cooldownSource,
    replayed: outcome.replayed,
    action: plan.summary,
    response: plan.body,
  };

  logger.info('Обработан входящий звонок', logContext);

  const after = async () => {
    if (shouldSendSms) {
      const smsStatus = await sendAutoreplySms({ phone, cooldownMinutes, deps });
      if (pbxCallId) store.recordResponse({ pbxCallId, response: plan.body, smsStatus });
      logContext.smsStatus = smsStatus;
    }

    if (forwarder?.enabled) {
      await forwarder.send({
        type: 'call_decision',
        occurredAt: new Date(now).toISOString(),
        pbxCallId,
        phone: toE164(phone),
        calledDid: payload.called_did ?? null,
        callStart: payload.call_start ?? null,
        decision: outcome.decision,
        isFirstCall: outcome.isFirstCall,
        minutesSincePreviousCall: outcome.elapsedMinutes,
        cooldownMinutes,
        action: plan.summary,
        smsStatus: logContext.smsStatus ?? null,
      });
    }
  };

  return { body: plan.body, after, log: logContext };
}

async function sendAutoreplySms({ phone, cooldownMinutes, deps }) {
  const { config, zadarma, logger } = deps;

  if (!zadarma?.isConfigured) {
    logger.warn('SMS-автоответ пропущен: не настроены ключи API Zadarma', { caller: maskPhone(phone) });
    return 'skipped_no_api_keys';
  }

  const text = buildSmsText({
    customText: config.autoreplySmsText,
    language: config.autoreplyLanguage,
    cooldownMinutes,
  });

  try {
    const result = await zadarma.sendSms({
      to: toE164(phone),
      message: text,
      sender: config.smsSender,
    });
    logger.info('SMS-автоответ отправлен', { caller: maskPhone(phone), messages: result?.messages ?? null });
    return 'sent';
  } catch (error) {
    logger.error('Не удалось отправить SMS-автоответ', { caller: maskPhone(phone), error });
    return `failed: ${error.message}`;
  }
}

function handleNotifyEnd({ payload, deps }) {
  const { store, logger, forwarder } = deps;
  const pbxCallId = payload.pbx_call_id ?? null;

  if (pbxCallId) {
    store.recordCallEnd({
      pbxCallId,
      duration: payload.duration,
      disposition: payload.disposition,
      isRecorded: payload.is_recorded,
      endedAt: deps.now ? deps.now() : Date.now(),
    });
  }

  const logContext = {
    event: EVENTS.END,
    pbxCallId,
    caller: maskPhone(payload.caller_id),
    duration: payload.duration ?? null,
    disposition: payload.disposition ?? null,
  };

  logger.info('Звонок завершён', logContext);

  const after = forwarder?.enabled
    ? async () => {
        await forwarder.send({
          type: 'call_end',
          occurredAt: new Date().toISOString(),
          pbxCallId,
          phone: toE164(normalizePhone(payload.caller_id)),
          duration: payload.duration ?? null,
          disposition: payload.disposition ?? null,
        });
      }
    : null;

  return { body: {}, after, log: logContext };
}

export { Decision };
