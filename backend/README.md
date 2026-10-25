# Backend HackAtlas

Реализованы основа B0, публичный каталог B1, аккаунты/личные данные B2 и модерация/удаление аккаунта B3: Java 21 / Spring Boot, PostgreSQL/Flyway, Spring Security, Thymeleaf, health, интеграционные тесты и CI. JSON API использует opaque Bearer sessions; админка — отдельную browser session с CSRF. Единственный mobile API контракт — [OpenAPI](../api/openapi.yaml) и [его семантика](../api/README.md).

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

Для B2 после `verify` тот же schema validator принимает каталог `target/b2-contract` вместо `target/catalog-contract-responses.json`. Затем [B2 runner](../scripts/b2/README.md) проверяет собранный JAR и [26 запросов Bruno](../tools/bruno-b2/README.md) на своей временной БД. B1 regression checks остаются обязательными.

Для B3 validator принимает каталог `target/b3-contract`. [B3 runner](../scripts/b3/README.md) проверяет три JSON endpoint модерации/удаления и [24 запроса Bruno](../tools/bruno-b3/README.md), создавая и удаляя только одноразовые аккаунты в отдельной PostgreSQL. CI сохраняет все проверки B1/B2 и добавляет B3.

## Аккаунты и личные данные B2

Публичный каталог доступен без входа. `POST /api/v1/auth/register` и `POST /api/v1/auth/login` возвращают opaque token; защищённые операции принимают его только в `Authorization: Bearer <token>`. PostgreSQL хранит SHA-256 хеш случайного токена, а пароли обрабатывает Spring Security Argon2id. В JSON API cookie-based auth, JWT и refresh flow отсутствуют. Токен имеет абсолютный срок 30 дней по умолчанию; logout отзывает текущую сессию, после чего она получает 401.

Регистрация закрыта по умолчанию. Для локальной разработки включить её явно:

```text
java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --hackatlas.auth.registration-enabled=true
```

В production регистрация остаётся закрытой даже при ошибочном включении dev-флага. Проверка email и password reset относятся к отдельному согласованию перед публичным запуском.

После входа доступны GET/PATCH `/api/v1/me`, GET `/api/v1/me/saved`, PUT/DELETE `/api/v1/me/saved/{id}`, GET `/api/v1/me/applications`, PUT/DELETE `/api/v1/me/applications/{id}`. PATCH сохраняет отсутствующие поля; `null` очищает только nullable поля. Закладка и личная отметка заявки независимы; повтор PUT не создаёт дубликат и не обновляет `markedAt`. `selfReported=true` не подтверждает регистрацию у организатора.

Auth ограничивает попытки по IP и нормализованному email. Настройки: `hackatlas.auth.session-ttl=P30D`, `hackatlas.auth.rate-limit.ip-limit=30`, `account-limit=10`, `window=PT15M`, `max-keys=10000` (последние три с тем же префиксом `hackatlas.auth.rate-limit`). Лимиты действуют внутри одного процесса; согласование общего лимитера для нескольких экземпляров относится к подготовке deployment. Rate limits проверяются отдельными HTTP-тестами с малыми значениями; runtime smoke использует обычные defaults. Пароли, токены и заголовок Authorization не добавлять в логи и файлы репозитория. Подробности реализации и ограничения — в [AUTH](AUTH.md).

## Модерация и удаление аккаунта B3

`POST /api/v1/suggestions` и `POST /api/v1/hackathons/{id}/reports` требуют Bearer session и возвращают `202` с receipt `PENDING`. Предложение источника не публикуется автоматически. Пользовательские URL сохраняются после проверки HTTPS и отсутствия userinfo; сервер не скачивает их. Для этих операций действуют отдельные лимиты по IP и пользователю.

`POST /api/v1/me/delete-account` принимает JSON `{ "password": "…" }`, повторно проверяет пароль и возвращает `204` после удаления аккаунта и личных связей. Все его сессии прекращают давать доступ; неверный пароль возвращает `401` без удаления. Воспроизводимые сценарии используют только одноразовые аккаунты.

Админка `/admin` обслуживает draft/publish/archive, исправление данных, перенос/отмену и явную отметку проверки источника. Доступ выдаётся отдельно; обычный пользователь его не получает. Thymeleaf создаёт серверные HTML-формы, Spring Security защищает browser session и CSRF. Подготовка локального администратора, правила работы и последствия удаления для audit/backups описаны в [MODERATION](MODERATION.md). B4 deployment остаётся отдельным этапом.

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

Flyway применяет V1 (схема `hackatlas`), V2 (`catalog_hackathon`, `catalog_tag`, `catalog_hackathon_topic`), V3 (`user_account`, `auth_session`), V4 (`user_interest`, `saved_hackathon`, `application_mark`), V5 (`moderation_submission`) и V6 (`admin_access`, `admin_audit`) с ограничениями и индексами; история хранится в `public.flyway_schema_history`. Hibernate использует `ddl-auto=validate`, Open Session in View отключён. Миграции создают схему без seed-аккаунтов, admin grants и событий. Существующие применённые миграции не переписывать; изменения добавлять следующей версией. Запросы каталога используют read-only транзакцию PostgreSQL `REPEATABLE READ`, чтобы count, страница и детали читались из одного снимка.

## Проверенный путь на текущей Windows-машине

Docker Engine находится в Ubuntu WSL2. Windows Wrapper проверен отдельно; полный `verify` выполняется внутри WSL, где доступны JVM и Docker socket:

```powershell
wsl -d Ubuntu -u root --exec /bin/sh -lc 'cd /mnt/c/Daniyar/MyProjects/HackAtlas/backend && sh ./mvnw --batch-mode --no-transfer-progress verify'
```

`root` нужен здесь потому, что обычный WSL-пользователь не имеет доступа к Docker socket. Права socket и группы пользователей не изменялись. Этот путь зависит от местоположения клона; на другой машине использовать свой путь и пользователя с доступом к Docker. Не направлять тесты на существующую БД другого проекта.

Результаты этапа, ограничения проверки и передача напарнику — в [STATUS_BACKEND](../docs/STATUS_BACKEND.md). CI описан в [infra/README](../infra/README.md); push и PR выполняет пользователь.
