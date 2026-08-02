/**
 * Настройки, которые можно менять во время работы сервиса.
 *
 * Требование ТЗ: окно тишины (сейчас 40 минут) должно меняться без правки кода.
 * Реализовано двумя уровнями:
 *   1. переменная окружения CALL_COOLDOWN_MINUTES — значение по умолчанию;
 *   2. запись в таблице settings — перекрывает переменную окружения и
 *      применяется сразу, без перезапуска (эндпоинт PUT /admin/config).
 */

export const SETTING_COOLDOWN_MINUTES = 'cooldown_minutes';

/**
 * Действующее окно тишины в минутах.
 *
 * @param {object} store
 * @param {object} config
 * @returns {{ minutes: number, source: 'runtime' | 'env' }}
 */
export function getCooldown(store, config) {
  const stored = store.getSetting(SETTING_COOLDOWN_MINUTES);
  if (stored !== null) {
    const parsed = Number(stored);
    if (Number.isFinite(parsed) && parsed >= 0) {
      return { minutes: parsed, source: 'runtime' };
    }
  }
  return { minutes: config.cooldownMinutes, source: 'env' };
}

/**
 * Меняет окно тишины на лету.
 *
 * @param {object} store
 * @param {number} minutes
 */
export function setCooldownMinutes(store, minutes) {
  const value = Number(minutes);
  if (!Number.isFinite(value) || value < 0 || value > 60 * 24 * 30) {
    throw new RangeError('cooldownMinutes: ожидается число от 0 до 43200');
  }
  store.setSetting(SETTING_COOLDOWN_MINUTES, value);
  return value;
}

/** Возвращает значение из переменной окружения. */
export function resetCooldownMinutes(store) {
  store.deleteSetting(SETTING_COOLDOWN_MINUTES);
}
