/**
 * Конфигурация приложения.
 *
 * Все параметры задаются переменными окружения (файл .env / переменные хостинга),
 * поэтому таймаут повторного звонка, тексты автоответа и режим автоответа
 * меняются без правки кода.
 */

const TRUE_VALUES = new Set(['1', 'true', 'yes', 'y', 'on', 'да']);
const FALSE_VALUES = new Set(['0', 'false', 'no', 'n', 'off', 'нет']);

/** Языки, для которых у Zadarma есть встроенные голосовые фразы. */
export const ZADARMA_TTS_LANGUAGES = ['ru', 'ua', 'en', 'es', 'pl', 'fr', 'de'];

export const AUTOREPLY_MODES = ['voice', 'sms', 'both', 'none'];
export const REPEAT_CALL_ACTIONS = ['ignore', 'hangup'];
export const STORE_DRIVERS = ['sqlite', 'memory'];

class ConfigError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConfigError';
  }
}

function readString(env, key, fallback) {
  const raw = env[key];
  if (raw === undefined || raw === null || String(raw).trim() === '') return fallback;
  return String(raw).trim();
}

function readBoolean(env, key, fallback) {
  const raw = readString(env, key);
  if (raw === undefined) return fallback;
  const normalized = raw.toLowerCase();
  if (TRUE_VALUES.has(normalized)) return true;
  if (FALSE_VALUES.has(normalized)) return false;
  throw new ConfigError(`${key}: ожидается true/false, получено "${raw}"`);
}

function readNumber(env, key, fallback, { min = -Infinity, max = Infinity } = {}) {
  const raw = readString(env, key);
  if (raw === undefined) return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value)) {
    throw new ConfigError(`${key}: ожидается число, получено "${raw}"`);
  }
  if (value < min || value > max) {
    throw new ConfigError(`${key}: значение ${value} вне допустимого диапазона [${min}, ${max}]`);
  }
  return value;
}

function readEnum(env, key, allowed, fallback) {
  const raw = readString(env, key, fallback);
  if (!allowed.includes(raw)) {
    throw new ConfigError(`${key}: допустимые значения — ${allowed.join(', ')}; получено "${raw}"`);
  }
  return raw;
}

/**
 * Собирает и валидирует конфигурацию.
 *
 * @param {Record<string, string | undefined>} [env] источник переменных окружения
 * @returns {Readonly<object>} неизменяемый объект конфигурации
 */
