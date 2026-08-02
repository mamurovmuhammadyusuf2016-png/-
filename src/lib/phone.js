/**
 * Нормализация телефонных номеров.
 *
 * Номер — это ключ в базе, поэтому один и тот же абонент должен давать
 * одинаковый ключ независимо от того, как оператор передал его в вебхуке:
 * `+998901234567`, `998901234567`, `00998901234567`, `8 (90) 123-45-67`.
 */

/** Значения caller_id, означающие скрытый / неопределённый номер. */
const ANONYMOUS_VALUES = new Set(['', 'anonymous', 'unknown', 'unavailable', 'restricted', 'private', 'hidden']);

/**
 * Приводит номер к каноническому виду: только цифры, международный формат без «+».
 *
 * @param {string | number | null | undefined} input
 * @returns {string | null} нормализованный номер или null, если номер не распознан
 */
export function normalizePhone(input) {
  if (input === null || input === undefined) return null;

  const raw = String(input).trim().toLowerCase();
  if (ANONYMOUS_VALUES.has(raw)) return null;

  let digits = raw.replace(/\D/g, '');
  if (digits === '') return null;

  // Международный префикс набора: 00XXXXXXXXX -> XXXXXXXXX
  if (digits.startsWith('00')) digits = digits.slice(2);

  // Слишком короткий номер — это внутренний номер АТС, а не абонент.
  if (digits.length < 5) return null;

  // Защита от мусорных значений произвольной длины.
  if (digits.length > 15) digits = digits.slice(-15);

  return digits;
}

/**
 * Проверяет, что caller_id пригоден для логики «первый / повторный звонок».
 * Для скрытых номеров вести историю невозможно.
 *
 * @param {string | null | undefined} input
 * @returns {boolean}
 */
export function isAnonymousCaller(input) {
  return normalizePhone(input) === null;
}

/**
 * Формат для показа человеку и для отправки SMS через API Zadarma.
 *
 * @param {string | null} normalized нормализованный номер
 * @returns {string | null} номер в формате +998901234567
 */
export function toE164(normalized) {
  if (!normalized) return null;
  return `+${normalized}`;
}

/**
 * Маскирует номер для логов: 998901234567 -> 99890****567.
 * Позволяет отлаживать поток звонков, не складывая базу номеров в логи.
 *
 * @param {string | null | undefined} value
 * @returns {string}
 */
export function maskPhone(value) {
  const normalized = normalizePhone(value);
  if (!normalized) return 'anonymous';
  if (normalized.length <= 7) return `${normalized.slice(0, 2)}***`;
  return `${normalized.slice(0, 5)}****${normalized.slice(-3)}`;
}
