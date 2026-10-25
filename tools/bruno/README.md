# Bruno: публичный каталог B1

Коллекция содержит десять GET-запросов: каталог `NEWEST`, поиск с форматом, пустой результат, details, metadata, ошибочные size/sort/date range, отсутствующий UUID и неверный UUID. Assertions рассчитаны на пять канонических DEMO-событий и фиксированные часы `2026-10-12T10:00:00Z`.

`environments/local.bru` содержит только `baseUrl=http://127.0.0.1:8080/api/v1`. Секретов, Bearer-токенов и операций B2/B3 здесь нет. Идентификаторы берутся из общего Starter DEMO.

## Воспроизводимый запуск

Предпочтительный полный прогон — [изолированный B1 runner](../../scripts/b1/README.md): он поднимает собственную PostgreSQL/Java-среду и запускает эту коллекцию вместе со schema checks и Schemathesis.

Для уже запущенного локального DEMO-сервера:

```bash
cd tools/bruno
npm ci --ignore-scripts --no-audit --no-fund
npm test
```

Другой локальный порт:

```bash
node node_modules/@usebruno/cli/bin/bru.js run --env local --noproxy \
  --env-var baseUrl=http://127.0.0.1:8081/api/v1
```

CLI **4.2.1** закреплён в `package.json` и `package-lock.json`; Node.js **24.19.0** закреплён в CI. Используется стандартная safe sandbox. GUI и глобальная установка не нужны. При открытии коллекции в GUI выбирайте `local`; ручной просмотр сам по себе не подтверждает успешный CLI-прогон.

Не сохраняйте отчёты рядом с коллекцией: при необходимости используйте игнорируемый `backend/target/b1-checks/`. Не добавляйте реальные аккаунты или секреты в environment. Metadata намеренно ожидает только два формата, реально представленных в DEMO-каталоге; полный enum в общей fixture не обязывает возвращать отсутствующий `HYBRID`.

Источники версий и синтаксиса: [официальный npm registry](https://registry.npmjs.org/@usebruno%2fcli), [Bruno CLI](https://docs.usebruno.com/bru-cli/overview), [scripts/tests API](https://docs.usebruno.com/testing/script/overview), [upstream releases](https://github.com/usebruno/bruno/releases). Для npm используется CLI из открытого репозитория; платные функции GUI в эту проверку не входят.
