# Backend HackAtlas

Реализованы основа B0 и публичный каталог B1: Java 21 / Spring Boot, PostgreSQL/Flyway, три GET endpoint, health, интеграционные тесты и CI. Авторизация B2 и модерация B3 ещё не реализованы. Единственный контракт — [OpenAPI](../api/openapi.yaml) и [его семантика](../api/README.md); opaque Bearer sessions остаются решением для B2.

Параметры каталога, политика статусов и явный DEMO-запуск описаны в [CATALOG](CATALOG.md).

## Требования и сборка

Нужны JDK 21, доступ в интернет для первого скачивания зависимостей и Docker с Linux containers для интеграционных тестов. Maven устанавливать отдельно не нужно: используется Wrapper. В Unix нужны `sh`, `curl` или `wget` и `unzip`; последний нужен для проверки закреплённого ZIP checksum. Версии и источники — в [TOOLCHAIN](TOOLCHAIN.md).

Из каталога `backend/`:

```powershell
.\mvnw.cmd --version
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Linux / WSL:

```bash
sh ./mvnw --version
sh ./mvnw --batch-mode --no-transfer-progress verify
```

`verify` компилирует приложение, создаёт исполняемый JAR и запускает PostgreSQL integration tests через Maven Failsafe. Без работающего Docker проверка завершается ошибкой. Запуск `test` сам по себе не выполняет integration tests.

Тестовая PostgreSQL создаётся Testcontainers на случайном порту и удаляется после завершения. Пароль генерируется для тестового контейнера. Ни локальный dev volume, ни `infra/.env` тестам не нужны. Проверяются миграции, настоящий HTTP каталог и ошибки, фильтры/пагинация/сортировка, DATE/MINUTE и статусы с фиксированным Clock, ограниченное число SQL-запросов, health и запрет DEMO в prod.

После `verify` проверить экспорт настоящих HTTP-ответов по общей OpenAPI-схеме. Из `backend/`, используя Python-окружение с [зависимостями validator](../scripts/README.md):

```powershell
..\.venv\Scripts\python.exe src/test/resources/validate_http_contract.py --schema ../api/openapi.yaml --responses target/catalog-contract-responses.json
```

В bash использовать Python того окружения:

```bash
python src/test/resources/validate_http_contract.py --schema ../api/openapi.yaml --responses target/catalog-contract-responses.json
```

Bruno и Schemathesis проверяют отдельно собранный JAR на временной БД; инструкции — в [scripts/b1](../scripts/b1/README.md).

## Локальный запуск

1. Подготовить игнорируемый `infra/.env` и запустить PostgreSQL по [инструкции infra](../infra/README.md).
2. Из `backend/` выполнить:

```powershell
.\mvnw.cmd "-Dspring-boot.run.profiles=dev" spring-boot:run
```

В Linux / WSL:

```bash
sh ./mvnw -Dspring-boot.run.profiles=dev spring-boot:run
```

После успешного `verify` можно запускать уже собранный файл:

```text
java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

По умолчанию каталог пустой. Для явной загрузки общих вымышленных fixtures добавить `--hackatlas.demo.enabled=true`; детали и фиксированная дата DEMO описаны в [CATALOG](CATALOG.md). Production миграции не загружают события.

Профиль `dev` читает `../infra/.env` относительно рабочего каталога, поэтому запускать следует из `backend/`. Значения задаются как простой `KEY=value` без кавычек, подстановок и inline-комментариев. Переменные окружения имеют приоритет. Сервер по умолчанию слушает `127.0.0.1:8080`.

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

В Linux подойдёт `curl --fail http://127.0.0.1:8080/actuator/health`. У готового приложения root health возвращает `{"groups":["liveness","readiness"],"status":"UP"}`, отдельные probes — `{"status":"UP"}`. Readiness включает доступность БД, liveness проверяет состояние приложения; компоненты и внутренние детали не выдаются. `/actuator/env`, `/actuator/configprops` и другие служебные endpoints не доступны. Локальный base URL каталога — `http://127.0.0.1:8080/api/v1`; опубликованного staging нет.

## Окружения и схема

| Профиль | Источник подключения | Назначение |
|---|---|---|
| `dev` | Локальный `infra/.env` или env; localhost URL и username имеют dev defaults, пароль обязателен | Ручной запуск с Docker Compose |
| `test` | `@ServiceConnection` к отдельному Testcontainers PostgreSQL | Интеграционные тесты |
| `prod` (по умолчанию) | Обязательные `HACKATLAS_DB_URL`, `HACKATLAS_DB_USERNAME`, `HACKATLAS_DB_PASSWORD` | Основа будущего deployment; dev-файл не импортируется |

`PORT` переопределяет порт; `HACKATLAS_BIND_ADDRESS` — адрес прослушивания. Production hosting, TLS и выпуск относятся к B4. Не включать внешний bind без подготовки окружения.

Flyway применяет V1 (схема `hackatlas`) и V2 (`catalog_hackathon`, `catalog_tag`, `catalog_hackathon_topic` с ограничениями и индексами); история хранится в `public.flyway_schema_history`. Hibernate использует `ddl-auto=validate`, Open Session in View отключён. Пользователей и seed в миграциях нет. Существующие применённые миграции не переписывать; изменения добавлять следующей версией. Запросы каталога используют read-only транзакцию PostgreSQL `REPEATABLE READ`, чтобы count, страница и детали читались из одного снимка.

## Проверенный путь на текущей Windows-машине

Docker Engine находится в Ubuntu WSL2. Windows Wrapper проверен отдельно; полный `verify` выполняется внутри WSL, где доступны JVM и Docker socket:

```powershell
wsl -d Ubuntu -u root --exec /bin/sh -lc 'cd /mnt/c/Daniyar/MyProjects/HackAtlas/backend && sh ./mvnw --batch-mode --no-transfer-progress verify'
```

`root` нужен здесь потому, что обычный WSL-пользователь не имеет доступа к Docker socket. Права socket и группы пользователей не изменялись. Этот путь зависит от местоположения клона; на другой машине использовать свой путь и пользователя с доступом к Docker. Не направлять тесты на существующую БД другого проекта.

Результаты этапа, ограничения проверки и передача напарнику — в [STATUS_BACKEND](../docs/STATUS_BACKEND.md). CI описан в [infra/README](../infra/README.md); push и PR выполняет пользователь.
