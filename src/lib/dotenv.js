/**
 * Простейшая загрузка .env — чтобы не тянуть пакет dotenv.
 *
 * Уже существующие переменные окружения не перезаписываются: на хостинге
 * (Railway/Render/systemd) приоритет должен оставаться за настройками платформы.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * @param {string} [path] путь к файлу .env
 * @returns {number} сколько переменных загружено
 */
export function loadDotEnv(path = '.env') {
  let content;
  try {
    content = readFileSync(resolve(path), 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return 0;
    throw error;
  }

  let loaded = 0;

  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#')) continue;

    const separator = trimmed.indexOf('=');
    if (separator === -1) continue;

    const key = trimmed.slice(0, separator).trim();
    if (!key || Object.hasOwn(process.env, key)) continue;

    let value = trimmed.slice(separator + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }

    process.env[key] = value.replace(/\\n/g, '\n');
    loaded += 1;
  }

  return loaded;
}
