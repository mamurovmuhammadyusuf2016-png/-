/**
 * Минимальный структурированный логгер: одна строка JSON на событие.
 * Удобно читается `docker logs`, Railway/Render и `journalctl`.
 */

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

/** Ключи, значения которых никогда не попадают в лог целиком. */
const SECRET_KEYS = new Set(['apiSecret', 'apiKey', 'adminToken', 'secret', 'token', 'password', 'signature']);

function redact(value, depth = 0) {
  if (value === null || typeof value !== 'object' || depth > 4) return value;
  if (Array.isArray(value)) return value.map((item) => redact(item, depth + 1));

  const result = {};
  for (const [key, item] of Object.entries(value)) {
    result[key] = SECRET_KEYS.has(key) ? '[redacted]' : redact(item, depth + 1);
  }
  return result;
}

export function createLogger({ level = 'info', stream = process.stdout } = {}) {
  const threshold = LEVELS[level] ?? LEVELS.info;

  function write(levelName, message, context) {
    if (LEVELS[levelName] < threshold) return;

    const entry = {
      time: new Date().toISOString(),
      level: levelName,
      message,
      ...(context ? redact(context) : {}),
    };

    if (entry.error instanceof Error) {
      entry.error = { name: entry.error.name, message: entry.error.message, stack: entry.error.stack };
    }

    stream.write(`${JSON.stringify(entry)}\n`);
  }

  return {
    level,
    debug: (message, context) => write('debug', message, context),
    info: (message, context) => write('info', message, context),
    warn: (message, context) => write('warn', message, context),
    error: (message, context) => write('error', message, context),
  };
}

/** Логгер-заглушка для тестов. */
export const silentLogger = {
  level: 'silent',
  debug() {},
  info() {},
  warn() {},
  error() {},
};
