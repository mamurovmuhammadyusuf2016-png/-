/**
 * Точка входа: сборка зависимостей, запуск HTTP-сервера, корректное завершение.
 */

import { createServer } from 'node:http';

import { loadConfig } from './config.js';
import { createLogger } from './lib/logger.js';
import { createStore } from './store/index.js';
import { ZadarmaClient } from './services/zadarmaClient.js';
import { EventForwarder } from './services/forwarder.js';
import { createApp, WEBHOOK_PATH } from './http/app.js';
import { getCooldown } from './services/settings.js';
import { loadDotEnv } from './lib/dotenv.js';

/** Периодичность чистки журнала звонков. */
const PURGE_INTERVAL_MS = 6 * 60 * 60 * 1000;

export async function buildApplication(env = process.env) {
  const config = loadConfig(env);
  const logger = createLogger({ level: config.logLevel });
  const store = await createStore(config);

  const zadarma = new ZadarmaClient({
    apiKey: config.zadarma.apiKey,
    apiSecret: config.zadarma.apiSecret,
    baseUrl: config.zadarma.apiBaseUrl,
    timeoutMs: config.zadarma.requestTimeoutMs,
  });

  const forwarder = new EventForwarder({
    url: config.forwardWebhookUrl,
    timeoutMs: config.forwardWebhookTimeoutMs,
    logger,
  });

  const app = createApp({ config, store, zadarma, forwarder, logger });

  return { config, logger, store, zadarma, forwarder, app };
}

async function main() {
  loadDotEnv();

  const { config, logger, store, app } = await buildApplication();
  const server = createServer(app);

  server.listen(config.port, config.host, () => {
    const cooldown = getCooldown(store, config);
    logger.info('Сервис запущен', {
      host: config.host,
      port: config.port,
      webhookPath: WEBHOOK_PATH,
      cooldownMinutes: cooldown.minutes,
      autoreplyMode: config.autoreplyMode,
      autoreplyLanguage: config.autoreplyLanguage,
      ownerPhone: config.ownerPhone,
      storeDriver: config.storeDriver,
      signatureVerification: config.zadarma.verifySignature,
    });
  });

  const purgeTimer = setInterval(() => {
    try {
      const removed = store.purgeOldCalls(config.callLogRetentionDays);
      if (removed > 0) logger.info('Журнал звонков очищен', { removed });
    } catch (error) {
      logger.error('Ошибка очистки журнала звонков', { error });
    }
  }, PURGE_INTERVAL_MS);
  purgeTimer.unref();

  const shutdown = (signal) => {
    logger.info('Остановка сервиса', { signal });
    clearInterval(purgeTimer);
    server.close(() => {
      try {
        store.close();
      } catch (error) {
        logger.error('Ошибка при закрытии хранилища', { error });
      }
      process.exit(0);
    });

    // Страховка на случай зависших соединений.
    setTimeout(() => process.exit(1), 10_000).unref();
  };

  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('unhandledRejection', (error) => logger.error('Необработанное отклонение промиса', { error }));
  process.on('uncaughtException', (error) => {
    logger.error('Необработанное исключение', { error });
    shutdown('uncaughtException');
  });
}

const isEntryPoint = process.argv[1] && import.meta.url === `file://${process.argv[1]}`;
if (isEntryPoint) {
  main().catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exit(1);
  });
}
