import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';

import {
  buildAuthorizationHeader,
  buildNotifySignatureString,
  buildQueryString,
  encodeSignature,
  signaturesMatch,
  verifyWebhookSignature,
} from '../src/lib/signature.js';

const SECRET = 'test-secret-key';

test('строка подписи NOTIFY_START — caller_id + called_did + call_start', () => {
  const payload = {
    event: 'NOTIFY_START',
    caller_id: '998901234567',
    called_did: '998712000000',
    call_start: '2026-01-15 12:00:00',
    pbx_call_id: 'in_abc123',
  };

  assert.equal(buildNotifySignatureString(payload), '998901234567998712000000' + '2026-01-15 12:00:00');
});

test('у NOTIFY_ANSWER вместо called_did используется destination', () => {
  const payload = {
    event: 'NOTIFY_ANSWER',
    caller_id: '998901234567',
    destination: '100',
    call_start: '2026-01-15 12:00:00',
  };

  assert.equal(buildNotifySignatureString(payload), '998901234567100' + '2026-01-15 12:00:00');
});

test('подпись совпадает с формулой base64(hmac-sha1(...))', () => {
  const data = 'проверочная строка';
  const expected = createHmac('sha1', SECRET).update(data, 'utf8').digest('base64');

  assert.equal(encodeSignature(data, SECRET), expected);
});

test('корректная подпись вебхука принимается, изменённая — нет', () => {
  const payload = {
    event: 'NOTIFY_START',
    caller_id: '998901234567',
    called_did: '998712000000',
    call_start: '2026-01-15 12:00:00',
  };

  const signature = encodeSignature(buildNotifySignatureString(payload), SECRET);

  assert.equal(verifyWebhookSignature({ payload, signature, secret: SECRET }), true);
  assert.equal(verifyWebhookSignature({ payload, signature, secret: 'другой-секрет' }), false);
  assert.equal(
    verifyWebhookSignature({ payload: { ...payload, caller_id: '998900000000' }, signature, secret: SECRET }),
    false,
  );
  assert.equal(verifyWebhookSignature({ payload, signature: undefined, secret: SECRET }), false);
  assert.equal(verifyWebhookSignature({ payload, signature, secret: '' }), false);
});

test('сравнение подписей устойчиво к разной длине', () => {
  assert.equal(signaturesMatch('abc', 'abcdef'), false);
  assert.equal(signaturesMatch('', 'abc'), false);
  assert.equal(signaturesMatch(null, 'abc'), false);
  assert.equal(signaturesMatch('abc', 'abc'), true);
});

test('параметры запроса сортируются, пробел кодируется как +', () => {
  const query = buildQueryString({ message: 'привет мир', number: '+998901234567', caller: 'A B' });

  assert.equal(query, 'caller=A+B&message=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82+%D0%BC%D0%B8%D1%80&number=%2B998901234567');
});

test('массивы кодируются в PHP-стиле', () => {
  assert.equal(buildQueryString({ number: ['998901234567', '998901234568'] }), 'number%5B0%5D=998901234567&number%5B1%5D=998901234568');
});

test('заголовок Authorization строится по формуле method + query + md5(query)', () => {
  const method = '/v1/sms/send/';
  const params = { message: 'test', number: '998901234567' };

  const { header, queryString } = buildAuthorizationHeader({
    method,
    params,
    apiKey: 'my-key',
    apiSecret: SECRET,
  });

  const md5 = createHash('md5').update(queryString, 'utf8').digest('hex');
  const expectedSign = createHmac('sha1', SECRET).update(`${method}${queryString}${md5}`, 'utf8').digest('base64');

  assert.equal(header, `my-key:${expectedSign}`);
  assert.equal(queryString, 'message=test&number=998901234567');
});
