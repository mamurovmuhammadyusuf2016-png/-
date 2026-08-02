#!/usr/bin/env node
/**
 * Имитация входящего звонка: отправляет на сервис такой же подписанный вебхук,
 * какой присылает Zadarma. Позволяет проверить логику, не совершая реальных звонков.
 *
 * Примеры:
 *   node scripts/simulate-call.mjs --from 998901234567
 *   node scripts/simulate-call.mjs --from 998901234567 --url https://calls.example.com
 *   node scripts/simulate-call.mjs --from 998901234567 --event NOTIFY_END --duration 20
 *
 * Сценарий проверки всех трёх веток ТЗ:
 *   1) первый вызов            -> ожидаем ivr_play/hangup (автоответ)
 *   2) сразу повторный вызов   -> ожидаем {} (игнор)
 *   3) через 40+ минут         -> ожидаем redirect (соединение с владельцем)
 *      (ускорить: DELETE /admin/callers/<номер> или PUT /admin/config {"cooldownMinutes":0})
 */

import { loadDotEnv } from '../src/lib/dotenv.js';
import { buildNotifySignatureString, encodeSignature } from '../src/lib/signature.js';
import { WEBHOOK_PATH } from '../src/http/app.js';

loadDotEnv();

const args = parseArgs(process.argv.slice(2));
const secret = process.env.ZADARMA_API_SECRET;

if (!secret) {
  console.error('Ошибка: не задан ZADARMA_API_SECRET — подпись сформировать нечем.');
  process.exit(1);
}

const payload = {
  event: args.event,
  caller_id: args.from,
  called_did: args.to,
  call_start: formatCallStart(new Date()),
  pbx_call_id: args.callId,
};

if (args.event === 'NOTIFY_END') {
  payload.duration = String(args.duration);
  payload.disposition = args.disposition;
  payload.is_recorded = '0';
}

const signature = encodeSignature(buildNotifySignatureString(payload), secret);
const url = `${args.url.replace(/\/$/, '')}${WEBHOOK_PATH}`;

console.log(`-> POST ${url}`);
console.log(`   ${JSON.stringify(payload)}`);

const response = await fetch(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded', Signature: signature },
  body: new URLSearchParams(payload).toString(),
});

const text = await response.text();
console.log(`<- HTTP ${response.status}`);
console.log(`   ${text}`);

console.log(`\n${explain(text)}`);

function explain(rawBody) {
  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return 'Ответ не является JSON.';
  }

  if (body.redirect) return `Решение: СОЕДИНИТЬ с ${body.redirect} (новое обращение).`;
  if (body.ivr_play) return 'Решение: АВТООТВЕТ голосовым сообщением, звонок не соединяется.';
  if (body.hangup) return 'Решение: автоответ без голосового файла (SMS) либо сброс повторного звонка.';
  if (Object.keys(body).length === 0) return 'Решение: НИЧЕГО НЕ ДЕЛАТЬ — звонок обрабатывается штатным сценарием линии.';
  return 'Решение: см. поля ответа выше.';
}

function formatCallStart(date) {
  const pad = (value) => String(value).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

function parseArgs(argv) {
  const result = {
    url: process.env.PUBLIC_BASE_URL ?? `http://127.0.0.1:${process.env.PORT ?? 3000}`,
    from: '998901234567',
    to: process.env.ZADARMA_VIRTUAL_NUMBER ?? '998712000000',
    event: 'NOTIFY_START',
    callId: `sim_${Date.now()}`,
    duration: 15,
    disposition: 'answered',
  };

  for (let index = 0; index < argv.length; index += 1) {
    const [flag, inlineValue] = argv[index].split('=');
    const value = inlineValue ?? argv[index + 1];
    const consume = () => {
      if (inlineValue === undefined) index += 1;
      return value;
    };

    switch (flag) {
      case '--url':
        result.url = consume();
        break;
      case '--from':
        result.from = consume();
        break;
      case '--to':
        result.to = consume();
        break;
      case '--event':
        result.event = consume();
        break;
      case '--call-id':
        result.callId = consume();
        break;
      case '--duration':
        result.duration = Number(consume());
        break;
      case '--disposition':
        result.disposition = consume();
        break;
      case '--help':
      case '-h':
        console.log(
          'Использование: node scripts/simulate-call.mjs [--url http://localhost:3000] [--from 998901234567] ' +
            '[--to 998712000000] [--event NOTIFY_START|NOTIFY_END] [--call-id ID]',
        );
        process.exit(0);
        break;
      default:
        break;
    }
  }

  return result;
}
