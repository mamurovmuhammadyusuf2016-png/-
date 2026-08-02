/**
 * Фабрика хранилища. Выбор драйвера — через STORE_DRIVER.
 *
 * Добавление нового драйвера (Redis, Postgres) сводится к реализации
 * тех же методов и одной ветке в этом switch.
 */

import { MemoryStore } from './memoryStore.js';

/**
 * @param {object} config результат loadConfig()
 * @returns {Promise<import('./sqliteStore.js').SqliteStore | MemoryStore>}
 */
export async function createStore(config) {
  switch (config.storeDriver) {
    case 'memory':
      return new MemoryStore();

    case 'sqlite': {
      try {
        const { SqliteStore } = await import('./sqliteStore.js');
        return new SqliteStore({ path: config.databasePath });
      } catch (error) {
        if (error?.code === 'ERR_UNKNOWN_BUILTIN_MODULE' || /node:sqlite/.test(error?.message ?? '')) {
          throw new Error(
            'Модуль node:sqlite недоступен. Требуется Node.js >= 22.5 ' +
              '(проверьте `node --version`) либо укажите STORE_DRIVER=memory для отладки.',
            { cause: error },
          );
        }
        throw error;
      }
    }

    default:
      throw new Error(`Неизвестный STORE_DRIVER: ${config.storeDriver}`);
  }
}
