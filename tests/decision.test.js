import test from 'node:test';
import assert from 'node:assert/strict';

import { Decision, decideCallAction, minutesToMs, unidentifiedCallDecision } from '../src/domain/decision.js';

const NOW = Date.UTC(2026, 0, 15, 12, 0, 0);
const COOLDOWN_MS = minutesToMs(40);

test('первый звонок — автоответ и сохранение времени', () => {
  const result = decideCallAction({ lastCallAt: null, now: NOW, cooldownMs: COOLDOWN_MS });

  assert.equal(result.decision, Decision.AUTOREPLY);
  assert.equal(result.updateLastCallAt, true);
  assert.equal(result.isFirstCall, true);
  assert.equal(result.elapsedMs, null);
});

test('undefined трактуется как отсутствие записи', () => {
  const result = decideCallAction({ lastCallAt: undefined, now: NOW, cooldownMs: COOLDOWN_MS });
  assert.equal(result.decision, Decision.AUTOREPLY);
});

test('ровно 40 минут — уже новое обращение, соединяем', () => {
  const result = decideCallAction({ lastCallAt: NOW - COOLDOWN_MS, now: NOW, cooldownMs: COOLDOWN_MS });

  assert.equal(result.decision, Decision.CONNECT);
  assert.equal(result.updateLastCallAt, true);
  assert.equal(result.elapsedMinutes, 40);
});

test('больше 40 минут — соединяем', () => {
  const result = decideCallAction({ lastCallAt: NOW - minutesToMs(90), now: NOW, cooldownMs: COOLDOWN_MS });

  assert.equal(result.decision, Decision.CONNECT);
  assert.equal(result.elapsedMinutes, 90);
});

test('на секунду раньше 40 минут — игнорируем', () => {
  const result = decideCallAction({ lastCallAt: NOW - COOLDOWN_MS + 1000, now: NOW, cooldownMs: COOLDOWN_MS });

  assert.equal(result.decision, Decision.IGNORE);
  assert.equal(result.updateLastCallAt, false);
});

test('игнорирование не сдвигает окно тишины', () => {
  // Абонент звонит каждые 5 минут: окно всё равно должно истечь через 40 минут
  // после последнего «засчитанного» звонка, иначе до владельца не дозвониться.
  const firstCallAt = NOW;
  let lastCallAt = firstCallAt;

  for (let minute = 5; minute <= 35; minute += 5) {
    const result = decideCallAction({ lastCallAt, now: firstCallAt + minutesToMs(minute), cooldownMs: COOLDOWN_MS });
    assert.equal(result.decision, Decision.IGNORE);
    if (result.updateLastCallAt) lastCallAt = firstCallAt + minutesToMs(minute);
  }

  const finalCall = decideCallAction({ lastCallAt, now: firstCallAt + minutesToMs(40), cooldownMs: COOLDOWN_MS });
  assert.equal(finalCall.decision, Decision.CONNECT);
});

test('нулевое окно — каждый повторный звонок соединяется', () => {
  const result = decideCallAction({ lastCallAt: NOW - 1, now: NOW, cooldownMs: 0 });
  assert.equal(result.decision, Decision.CONNECT);
});

test('метка времени из будущего (расхождение часов) не соединяет звонок', () => {
  const result = decideCallAction({ lastCallAt: NOW + minutesToMs(10), now: NOW, cooldownMs: COOLDOWN_MS });
  assert.equal(result.decision, Decision.IGNORE);
});

test('окно можно изменить конфигурацией без правки логики', () => {
  const cooldown15 = minutesToMs(15);
  const result = decideCallAction({ lastCallAt: NOW - minutesToMs(20), now: NOW, cooldownMs: cooldown15 });
  assert.equal(result.decision, Decision.CONNECT);
});

test('скрытый номер — отдельное решение без записи в историю', () => {
  const result = unidentifiedCallDecision();
  assert.equal(result.decision, Decision.UNIDENTIFIED);
  assert.equal(result.updateLastCallAt, false);
});

test('некорректные аргументы отклоняются', () => {
  assert.throws(() => decideCallAction({ lastCallAt: null, now: 'сейчас', cooldownMs: 1 }), TypeError);
  assert.throws(() => decideCallAction({ lastCallAt: null, now: NOW, cooldownMs: -1 }), TypeError);
  assert.throws(() => decideCallAction({ lastCallAt: 'вчера', now: NOW, cooldownMs: 1 }), TypeError);
});
