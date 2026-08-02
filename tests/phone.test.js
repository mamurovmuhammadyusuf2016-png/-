import test from 'node:test';
import assert from 'node:assert/strict';

import { isAnonymousCaller, maskPhone, normalizePhone, toE164 } from '../src/lib/phone.js';

test('разные записи одного номера дают один ключ', () => {
  const expected = '998901234567';

  for (const variant of ['+998901234567', '998901234567', '00998901234567', '+998 (90) 123-45-67', ' 998-90-123-45-67 ']) {
    assert.equal(normalizePhone(variant), expected, `вариант: ${variant}`);
  }
});

test('скрытые и пустые номера не нормализуются', () => {
  for (const variant of ['', '   ', 'anonymous', 'Unknown', 'restricted', null, undefined]) {
    assert.equal(normalizePhone(variant), null, `вариант: ${variant}`);
    assert.equal(isAnonymousCaller(variant), true);
  }
});

test('внутренние номера АТС не считаются абонентами', () => {
  assert.equal(normalizePhone('100'), null);
  assert.equal(normalizePhone('1234'), null);
});

test('слишком длинные значения обрезаются до 15 цифр', () => {
  assert.equal(normalizePhone('9989012345671234567').length, 15);
});

test('E.164 добавляет плюс', () => {
  assert.equal(toE164('998901234567'), '+998901234567');
  assert.equal(toE164(null), null);
});

test('маска скрывает середину номера', () => {
  assert.equal(maskPhone('+998901234567'), '99890****567');
  assert.equal(maskPhone('anonymous'), 'anonymous');
});
