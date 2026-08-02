/**
 * Ядро бизнес-логики: одно чистое решение по одному входящему звонку.
 *
 * Модуль намеренно не знает ни про Zadarma, ни про базу, ни про HTTP —
 * это делает правило легко тестируемым и позволяет заменить провайдера
 * телефонии, не трогая логику.
 */

/** Возможные решения по входящему звонку. */
export const Decision = Object.freeze({
  /** Номер звонит впервые: проигрываем/отправляем автоответ, на человека не соединяем. */
  AUTOREPLY: 'autoreply',
  /** Прошло не меньше окна тишины: это новое обращение — соединяем с владельцем. */
  CONNECT: 'connect',
  /** Повтор внутри окна тишины: ничего не делаем, чтобы не спамить автоответами. */
  IGNORE: 'ignore',
  /** Номер скрыт — историю вести не по чему, отдаём звонок штатному сценарию АТС. */
  UNIDENTIFIED: 'unidentified',
});

/**
 * @typedef {object} CallDecision
 * @property {string} decision одно из значений {@link Decision}
 * @property {boolean} updateLastCallAt нужно ли записать текущее время как «последний звонок»
 * @property {number | null} elapsedMs сколько прошло с прошлого звонка, мс
 * @property {number | null} elapsedMinutes то же в минутах, округлённое до 0.1
 * @property {boolean} isFirstCall
 * @property {string} reason человекочитаемое объяснение — попадает в журнал
 */

/**
 * Принимает решение по звонку на основании времени предыдущего звонка.
 *
 * Правила (ТЗ):
 *  1. Записи нет            -> автоответ, номер и время сохраняются, на человека не соединяем.
 *  2. Прошло >= cooldownMs  -> соединяем с владельцем, время обновляем.
 *  3. Прошло <  cooldownMs  -> ничего не делаем и НЕ обновляем время.
 *
 * Пункт 3 намеренно не обновляет метку времени: иначе частые повторные звонки
 * бесконечно сдвигали бы окно вперёд, и абонент никогда не смог бы дозвониться.
 *
 * @param {object} args
 * @param {number | null | undefined} args.lastCallAt время прошлого звонка (мс, epoch)
 * @param {number} args.now текущее время (мс, epoch)
 * @param {number} args.cooldownMs окно тишины в миллисекундах
 * @returns {CallDecision}
 */
export function decideCallAction({ lastCallAt, now, cooldownMs }) {
  if (!Number.isFinite(now)) throw new TypeError('decideCallAction: "now" должно быть числом (мс epoch)');
  if (!Number.isFinite(cooldownMs) || cooldownMs < 0) {
    throw new TypeError('decideCallAction: "cooldownMs" должно быть неотрицательным числом');
  }

  if (lastCallAt === null || lastCallAt === undefined) {
    return {
      decision: Decision.AUTOREPLY,
      updateLastCallAt: true,
      elapsedMs: null,
      elapsedMinutes: null,
      isFirstCall: true,
      reason: 'Первый звонок с этого номера — отправляем автоответ.',
    };
  }

  if (!Number.isFinite(lastCallAt)) {
    throw new TypeError('decideCallAction: "lastCallAt" должно быть числом (мс epoch) или null');
  }

  const elapsedMs = now - lastCallAt;
  const elapsedMinutes = Math.round((elapsedMs / 60_000) * 10) / 10;

  if (elapsedMs >= cooldownMs) {
    return {
      decision: Decision.CONNECT,
      updateLastCallAt: true,
      elapsedMs,
      elapsedMinutes,
      isFirstCall: false,
      reason: `С прошлого звонка прошло ${elapsedMinutes} мин (>= ${cooldownMs / 60_000}) — новое обращение.`,
    };
  }

  return {
    decision: Decision.IGNORE,
    updateLastCallAt: false,
    elapsedMs,
    elapsedMinutes,
    isFirstCall: false,
    reason: `С прошлого звонка прошло ${elapsedMinutes} мин (< ${cooldownMs / 60_000}) — повторный звонок внутри окна тишины.`,
  };
}

/**
 * Решение для звонка со скрытым номером: логику «первый/повторный» применить не к чему.
 *
 * @returns {CallDecision}
 */
export function unidentifiedCallDecision() {
  return {
    decision: Decision.UNIDENTIFIED,
    updateLastCallAt: false,
    elapsedMs: null,
    elapsedMinutes: null,
    isFirstCall: false,
    reason: 'Номер звонящего скрыт или не распознан — отдаём звонок штатному сценарию АТС.',
  };
}

/**
 * Перевод минут в миллисекунды.
 *
 * @param {number} minutes
 * @returns {number}
 */
export function minutesToMs(minutes) {
  return Math.round(minutes * 60_000);
}
