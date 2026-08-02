/**
 * Схема SQLite. Хранится строкой, чтобы миграция выполнялась при старте
 * без внешних инструментов (CREATE TABLE IF NOT EXISTS идемпотентен).
 */
export const SCHEMA_SQL = `
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
PRAGMA foreign_keys = ON;

-- Основная таблица требования: номер -> время последнего звонка.
CREATE TABLE IF NOT EXISTS callers (
  phone            TEXT    PRIMARY KEY,
  last_call_at     INTEGER NOT NULL,
  first_seen_at    INTEGER NOT NULL,
  total_calls      INTEGER NOT NULL DEFAULT 0,
  autoreply_count  INTEGER NOT NULL DEFAULT 0,
  connect_count    INTEGER NOT NULL DEFAULT 0,
  ignore_count     INTEGER NOT NULL DEFAULT 0,
  last_decision    TEXT,
  last_pbx_call_id TEXT,
  note             TEXT,
  updated_at       INTEGER NOT NULL
);

-- Журнал звонков: аналитика + защита от повторной обработки одного вебхука.
CREATE TABLE IF NOT EXISTS calls (
  pbx_call_id      TEXT    PRIMARY KEY,
  phone            TEXT,
  raw_caller_id    TEXT,
  called_did       TEXT,
  call_start       TEXT,
  decision         TEXT    NOT NULL,
  reason           TEXT,
  elapsed_ms       INTEGER,
  previous_call_at INTEGER,
  response_json    TEXT,
  sms_status       TEXT,
  duration         INTEGER,
  disposition      TEXT,
  is_recorded      INTEGER,
  created_at       INTEGER NOT NULL,
  ended_at         INTEGER
);

CREATE INDEX IF NOT EXISTS idx_calls_created_at ON calls (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_calls_phone      ON calls (phone);
CREATE INDEX IF NOT EXISTS idx_calls_decision   ON calls (decision);
CREATE INDEX IF NOT EXISTS idx_callers_last     ON callers (last_call_at DESC);

-- Настройки, изменяемые на лету (например, окно тишины) без рестарта сервиса.
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
`;
