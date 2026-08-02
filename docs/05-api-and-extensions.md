# 5. Служебный API и расширение системы

---

## Эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/health` | Состояние сервиса и действующие настройки |
| `GET` | `/zadarma/webhook?zd_echo=<код>` | Проверка адреса при сохранении в кабинете Zadarma |
| `POST` | `/zadarma/webhook` | Приём уведомлений о звонках (`notify_start`, `notify_end`) |
| `GET` | `/admin/config` | Текущие параметры + предпросмотр текста SMS |
| `PUT` | `/admin/config` | Изменение окна тишины на лету |
| `GET` | `/admin/stats` | Сводка: сколько номеров, какие решения, звонки за сутки |
| `GET` | `/admin/callers` | Список номеров с историей |
| `GET` | `/admin/callers/<номер>` | Карточка одного номера |
| `DELETE` | `/admin/callers/<номер>` | Сброс истории номера — он снова «звонит впервые» |
| `GET` | `/admin/calls` | Журнал звонков (фильтр `?phone=`, `?limit=`, `?offset=`) |

Все `/admin/*` требуют токен:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" https://calls.example.com/admin/stats
```

## Изменение таймаута без правки кода

Требование ТЗ реализовано на двух уровнях.

**1. Переменная окружения** — значение по умолчанию:

```env
CALL_COOLDOWN_MINUTES=40
```

**2. На лету, без перезапуска** — значение сохраняется в базе и перекрывает `.env`:

```bash
# поставить 25 минут
curl -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d '{"cooldownMinutes": 25}' https://calls.example.com/admin/config

# вернуться к значению из .env
curl -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d '{}' https://calls.example.com/admin/config
```

Ответ показывает, откуда взято действующее значение:

```json
{ "status": "ok", "cooldownMinutes": 25, "cooldownSource": "runtime" }
```

`runtime` — задано через API, `env` — из переменной окружения.

## Формат журнала

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "https://calls.example.com/admin/calls?limit=3"
```

```json
{
  "status": "ok",
  "calls": [
    {
      "pbxCallId": "in_ae6b03b3b0765d127ec0b739209346bb",
      "phone": "+998901234567",
      "calledDid": "998712000000",
      "decision": "connect",
      "reason": "С прошлого звонка прошло 47.2 мин (>= 40) — новое обращение.",
      "minutesSincePreviousCall": 47.2,
      "smsStatus": null,
      "duration": 63,
      "disposition": "answered",
      "createdAt": "2026-01-15T10:47:12.000Z",
      "endedAt": "2026-01-15T10:48:15.000Z"
    }
  ]
}
```

Значения `decision`:

| Значение | Что произошло |
|---|---|
| `autoreply` | Первый звонок — отправлен автоответ, соединения не было |
| `connect` | Новое обращение — звонок переведён на владельца |
| `ignore` | Повтор внутри окна тишины — ничего не делали |
| `unidentified` | Номер скрыт, историю вести не по чему |

## Интеграция с Google Sheets

Самый простой путь без сторонних сервисов — Google Apps Script.

**1. В таблице:** «Расширения» → «Apps Script», вставьте:

```javascript
function doPost(e) {
  const data = JSON.parse(e.postData.contents);
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Звонки');

  sheet.appendRow([
    new Date(data.occurredAt),
    data.phone,
    data.decision,
    data.isFirstCall ? 'первый' : 'повторный',
    data.minutesSincePreviousCall,
    data.action,
    data.smsStatus,
  ]);

  return ContentService.createTextOutput(JSON.stringify({ ok: true }))
    .setMimeType(ContentService.MimeType.JSON);
}
```

**2.** «Развернуть» → «Новое развёртывание» → тип «Веб-приложение» →
доступ «Все» → скопируйте URL.

**3.** В `.env`:

```env
FORWARD_WEBHOOK_URL=https://script.google.com/macros/s/AKfy.../exec
```

Каждое решение по звонку будет уходить в таблицу. Отправка асинхронная:
недоступность таблицы никак не влияет на обработку звонка (ошибка попадёт в лог).

### Формат отправляемого события

```json
{
  "type": "call_decision",
  "occurredAt": "2026-01-15T10:47:12.000Z",
  "pbxCallId": "in_ae6b03b3...",
  "phone": "+998901234567",
  "calledDid": "998712000000",
  "callStart": "2026-01-15 10:47:10",
  "decision": "connect",
  "isFirstCall": false,
  "minutesSincePreviousCall": 47.2,
  "cooldownMinutes": 40,
  "action": "Соединение с владельцем (998901112233)",
  "smsStatus": null
}
```

Событие `type: "call_end"` приходит после завершения разговора и содержит
`duration` и `disposition`.

## Интеграция с CRM

Тот же `FORWARD_WEBHOOK_URL`, только адрес — приёмник CRM (amoCRM, Bitrix24,
Make/Zapier/n8n). Если нужна авторизация или другой формат тела, правится один
файл — `src/services/forwarder.js`, метод `send`.

Zadarma также имеет собственную CRM (Teamsale) и готовые интеграции — если она уже
используется, звонки попадут туда автоматически, отдельная настройка не нужна.

## Замена хранилища (Redis, Postgres)

Хранилище изолировано за одним интерфейсом. Чтобы добавить драйвер:

1. Создайте `src/store/redisStore.js` с теми же методами, что у `MemoryStore`:

```
resolveCall({ pbxCallId, phone, rawCallerId, calledDid, callStart, now, cooldownMs })
recordResponse({ pbxCallId, response, smsStatus })
recordCallEnd({ pbxCallId, duration, disposition, isRecorded, endedAt })
getCaller(phone) / listCallers({ limit, offset }) / deleteCaller(phone)
listCalls({ limit, offset, phone }) / getStats() / purgeOldCalls(days)
getSetting(key) / setSetting(key, value) / deleteSetting(key)
close()
```

2. Добавьте ветку в `switch` в `src/store/index.js`.
3. Прогоните `tests/store.test.js` — он параметризован по драйверам,
   достаточно добавить новый в массив `drivers`.

Ключевое требование к `resolveCall`: чтение прошлой метки времени и её обновление
должны быть **атомарными**. В SQLite это транзакция `BEGIN IMMEDIATE`, в Redis —
скрипт на Lua или `WATCH/MULTI/EXEC`. Иначе два одновременных звонка с одного номера
могут оба получить решение «соединить».

## Смена провайдера телефонии

Логика решения (`src/domain/decision.js`) не зависит от Zadarma. Для перехода
на Voximplant или другого провайдера меняются три файла:

- `src/lib/signature.js` — схема проверки подписи вебхука;
- `src/services/responsePlan.js` — формат ответа (у каждого провайдера свой);
- `src/services/zadarmaClient.js` — вызовы API для отправки SMS.

Сама таблица «номер → время последнего звонка» и правило 40 минут остаются как есть.
