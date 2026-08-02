# 4. Развёртывание

Требование Zadarma — постоянно работающий сервер с публичным **HTTPS**-адресом.
Ниже три варианта: два быстрых для старта и один для продакшена.

Общее для всех вариантов: **нужен постоянный диск** для файла SQLite.
Если файл лежит в эфемерной файловой системе, при каждом деплое история звонков
обнуляется, и все абоненты снова становятся «звонящими впервые».

---

## Вариант А. Railway (быстрее всего)

1. Залейте репозиторий на GitHub.
2. [railway.app](https://railway.app) → **New Project → Deploy from GitHub repo**.
3. **Variables** — задайте переменные из `.env.example`, минимум:
   `OWNER_PHONE`, `ZADARMA_API_KEY`, `ZADARMA_API_SECRET`, `AUTOREPLY_VOICE_FILE_ID`,
   `ADMIN_TOKEN`, `NODE_ENV=production`.
4. **Volume**: подключите том и смонтируйте его в `/data`, затем задайте
   `DATABASE_PATH=/data/calls.db`.
5. **Settings → Networking → Generate Domain** — получите адрес вида
   `https://ваш-сервис.up.railway.app`.
6. Проверьте: `curl https://ваш-сервис.up.railway.app/health`.

Railway сам определит Node.js и запустит `npm start`. Сборка мгновенная: зависимостей нет.

## Вариант Б. Render

В репозитории есть готовый `render.yaml` (Blueprint).

1. [render.com](https://render.com) → **New → Blueprint** → выберите репозиторий.
2. Render создаст web-сервис с диском на 1 ГБ, смонтированным в `.../data`.
3. Заполните переменные, помеченные `sync: false` (ключи, номер владельца).
   `ADMIN_TOKEN` Render сгенерирует сам.
4. Health check уже настроен на `/health`.

> На бесплатном плане Render сервис засыпает при простое, и первый звонок после
> паузы может обработаться с задержкой в несколько секунд — Zadarma к этому моменту
> уже уйдёт в штатный сценарий. Для боевой работы нужен платный план или VPS.

## Вариант В. VPS (продакшен)

Минимальной конфигурации (1 vCPU, 1 ГБ RAM) более чем достаточно.

### 1. Node.js 22

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
node --version   # должно быть v22.5 или новее — нужен встроенный модуль node:sqlite
```

### 2. Код и конфигурация

```bash
sudo useradd -r -m -d /opt/call-router -s /bin/bash callrouter
sudo -u callrouter git clone <репозиторий> /opt/call-router/app
cd /opt/call-router/app
sudo -u callrouter cp .env.example .env
sudo -u callrouter nano .env          # заполните значения
sudo -u callrouter mkdir -p /opt/call-router/app/data
```

### 3. Автозапуск через systemd

`/etc/systemd/system/call-router.service`:

```ini
[Unit]
Description=Zadarma call router
After=network.target

[Service]
Type=simple
User=callrouter
WorkingDirectory=/opt/call-router/app
ExecStart=/usr/bin/node src/server.js
Restart=always
RestartSec=5
Environment=NODE_ENV=production
# Логи уходят в journald: journalctl -u call-router -f
StandardOutput=journal
StandardError=journal
# Базовая изоляция
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=/opt/call-router/app/data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now call-router
sudo systemctl status call-router
```

### 4. Nginx и HTTPS

`/etc/nginx/sites-available/call-router`:

```nginx
server {
    listen 80;
    server_name calls.example.com;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Zadarma ждёт ответ считаные секунды — таймауты держим короткими.
        proxy_connect_timeout 5s;
        proxy_read_timeout 10s;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/call-router /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d calls.example.com    # бесплатный сертификат Let's Encrypt
```

### 5. Обновление версии

```bash
cd /opt/call-router/app
sudo -u callrouter git pull
sudo systemctl restart call-router
```

Простой при перезапуске — доли секунды. Если звонок придёт именно в этот момент,
Zadarma не получит ответа и обработает вызов штатным сценарием линии — звонок не потеряется.

## Вариант Г. Docker

```bash
docker build -t call-router .
docker run -d --name call-router \
  --env-file .env \
  -p 3000:3000 \
  -v call-data:/app/data \
  -e DATABASE_PATH=/app/data/calls.db \
  --restart unless-stopped \
  call-router
```

---

## Бэкап базы

Файл SQLite копируется как обычный файл, останавливать сервис не нужно
(включён режим WAL):

```bash
sqlite3 /opt/call-router/app/data/calls.db ".backup '/var/backups/calls-$(date +%F).db'"
```

Ежедневный бэкап через cron:

```cron
0 3 * * * sqlite3 /opt/call-router/app/data/calls.db ".backup '/var/backups/calls-$(date +\%F).db'" && find /var/backups -name 'calls-*.db' -mtime +30 -delete
```

## Мониторинг

- **Проверка живости:** `GET /health` — отдаёт текущее окно тишины и режим автоответа.
  Подключите внешний пингер (UptimeRobot и т. п.): если сервис лежит, звонки молча
  идут по штатному сценарию, и заметить это без мониторинга сложно.
- **Логи:** одна строка JSON на событие. `journalctl -u call-router -f`
  или логи платформы. Ключевые сообщения: `Обработан входящий звонок`,
  `SMS-автоответ отправлен`, `Отклонён вебхук с неверной подписью`.
- **Баланс Zadarma.** Кончился баланс — не уходят ни SMS, ни переадресация на владельца.
  Включите в кабинете уведомление о низком балансе.

## Чек-лист перед боевым запуском

- [ ] `NODE_ENV=production`, `ZADARMA_VERIFY_SIGNATURE=true`
- [ ] `ADMIN_TOKEN` задан длинной случайной строкой (`openssl rand -hex 32`)
- [ ] `DATABASE_PATH` указывает на постоянный диск
- [ ] `/health` отвечает по HTTPS снаружи
- [ ] адрес вебхука сохранён в кабинете Zadarma, включён `notify_start`
- [ ] переадресация с SIM включена и проверена (`*#004#`)
- [ ] пройдены все три сценария из [docs/01, шаг 7](01-zadarma-setup.md)
- [ ] настроен бэкап базы
- [ ] на балансе Zadarma достаточно средств для SMS и переадресации
