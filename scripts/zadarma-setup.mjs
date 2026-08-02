#!/usr/bin/env node
/**
 * Настройка личного кабинета Zadarma из командной строки:
 *   1. проверяет ключи API;
 *   2. прописывает адрес обработчика вебхуков;
 *   3. включает нужные типы уведомлений (notify_start обязателен, notify_end — для аналитики).
 *
 * Использование:
 *   node scripts/zadarma-setup.mjs --url https://calls.example.com/zadarma/webhook
 *   node scripts/zadarma-setup.mjs --status        # только показать текущие настройки
 *
 * Ключи берутся из ZADARMA_API_KEY / ZADARMA_API_SECRET (или файла .env).
 */

import { loadDotEnv } from '../src/lib/dotenv.js';
import { ZadarmaClient } from '../src/services/zadarmaClient.js';
import { WEBHOOK_PATH } from '../src/http/app.js';

loadDotEnv();

const args = parseArgs(process.argv.slice(2));

const client = new ZadarmaClient({
  apiKey: process.env.ZADARMA_API_KEY,
  apiSecret: process.env.ZADARMA_API_SECRET,
  baseUrl: process.env.ZADARMA_API_BASE_URL ?? 'https://api.zadarma.com',
});

if (!client.isConfigured) {
  fail('Не заданы ZADARMA_API_KEY и ZADARMA_API_SECRET (личный кабинет -> Настройки -> API).');
}

try {
  console.log('Проверяю ключи API...');
  const balance = await client.getBalance();
  console.log(`  OK. Баланс: ${balance.balance} ${balance.currency}\n`);

  if (!args.status) {
    const url = args.url ?? buildUrlFromEnv();
    if (!url) {
      fail(
        'Укажите адрес обработчика: --url https://ваш-домен/zadarma/webhook ' +
          '(или задайте PUBLIC_BASE_URL в .env).',
      );
    }

    if (!url.startsWith('https://')) {
      fail('Zadarma принимает только HTTPS-адреса вебхука.');
    }

    console.log(`Прописываю адрес вебхука: ${url}`);
    console.log('  (Zadarma сейчас проверит адрес запросом с параметром zd_echo — сервис должен быть запущен)');
    await client.setWebhookUrl(url);
    console.log('  OK\n');

    console.log('Включаю уведомления notify_start и notify_end...');
    await client.setNotifications({
      notify_start: true,
      notify_end: true,
      notify_internal: false,
      notify_answer: false,
      notify_out_start: false,
      notify_out_end: false,
    });
    console.log('  OK\n');
  }

  console.log('Текущие настройки уведомлений:');
  const settings = await client.getCallInfoSettings();
  console.log(JSON.stringify(settings, null, 2));

  console.log('\nГотово. Проверьте обработку звонка: позвоните на виртуальный номер и посмотрите логи сервиса.');
} catch (error) {
  fail(error.message);
}

function buildUrlFromEnv() {
  const base = process.env.PUBLIC_BASE_URL;
  if (!base) return null;
  return `${base.replace(/\/$/, '')}${WEBHOOK_PATH}`;
}

function parseArgs(argv) {
  const result = { status: false, url: null };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--status') result.status = true;
    else if (arg === '--url') result.url = argv[++index];
    else if (arg.startsWith('--url=')) result.url = arg.slice('--url='.length);
    else if (arg === '--help' || arg === '-h') {
      console.log('Использование: node scripts/zadarma-setup.mjs [--url https://.../zadarma/webhook] [--status]');
      process.exit(0);
    }
  }

  return result;
}

function fail(message) {
  console.error(`Ошибка: ${message}`);
  process.exit(1);
}
