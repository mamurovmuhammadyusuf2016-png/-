/**
 * Тексты автоответа.
 *
 * Тексты вынесены отдельно, чтобы владелец мог утвердить формулировки,
 * не трогая код: значение из переменной окружения AUTOREPLY_SMS_TEXT
 * всегда перекрывает вариант по умолчанию.
 *
 * Поддерживаемые плейсхолдеры:
 *   {{cooldownMinutes}} — текущее окно тишины в минутах (по умолчанию 40)
 */

export const DEFAULT_SMS_TEXTS = Object.freeze({
  ru:
    'Здравствуйте! Спасибо за звонок. Сейчас мы не можем ответить, но уже увидели ваш номер ' +
    'и свяжемся с вами в ближайшее время. Если вопрос срочный — перезвоните через ' +
    '{{cooldownMinutes}} минут, и мы ответим лично.',
  uz:
    "Assalomu alaykum! Qo'ng'irog'ingiz uchun rahmat. Hozir javob bera olmadik, lekin " +
    "raqamingizni qabul qildik va tez orada o'zimiz bog'lanamiz. Shoshilinch bo'lsa, " +
    "{{cooldownMinutes}} daqiqadan keyin qayta qo'ng'iroq qiling — shaxsan javob beramiz.",
});

/**
 * Подставляет значения плейсхолдеров в шаблон.
 *
 * @param {string} template
 * @param {Record<string, string | number>} values
 * @returns {string}
 */
export function renderTemplate(template, values) {
  return String(template).replace(/\{\{\s*(\w+)\s*\}\}/g, (match, key) =>
    Object.hasOwn(values, key) ? String(values[key]) : match,
  );
}

/**
 * Итоговый текст SMS-автоответа.
 *
 * @param {object} args
 * @param {string} [args.customText] текст из конфигурации (AUTOREPLY_SMS_TEXT)
 * @param {string} [args.language] ru | uz
 * @param {number} args.cooldownMinutes
 * @returns {string}
 */
export function buildSmsText({ customText, language = 'ru', cooldownMinutes }) {
  const template = customText || DEFAULT_SMS_TEXTS[language] || DEFAULT_SMS_TEXTS.ru;
  return renderTemplate(template, { cooldownMinutes });
}