export function loadConfig(env = process.env) {
  const nodeEnv = readString(env, 'NODE_ENV', 'development');
  const isProduction = nodeEnv === 'production';

  const config = {
    nodeEnv,
    isProduction,
    host: readString(env, 'HOST', '0.0.0.0'),
    port: readNumber(env, 'PORT', 3000, { min: 1, max: 65535 }),
    logLevel: readEnum(env, 'LOG_LEVEL', ['debug', 'info', 'warn', 'error'], 'info'),

    // --- Основное бизнес-правило -------------------------------------------
    /** Окно «тишины» в минутах. Звонок раньше окна игнорируется, позже — соединяется. */
    cooldownMinutes: readNumber(env, 'CALL_COOLDOWN_MINUTES', 40, { min: 0, max: 60 * 24 * 30 }),

    /** Куда соединять «новое обращение» — личный номер владельца или внутренний номер АТС. */
    ownerPhone: readString(env, 'OWNER_PHONE'),

    /** Что делать с повторным звонком внутри окна: ничего (`ignore`) или сбросить (`hangup`). */
    repeatCallAction: readEnum(env, 'REPEAT_CALL_ACTION', REPEAT_CALL_ACTIONS, 'ignore'),

    // --- Автоответ ----------------------------------------------------------
    autoreplyMode: readEnum(env, 'AUTOREPLY_MODE', AUTOREPLY_MODES, 'both'),
    /** Язык текстов по умолчанию: ru | uz. */
    autoreplyLanguage: readString(env, 'AUTOREPLY_LANGUAGE', 'ru').toLowerCase(),
    /** ID аудиофайла, загруженного в личном кабинете Zadarma (голосовой автоответ). */
    autoreplyVoiceFileId: readString(env, 'AUTOREPLY_VOICE_FILE_ID'),
    /** Текст SMS. Поддерживает плейсхолдер {{cooldownMinutes}}. Пусто — берётся текст по умолчанию. */
    autoreplySmsText: readString(env, 'AUTOREPLY_SMS_TEXT'),
    /** Имя отправителя SMS (senderid), согласованное с Zadarma. */
    smsSender: readString(env, 'SMS_SENDER'),
    /** Класть трубку сразу после проигрывания файла, чтобы звонок не ушёл на сотрудника. */
    hangupAfterAutoreply: readBoolean(env, 'HANGUP_AFTER_AUTOREPLY', true),
    /** Язык встроенных фраз Zadarma для голосового ответа. */
    voiceLanguage: readEnum(env, 'VOICE_LANGUAGE', ZADARMA_TTS_LANGUAGES, 'ru'),

    // --- Переадресация ------------------------------------------------------
    /** Сколько секунд звонить владельцу, прежде чем вернуть вызов в сценарий АТС. */
    redirectReturnTimeout: readNumber(env, 'REDIRECT_RETURN_TIMEOUT', 25, { min: 0, max: 600 }),
    /** Подпись, которую увидит владелец вместо номера (опционально). */
    callerNamePrefix: readString(env, 'CALLER_NAME_PREFIX'),

    // --- Zadarma API --------------------------------------------------------
    zadarma: {
      apiKey: readString(env, 'ZADARMA_API_KEY'),
      apiSecret: readString(env, 'ZADARMA_API_SECRET'),
      apiBaseUrl: readString(env, 'ZADARMA_API_BASE_URL', 'https://api.zadarma.com'),
      /** Проверять заголовок Signature у входящих вебхуков. Отключать только локально. */
      verifySignature: readBoolean(env, 'ZADARMA_VERIFY_SIGNATURE', true),
      requestTimeoutMs: readNumber(env, 'ZADARMA_REQUEST_TIMEOUT_MS', 8000, { min: 500, max: 60000 }),
    },

    // --- Хранилище ----------------------------------------------------------
    storeDriver: readEnum(env, 'STORE_DRIVER', STORE_DRIVERS, 'sqlite'),
    databasePath: readString(env, 'DATABASE_PATH', './data/calls.db'),
    /** Сколько дней хранить журнал звонков (0 — хранить всё). */
    callLogRetentionDays: readNumber(env, 'CALL_LOG_RETENTION_DAYS', 180, { min: 0, max: 3650 }),

    // --- Интеграции и админка ----------------------------------------------
    /** Токен для служебных эндпоинтов /admin/*. Без него админка выключена. */
    adminToken: readString(env, 'ADMIN_TOKEN'),
    /** URL внешнего приёмника событий (Google Sheets через Apps Script, CRM, Make/Zapier). */
    forwardWebhookUrl: readString(env, 'FORWARD_WEBHOOK_URL'),
    forwardWebhookTimeoutMs: readNumber(env, 'FORWARD_WEBHOOK_TIMEOUT_MS', 5000, { min: 500, max: 30000 }),
  };

  validate(config);
  return deepFreeze(config);
}

function validate(config) {
  const problems = [];

  if (!config.ownerPhone) {
    problems.push('OWNER_PHONE обязателен — на этот номер соединяются «новые обращения».');
  }

  const needsApi =
    config.zadarma.verifySignature ||
    config.autoreplyMode === 'sms' ||
    config.autoreplyMode === 'both';

  if (needsApi && !config.zadarma.apiSecret) {
    problems.push(
      'ZADARMA_API_SECRET обязателен для проверки подписи вебхука и отправки SMS ' +
        '(отключить проверку можно через ZADARMA_VERIFY_SIGNATURE=false — только для локальной отладки).',
    );
  }

  if ((config.autoreplyMode === 'sms' || config.autoreplyMode === 'both') && !config.zadarma.apiKey) {
    problems.push('ZADARMA_API_KEY обязателен для отправки SMS-автоответа.');
  }

  if ((config.autoreplyMode === 'voice' || config.autoreplyMode === 'both') && !config.autoreplyVoiceFileId) {
    problems.push(
      'AUTOREPLY_VOICE_FILE_ID обязателен для голосового автоответа: ' +
        'загрузите аудиофайл в личном кабинете Zadarma и укажите его ID (см. docs/01-zadarma-setup.md).',
    );
  }

  if (!['ru', 'uz'].includes(config.autoreplyLanguage)) {
    problems.push('AUTOREPLY_LANGUAGE: поддерживаются значения ru и uz.');
  }

  if (config.isProduction && !config.zadarma.verifySignature) {
    problems.push('ZADARMA_VERIFY_SIGNATURE=false недопустимо при NODE_ENV=production.');
  }

  if (config.isProduction && !config.adminToken) {
    problems.push('ADMIN_TOKEN обязателен в production (иначе служебные эндпоинты недоступны).');
  }

  if (problems.length > 0) {
    throw new ConfigError(`Ошибки конфигурации:\n- ${problems.join('\n- ')}`);
  }
}

function deepFreeze(object) {
  for (const value of Object.values(object)) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) deepFreeze(value);
  }
  return Object.freeze(object);
}

export { ConfigError };
