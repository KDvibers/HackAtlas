# BACKEND: статус

Дата актуального дополнения: 20 октября 2026.

## B1 — публичный каталог завершён локально

**B1 выполнен, B2 не начат.** Ветка `feat/catalog-api` создана от B0 `208e097`; в начале задания checkout был чистым. Повторный `git fetch origin` подтвердил, что пользователь уже объединил B0: `origin/main=43b11e4` (merge PR #2), `208e097` входит в его историю. База PR B1 — **main**. Merge/rebase текущей ветки не выполнялся.

Все изменения B1 оставлены **без staging и коммита**. Commit, push и PR делает пользователь; в итоговом отчёте дать готовые команды. Read-only GitHub API повторно подтвердил `yergaliuly` и push-доступ к KDvibers/HackAtlas; Git author отдельно — `softache <itsyergaliuly@gmail.com>`. Никакого push/PR/merge/deployment в этом задании не выполнялось.

### Реализовано

- `backend/src/main/java/com/hackatlas/catalog/`: сущности, DTO/mapping, параметризованные запросы каталога, AND-фильтры, стабильная сортировка, BigInteger pagination, metadata, статусы от Clock и идемпотентный явный DEMO seed.
- `backend/src/main/java/com/hackatlas/web/`, `config/ClockConfiguration.java`: три публичных GET, строгая валидация, безопасный Problem/requestId; фиксированный DEMO Clock разрешён только в dev/test с флагом.
- `backend/src/main/resources/db/migration/V2__create_catalog.sql`: таблицы каталога/тегов/связей, ограничения и индексы, без production seed.
- `backend/pom.xml`: канонические fixtures как classpath resources; новых Java dependencies нет.
- `backend/src/test/`: реальные PostgreSQL/Testcontainers HTTP-тесты, prod DEMO gate, экспорт ответов и проверка JSON Schema 2020-12.
- `tools/bruno/`: 10 запросов, CLI 4.2.1 с package-lock. `scripts/b1/`: pinned Schemathesis 4.32.2, изолированный runner собранного JAR, README/requirements. CI дополнен этими проверками.
- Документация: `backend/CATALOG.md`, README/START_HERE, backend/infra/toolchain, DECISIONS и правила пользовательских commit/push/PR. `api/`, fixtures, mobile, визуальный референс и авторизация не менялись.

### Выполненные проверки

| Команда / проверка | Результат |
|---|---|
| WSL root: `cd /mnt/c/Daniyar/MyProjects/HackAtlas/backend && sh ./mvnw --batch-mode --no-transfer-progress verify` | **BUILD SUCCESS, 91 tests, 0 failures/errors/skipped**, итоговый прогон после всех Java-исправлений |
| Windows: `.\.venv\Scripts\python.exe backend/src/test/resources/validate_http_contract.py --schema api/openapi.yaml --responses backend/target/catalog-contract-responses.json` | **PASS 139 реальных HTTP-ответов**, включая 3 GET/200, list/400/500, detail/400/404 |
| `.\.venv\Scripts\python.exe scripts/validate_contract.py` и `-m pip check` | PASS; неизменённый OpenAPI и все 6 fixtures |
| Изолированный runner на окончательном JAR | PASS offline validator, 12 реальных HTTP schema/semantic сценариев, **Bruno 10 requests / 10 tests** |
| Schemathesis 4.32.2 | **201 generated / 201 passed**, выбраны и проверены ровно 3/17 операций B1; seed `20261020`, max-examples 100. Fuzzing прошёл; Examples не имел request examples и пропустил три операции. Coverage/Stateful не входят в этот прогон |
| GitHub read-only API | Подтверждены `yergaliuly`, репозиторий KDvibers/HackAtlas и write-доступ; Git author проверен отдельно |
| Независимое review и regression checks | Исправлены порядок NEWEST seed, snapshot чтения, U+0000 в query/city/topic и ошибочный 500 при пустом имени query-параметра; итоговые Java tests покрывают исправления |

Логи: `backend/target/b1-verify.log`, `backend/target/failsafe-reports/`, `backend/target/catalog-contract-responses.json`, `backend/target/b1-checks/contract-checks.log`, `backend/target/b1-checks/final-checks.log`. Они игнорируются Git. Helper проверяет именно экспорт HTTP, требует обязательные пары operation/status и завершает работу ненулевым кодом при нарушении схемы/покрытия.

Первый Schemathesis запуск выявил неверные одинарные кавычки в селекторе; использован JSON literal `"B1"`, после чего выбраны ровно три маршрута. Реальный fuzz обнаружил, что Tomcat `InvalidParameterException` при пустом имени query-параметра попадал в общий 500. Добавлена безопасная обработка 400 `VALIDATION_ERROR` и пять HTTP regression cases для malformed/unknown names и повторов. Ошибки не исключались из генерации и checks не отключались ради прохождения.

### Окружение и ограничения

Docker/JVM проверки выполнены в Ubuntu WSL2 root с Java 21 и PostgreSQL 17.11. Bruno использовал Node 24.19.0, Schemathesis — отдельный Python 3.14 venv. CI настроен на Python 3.12; его dependency resolution проверен, но **удалённый GitHub Actions run ещё не выполнялся**. Полный Windows JVM + Docker прогон, Android/iOS и устройства не проверялись.

Runner копирует JAR в собственный Linux temporary directory, чтобы JVM не удерживала исходный файл на Windows mount. Его отдельная PostgreSQL использует случайное имя/owner label и tmpfs, credentials передаются через env. В итоговом прогоне cleanup успешно остановил JVM и удалил только собственный контейнер. Общая dev-БД, её volume и БД другого проекта не использовались и не удалялись.

API проверен по loopback внутри WSL. Отдельная попытка Windows PowerShell подключиться к активному серверу WSL по `127.0.0.1` получила connection refused; Windows localhost forwarding здесь не подтверждён и не настраивался. Для mobile потребуется отдельно проверить доступный host mapping/LAN либо staging. Внешнего staging URL нет, сервер после проверок остановлен.

Команды для нового окружения и locks — в [backend/README](../backend/README.md) и [scripts/b1/README](../scripts/b1/README.md). Фактически выполненный локальный runner из PowerShell:

   ```powershell
   wsl -d Ubuntu -u root --exec /tmp/hackatlas-b1-tools-20261020-326a/venv/bin/python /mnt/c/Daniyar/MyProjects/HackAtlas/scripts/b1/run_contract_checks.py --jar /mnt/c/Daniyar/MyProjects/HackAtlas/backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --node /tmp/hackatlas-b1-tools-20261020-326a/node-v24.19.0-linux-x64/bin/node
   ```

### Финальный scope и передача

`git diff --check` и проверка новых файлов/Markdown-ссылок проходят; staged diff пуст. Gitleaks CLI 8.30.1 проверяет доступную Git-историю и временную копию всех 58 изменённых/новых неигнорируемых файлов, без staging и без чтения реального `.env`: утечек не найдено. Проверку выполняет обычный WSL-пользователь; root Git не доверяет checkout. Повторяемый локальный скрипт проверки сохранён в игнорируемом `backend/target/b1-final-checks.py`. Это проверка распространённых шаблонов, не полный security audit.

Для напарника: «B1 готов локально в `feat/catalog-api`, PR будет в main. Доступны публичные GET `/api/v1/hackathons`, `/api/v1/hackathons/{id}`, `/api/v1/metadata/filters`; Starter не менялся. После пользовательского merge обнови main и используй [CATALOG](../backend/CATALOG.md). DEMO требует явного флага, фиксированная дата fixtures — `2026-10-12T10:00:00Z`; `example.com` не является реальной регистрацией. Базовый URL локального сервера в WSL — `http://127.0.0.1:8080/api/v1`; для твоего устройства/машины нужен отдельно проверенный доступный адрес. Auth ещё нет». Сообщение подготовлено, автоматически никому не отправлялось.

Следующий отдельный этап — **B2: аккаунты и opaque Bearer sessions** по неизменённому Starter. Он не начат; после B1 остановиться и ждать отдельного поручения. Для пользовательского PR: title `Implement B1 public catalog API`; описание — публичный каталог/поиск/фильтры/metadata, V2/явный DEMO, безопасные ошибки и фактические проверки выше. Commit, push и создание PR не выполнялись.

Далее сохранены исторические отчёты; их утверждения о ещё не начатом B1 относятся к предыдущим заданиям.

## Исторический B0 — основа backend завершена локально

Пользователь отдельно поручил **только B0**, со сборкой и тестами. Ветка — `feat/backend-bootstrap`, основа — локальный planning commit `6e6071c`; `origin/main` при начале работы оставался на `0128f7b`. B1 не начат. Push, создание PR, merge и deployment не выполнялись.

### Что изменено

- [backend/pom.xml](../backend/pom.xml), `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`: Java 21, Spring Boot 4.1.1/BOM, Maven 3.9.16, Wrapper 3.3.4, MVC/JPA/Flyway/Actuator, Failsafe и явный Mockito Java agent.
- [HackAtlasApplication.java](../backend/src/main/java/com/hackatlas/HackAtlasApplication.java) и `backend/src/main/resources/application*.yaml`: каркас и раздельные `dev`/`test`/`prod`, безопасный default `prod`, loopback bind, health/liveness/readiness без внутренних деталей. `test` настройки находятся в test resources.
- [V1__create_application_schema.sql](../backend/src/main/resources/db/migration/V1__create_application_schema.sql): только пустая схема `hackatlas`, без доменных таблиц и seed. История Flyway — в `public`; Hibernate — `ddl-auto=validate`.
- [BootstrapIT.java](../backend/src/test/java/com/hackatlas/BootstrapIT.java): реальные PostgreSQL 17.11/Testcontainers проверки миграции, повторного запуска, HTTP health и границы B0.
- [infra/compose.yaml](../infra/compose.yaml), [infra/.env.example](../infra/.env.example), [infra/README.md](../infra/README.md): отдельный dev PostgreSQL с закреплённым digest, пароль вне Git, порт только `127.0.0.1:5432`.
- [backend-ci.yml](../.github/workflows/backend-ci.yml): contract check, Java/PostgreSQL verify и Gitleaks CLI; Actions закреплены SHA, разрешения только `contents: read`.
- [backend/README.md](../backend/README.md), [backend/TOOLCHAIN.md](../backend/TOOLCHAIN.md), README/START_HERE и общие TOOLCHAIN/DECISIONS: версии, команды, результаты и следующая граница. `backend/AGENTS.md` больше не предписывает автоматически выполнять B0+B1.

API и auth-протокол **не изменены**. Каталог, аккаунты, сессии, admin и mobile не реализовывались; `api/`, fixtures, `mobile/`, `STATUS_MOBILE` и визуальный референс сохранены.

### Выполненные проверки

Команды ниже выполнялись 20 октября 2026; результат относится к локальной машине.

| Проверка / команда | Результат |
|---|---|
| Windows `.\mvnw.cmd --version` и WSL `sh ./mvnw --version` с отдельными пустыми кешами | Exit 0; Maven 3.9.16 / JDK 21.0.12.1; загрузки проверены checksum |
| `wsl -d Ubuntu -u root --exec /bin/sh -lc 'cd /mnt/c/Daniyar/MyProjects/HackAtlas/backend && sh ./mvnw --batch-mode --no-transfer-progress verify'` | **BUILD SUCCESS, 22 tests, 0 failures, 0 errors, 0 skipped**, исполняемый JAR создан |
| `.\.venv\Scripts\python.exe scripts/validate_contract.py` | Exit 0; OpenAPI, границы B1/B2, opaque Bearer и все 6 fixtures проходят |
| `.\.venv\Scripts\python.exe -m pip check` | Exit 0; `No broken requirements found` |
| Compose `config --quiet`, `up -d --wait --wait-timeout 60` | Exit 0; отдельный dev PostgreSQL стал healthy |
| `java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev` из `backend/` | Загружен локальный `.env`, применена V1, приложение запустилось на loopback:8080 |
| HTTP smoke собранного JAR | Health/liveness/readiness: 200/UP, без внутренних деталей; `/actuator/env` и `/api/v1/hackathons`: 404 |
| Тот же JAR без профиля и без `HACKATLAS_*` / `SPRING_*` env | Ожидаемый exit 1: выбран `prod`, datasource не может стартовать без JDBC URL; dev `.env` не подхватывается |
| Read-only GitHub API `/user` и repository metadata | Авторизован **yergaliuly**, write-доступ к KDvibers/HackAtlas подтверждён; Git author отдельно: `softache <itsyergaliuly@gmail.com>` |

Интеграционные тесты создали новую PostgreSQL 17.11 на случайном порту, проверили успешную V1, checksum/validation и `migrationsExecuted=0` при повторном вызове. Health root выдаёт только `status` и публичные имена групп `liveness`/`readiness`; дочерние probes — только `status`, даже запрос `showDetails=true` не раскрывает компоненты. Тесты отсутствия доменных таблиц и API относятся к B0 и должны быть заменены соответствующими сценариями при B1.

В процессе проверок устранены три несовместимости: null `Target` в официальном Windows Wrapper (минимальный guard по upstream issue #395), запись `tag@digest` в parser Testcontainers (заменена на `postgres@digest` с тем же digest), динамическое Mockito self-attach на Java 21 (настроен официальный Java agent). Ожидание root health уточнено под стандартные имена публичных probes; точная JSON-проверка сохранена. Итоговый `verify` выполнен после этих исправлений.

Дополнительный smoke выполнялся временным скриптом `backend/target/b0-smoke.py`; он и логи `b0-verify.log`, `b0-dev-smoke.log`, `b0-prod-smoke.log`, Failsafe XML лежат в игнорируемом `backend/target/` и не входят в коммит. Для повторной проверки в новом клоне использовать `verify` и команды запуска/health из backend README.

Финальные проверки изменений:

- Gitleaks CLI 8.30.1: скачанный Linux-архив сверен с upstream SHA-256. `gitleaks git --redact=100 --no-banner --log-opts="--all" .` проверил доступную историю до B0; exit 0, no leaks found. Затем `git diff --cached --no-ext-diff | gitleaks stdin --redact=100 --no-banner` с `set -euo pipefail` проверил staged B0 diff; exit 0, no leaks found. Проверка выполнялась обычным WSL-пользователем, которому доступен Git checkout. Это поиск распространённых шаблонов, не полный security audit.
- `git diff --cached --check` — exit 0. Проверены 63 относительные Markdown-ссылки в изменённых документах, завершающие newline и отсутствие trailing whitespace. Staged scope ограничен 23 файлами B0/документации, real `.env` и `target/` исключены. Unix Wrapper имеет Git mode `100755`.
- Независимый review агентов проверил код, инструкции запуска, CI и соответствие отчёта результатам. Замечания устранены; финальных блокирующих замечаний нет.

Результат сохраняется локальным коммитом `chore(backend): bootstrap B0 foundation`; его hash смотреть через `git log -1 --oneline`. Ветка включает также предшествующий неопубликованный planning commit `6e6071c`.

### Окружение и ограничения

Docker Engine 29.1.3 / Compose 2.40.3 и OpenJDK 21.0.12.1 доступны в Ubuntu WSL2. Полный `verify` выполнен WSL root, поскольку обычный пользователь не имеет доступа к Docker socket. Права пользователей/socket и global Git config не изменялись. В Windows отдельно проверен Wrapper на Temurin 21.0.12.1; полный Windows/JVM + Docker прогон не заявляется.

После smoke приложение штатно остановлено, dev PostgreSQL остановлен через Compose `stop postgres`; volume `hackatlas-dev_postgres-data` и игнорируемый локальный `infra/.env` сохранены. Временные Testcontainers удалены; БД другого проекта не использовалась и продолжает работать.

GitHub Actions проверен локально по YAML, bash-синтаксису и pins, но **ещё не запускался на GitHub**. Required checks и совместный review ожидают пользовательского push/PR. Production deployment, нагрузка, auth, B1-контрактные HTTP tests, Android/iOS в B0 не проверялись. Отдельных unit tests бизнес-логики пока нет: бизнес-логика ещё не реализована.

### Передача напарнику и следующий этап

«B0 готов локально: Java backend запускается, БД и миграции проверены. API Starter и дизайн прежние. Для mobile доступны прежние fixtures и работа M0/M1; готового каталога/base URL для интеграции ещё нет. После публикации пользователем получить новую ветку/коммит и читать backend README при необходимости локального сервера».

Следующая отдельная работа — **B1**, три публичных GET каталога с DTO, фильтрами, PostgreSQL tests, Bruno и Schemathesis. Автоматически к ней не переходить.

Ниже сохранены исторические отчёты; их сведения «B0 ещё не реализован» относятся к предыдущим заданиям.

## Направление и общий план — 20 октября 2026

По просьбе пользователя закреплены продуктовые ориентиры в [PROJECT](PROJECT.md), технологические решения в [TECHNOLOGY_DECISIONS](TECHNOLOGY_DECISIONS.md) и общий план backend/mobile в [ROADMAP](ROADMAP.md). Их чтение включено в корневые AGENTS, START_HERE и WORKFLOW; README ссылается на актуальные документы. В DECISIONS записано принятие направления с сохранением условий выбора кандидатов.

Исследование и документы плана сохраняются одним локальным коммитом в `chore/technology-research`; сообщение — `docs: lock HackAtlas direction and shared delivery plan`, hash доступен через `git log -1 --oneline`. Push и создание PR выполняет пользователь.

Реализация B0/B1, SDK-установки и новые зависимости не входят в это задание. API, fixtures, mobile-код, STATUS_MOBILE и визуальный референс не изменены. Следующая отдельная работа владельца backend — B0; напарника — M0/M1 с остановкой на визуальную приёмку.

Проверки фиксации плана:

- `git diff --check` — exit 0. Python-проверка 11 изменённых Markdown-файлов подтвердила 63 существующие относительные ссылки, завершающие newline и отсутствие trailing whitespace; изменения ограничены документацией.
- Выполнен review границ backend/mobile. Уточнены самостоятельные GitHub-аккаунты разработчиков, выбор своего STATUS, зависимости M2/M3 и отсутствие повторного разрешения внутри уже порученного пакета этапов.
- `java -version` — Temurin 21.0.12.1. `Get-Command java,git,docker,mvn,adb -ErrorAction SilentlyContinue` нашёл Java и Git; Docker/Maven/adb не найдены в PATH. Это не полная инвентаризация установок; Docker engine и mobile SDK не проверены.
- Локальные Git identity и выбранный credential username прочитаны; они совпадают с проверенной в G0 настройкой автора и `yergaliuly`. Новая проверка действующего GitHub-токена не выполнялась; перед пользовательским push аккаунт авторизации проверяется отдельно.
- Тесты приложения не запускались: изменения затрагивают только документы. Публикация и передача изменений в клон напарника ещё не выполнены.

Передача напарнику: «Общий план и технологии закреплены в ROADMAP и TECHNOLOGY_DECISIONS. После публикации пользователем получить ту же версию документов. Начать M0/M1 на Starter fixtures, сохранить дизайн; Ktor-интеграция ждёт B1. Шрифт, navigation и offline DB выбираются по оговорённым проверкам. Работающего base URL пока нет».

## Исследование технологий — 20 октября 2026

Подготовлен [Technology Research & Product Brainstorm](research/2026-10-20-technology-research.md): backend, KMP/Compose, GitHub-инструменты, тестирование, источники событий, эксплуатация, лицензии и продуктовые гипотезы. Рекомендации отделены от принятых решений; реализации и установки новых зависимостей нет.

После `git fetch origin` подтверждено, что G0 находится в `origin/main` (`0128f7b`, merge PR #1). Создана ветка `chore/technology-research` от этой основы. Backend по-прежнему не реализован; следующий отдельный этап — B0. API, авторизация, mobile и дизайн не изменены.

Проверки этого задания: чтение инструкций/контракта и официальных upstream-источников; review рекомендаций; проверка относительных ссылок отчёта и `git diff --check`. Сборки приложения, тесты PostgreSQL, iOS/device-проверки и новые проверки SDK не выполнялись. GitHub push, создание PR и deployment не выполнялись.

Результаты локальной проверки документации:

- `git status --short --branch` — ветка `chore/technology-research`, изменены только этот статус и новый `docs/research/2026-10-20-technology-research.md`.
- `git diff --check` — exit 0, замечаний к отслеживаемому diff нет.
- `git diff --no-index --check -- NUL docs/research/2026-10-20-technology-research.md` — нет whitespace-диагностик; exit 1 соответствует сравнению нового файла с пустым.
- Ad hoc Python-проверка через `.venv/Scripts/python.exe` — 7 относительных ссылок в двух документах существуют; у отчёта нет trailing whitespace и незаменённых служебных ссылок источников, есть завершающий newline.

Ниже сохранён исторический отчёт G0 с окружением и результатами на 12 октября; он не заменяет новую проверку toolchain перед B0.

## Исторический отчёт G0 — 12 октября 2026

**G0 / этап 1: локальная подготовка завершена.** Публикация ветки, PR и совместный review остаются за пользователем и напарником. Реализация backend, БД и авторизации ещё не начата.

Ветка: `chore/starter-setup`. Основа: `origin/main`, commit `7824a4b79d94db816fb31de2a01fa148f4b2680c`. Локальный commit этапа имеет сообщение `chore: prepare Starter specification and G0 checks`; точный hash смотреть через `git log -1 --oneline`.

## Сделано

- Клонирован существующий `KDvibers/HackAtlas`, сохранена его начальная история. До G0 в репозитории был только README.
- Перенесён Starter, обновлены инструкции и README под фактический репозиторий, границы работы и пользовательские push/PR.
- Зафиксировано: только `api/openapi.yaml` + `api/README.md`, opaque Bearer sessions без JWT/refresh. Контракт и fixtures не изменены; новый контракт несовместим со старым Handoff, несмотря на тот же номер 0.1.0.
- Созданы [решения](DECISIONS.md) и [отчёт о расхождениях/окружении](SETUP_AUDIT.md).
- Усилена проверка контракта в `scripts/`, закреплена 21 developer-зависимость, подготовлена локальная игнорируемая `.venv` с Python 3.12.14.
- Через read-only GitHub API подтверждены аккаунт `yergaliuly`, write-доступ и привязка автора первоначального коммита. В local Git config закреплены аккаунт и подтверждённое авторство; global config не менялся.

## Реальные проверки

Все перечисленные команды завершились с exit code 0:

```powershell
.\.venv\Scripts\python.exe -m pip install PyYAML==6.0.3 jsonschema==4.26.0 openapi-spec-validator==0.9.0
.\.venv\Scripts\python.exe -m pip install rfc3986-validator==0.1.1
.\.venv\Scripts\python.exe .\scripts\validate_contract.py
.\.venv\Scripts\python.exe -m pip check
git diff --cached --check
```

- PASS: OpenAPI 3.1.0 / API 0.1.0, 14 paths, 17 operations, 24 schemas; локальные `$ref`, уникальность operationId и параметры путей.
- PASS: границы B1/B2/B3, три публичных маршрута каталога, opaque Bearer и отсутствие refresh.
- PASS: все шесть demo JSON из manifest; date/date-time/UUID/URI/email имеют активные format checkers.
- PASS: семь негативных сценариев в памяти отклонены, включая JWT, refresh, public `/me`, повтор operationId, отсутствие path parameter/x-phase и невалидный URI. Исходные файлы при этих проверках не менялись.
- PASS: `pip check` — `No broken requirements found`.
- PASS: 14 относительных Markdown-ссылок в 24 файлах существуют. Внешние URL и anchors не проверялись.
- PASS: все 15 рабочих файлов `api/`, `design/`, `mobile/` совпадают с файлами Starter по SHA-256; hash референса `fd7dd0a85b8dd39a278481cc43faf96199f8ad098fbad1e885be6ab75c2be1cf`.
- PASS: проверены состав изменений и распространённые шаблоны секретов; `.venv` исключена Git ignore. Это ограниченная проверка, не полноценный security audit.
- Независимое review общих инструкций выполнено; замечание о точности описания исходного START_HERE исправлено.

Для повторного запуска достаточно `.\.venv\Scripts\python.exe .\scripts\validate_contract.py`. Создание окружения на другой машине описано в [scripts/README](../scripts/README.md).

## Не проверено / ещё не реализовано

Нет серверных endpoints, Maven Wrapper, PostgreSQL migrations, backend CI и Java-тестов. Сборка backend, Testcontainers, безопасность работающей авторизации и мобильные/device проверки не запускались. Push, PR, merge и deployment не выполнялись.

JDK 21 и Git доступны. `mvn`, `docker`, `gh` не найдены в PATH. Для B0 нужен Maven Wrapper и доступный Docker/Compose для PostgreSQL/Testcontainers; Docker engine пока не проверен. Общая спецификация проверяется без них.

У четырёх demo-событий в catalog-page.json скопирован текст «AI-хакатон»; это отмечено для совместной правки, схема и fixtures сохранены. GitHub handle напарника и доступ к Mac/Xcode пока не подтверждены.

## Передача напарнику

Подготовлена ветка `chore/starter-setup` со Starter. Использовать только `api/`, opaque Bearer без refresh и `design/reference/approved-reference.png`. Работающего API/base URL ещё нет. M0/M1 можно планировать по общим fixtures после получения пользовательского PR и сверки спецификации.

## Следующий отдельный этап

**B0:** обеспечить Docker/Compose, проверить совместимые стабильные версии Spring Boot 4.x и PostgreSQL 17, создать Java 21 skeleton, Maven Wrapper, окружения, Flyway и базовые проверки. Не реализовывать B2/auth заранее.
