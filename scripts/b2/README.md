# B2: проверка аккаунтов и личных данных

Runner запускает уже собранный JAR на своей PostgreSQL 17.11 и проверяет все **11 операций B2** по неизменённому `api/openapi.yaml`. Затем выполняет [коллекцию Bruno](../../tools/bruno-b2/README.md). Это дополнение к Maven/Testcontainers и [B1 runner](../b1/README.md), который продолжает проверять публичный каталог и запускать Schemathesis.

## Запуск

Нужны Java 21, Docker с Linux containers, Python 3.12+ и Node 24. Используется то же закреплённое окружение Python и Bruno CLI, что и в B1; второй набор зависимостей не создаётся.

Из корня репозитория в Linux/WSL:

```bash
python3 -m venv scripts/b1/.venv
scripts/b1/.venv/bin/python -m pip install -r scripts/b1/requirements.txt
scripts/b1/.venv/bin/python -m pip check
npm ci --prefix tools/bruno --ignore-scripts --no-audit --no-fund

cd backend
sh ./mvnw --batch-mode --no-transfer-progress verify
../scripts/b1/.venv/bin/python src/test/resources/validate_http_contract.py \
  --schema ../api/openapi.yaml --responses target/b2-contract
cd ..

scripts/b1/.venv/bin/python scripts/b2/run_contract_checks.py \
  --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar
```

`--node /absolute/path/to/node` и `--bruno-cli /absolute/path/to/bru.js` позволяют использовать внешнюю локальную установку. Проверяется Bruno **4.2.1**; CI закрепляет Node **24.19.0**. Python dependencies берутся из существующего `scripts/b1/requirements.txt`. На текущей Windows-машине эти команды выполняются внутри WSL Ubuntu пользователем с доступом к Docker, каталог проекта — `/mnt/c/Daniyar/MyProjects/HackAtlas`. Git-команды от WSL root не требуются.

## Сценарии и смысл инструментов

- **Maven/Testcontainers** проверяют PostgreSQL-миграции, хранение хешей, истечение сессий с управляемыми часами, закрытую регистрацию и rate limits. Экспорт реальных HTTP-ответов из `target/b2-contract/` проверяется JSON Schema отдельно, включая ошибки 403/429.
- **Python runner** проверяет собранное приложение через HTTP: регистрацию двух аккаунтов, вход, отдельный logout каждой сессии, `Cache-Control: no-store`, отсутствие cookie-сессии, отзыв токена, PATCH с отсутствующим полем и `null`, запрет изменения email, личные списки и счётчики. Проверяется, что пробелы в пароле значимы. Для каждого ответа проверяются статус, media type и схема с форматами.
- **Guest import** представлен серией повторных PUT и неизвестным ID между успешными запросами: неудачный элемент не стирает сохранённые, у второго аккаунта остаётся свой пустой список. Повтор application PUT не меняет `markedAt`, удаление закладки не удаляет отметку заявки.
- **Bruno** повторяет пользовательскую последовательность из 26 запросов в читаемых `.bru` файлах, которые напарник может открыть для M3. Каждый запрос содержит assertion; токены существуют только как runtime variables.
- **Schemathesis** остаётся проверкой трёх публичных B1 операций. B2 здесь проверяется явными переходами состояния: генератор независимых запросов не заменяет сценарий вход → личные данные → выход. Для B2 не заявляется генеративный fuzzing или полный security audit. Проверки B1 не отключены и не ослаблены.

## Изоляция и секреты

Runner не принимает адрес внешнего сервера. Он создаёт отдельную PostgreSQL со случайным именем `hackatlas-b2-…`, меткой `hackatlas.b2-run`, loopback-портом, случайным паролем и `tmpfs` вместо постоянного volume. JAR копируется во временный Linux-каталог с проверкой неизменности исходного файла. Сервер запускается только на `127.0.0.1`, с dev-профилем, явным DEMO seed и `--hackatlas.auth.registration-enabled=true`. Общая dev-БД и локальный `infra/.env` не используются.

Пароли и синтетические адреса `@example.com` создаются на каждый запуск. Пароли Bruno передаются через environment дочернего процесса, токены извлекаются из реального ответа. Они не передаются аргументами CLI и не записываются в `.env` или отчёты. Коллекция запускается из временной копии без пользовательского `.env`; stdout дополнительно редактирует распознаваемые секреты. Schema error не печатает содержимое ответа. HTTP redirects и proxy выключены.

При завершении и обычном SIGINT/SIGTERM останавливается только созданная JVM, а контейнер удаляется только после проверки его метки. При SIGKILL/сбое хоста автоматическая очистка невозможна: проверяйте конкретное имя и `hackatlas.b2-run`, не удаляйте контейнеры по общей маске. Временная БД и аккаунты удаляются; реальные пользовательские данные не затрагиваются.

Ненулевой exit означает провал проверки. CI запускает B2 после Maven `verify`, JSON Schema validation и B1 runner. Логи при локальном запуске можно сохранять только в игнорируемый `backend/target/b2-checks/`; не включайте HTTP trace с Authorization или JSON auth responses.

Источники синтаксиса Bruno: [process environment](https://docs.usebruno.com/variables/process-env), [runtime variables](https://docs.usebruno.com/variables/runtime-variables), [CLI](https://docs.usebruno.com/bru-cli/overview). Pins и исходные ссылки инструментов сохранены в [B1 README](../b1/README.md#pins-и-источники).
