# МОЙ ПРОМПТ ДЛЯ CODEX — JAVA BACKEND

Ты — разработчик серверной части HackAtlas. Я пишу backend на Java, мой друг — общий мобильный клиент Android/iOS на Kotlin Multiplatform + Compose Multiplatform. Мы используем один GitHub-монорепозиторий и продолжаем реализацию в Codex. Общайся по-русски, создавай необходимые файлы по этапам; не ограничивайся советами.

## 1. Обязательный контекст

Прочитай AGENTS.md, backend/AGENTS.md, docs/PROJECT.md, docs/ROADMAP.md, docs/WORKFLOW.md, docs/TOOLCHAIN.md, docs/STATUS_BACKEND.md, api/README.md, api/openapi.yaml и общие api/examples.

Фото design/reference/approved-reference.png — окончательный дизайн мобильного клиента. Друг реализует его; тебе не нужно генерировать новый frontend или предлагать упрощённый стиль. Обеспечь данные, которые нужны карточкам/detail/profile, а не подгоняй дизайн под удобную таблицу БД.

Проверь git status, ветку, структуру, существующий код и окружение. Не перезаписывай чужую работу. Если проект уже начат, сначала установи реальный текущий этап по файлам и тестам; не создавай его повторно.

## 2. Что строим

Каталог хакатонов: название, организатор, формат, место, сроки, дедлайн заявки, темы, команда, требования, призы, этапы, официальный источник и ссылка регистрации. Пользователь сохраняет события, настраивает профиль, позже получает локальные напоминания в приложении. Регистрация на событие происходит на сайте организатора, а не у нас.

Просмотр каталога public; профиль/закладки/личные отметки — только owner. Поиск команды и чат позже. Не добавлять микросервисы, AI рекомендации, платежи, event registration engine, массовый scraper или неиспользуемые team tables.

## 3. Стек

Java21, поддерживаемая стабильная линия Spring Boot4.x, Spring MVC, Bean Validation, Spring Security, Spring Data JPA, PostgreSQL17 с актуальным minor, Flyway, Maven wrapper. Unit tests на JUnit, integration tests с PostgreSQL/Testcontainers. Docker Compose для локальной DB; готовность/health без раскрытия секретов. Для небольшой admin web позже Thymeleaf + server-side forms, не отдельный React-проект.

Точные версии выбери после проверки официальной совместимости и зафиксируй в backend/TOOLCHAIN.md, pom/wrapper и infra config. Никаких SNAPSHOT/beta, latest image tag и бесконтрольного обновления всего проекта. Backend Maven и mobile Gradle независимы. Не менять мобильный version catalog.

Предпочтительная структура backend/src/main/java/dev/hackatlas: catalog, auth, profile, saved, applications, moderation, admin, common. Небольшой feature-oriented монолит. Controllers → services → repositories, отдельные DTO/mappers. Не раскрывать JPA entities как JSON. Не создавать50 интерфейсов без применения.

## 4. API — уже общий договор

api/openapi.yaml + api/README.md — канонические имена endpoints, schemas, enums, nullable поля, ответы и ошибки. В OpenAPI операции помечены x-phase: не означает, что они уже написаны. Не изменять contract молча и не давать другу другую модель JSON. Не импортировать конфигурацию и auth другого нашего проекта вроде StudyMate.

Сначала public B1:
GET /api/v1/hackathons;
GET /api/v1/hackathons/{id};
GET /api/v1/metadata/filters.

Search: query по title, organizer, topic labels, city; фильтры format, countryCode, city, topic, registrationStatus, individualApplicationAllowed, startsFrom, startsTo. AND semantics и даты по api/README. Sort DEADLINE_ASC/START_ASC/NEWEST с tie-breaker, unknown last. page0-based, size1..50, stable pagination. DTO HackathonPage и Detail строго по файлу.

Валидировать query bounds, даты, sort, enum, uuid, text lengths. Ограничить size и request payloads. Не принимать произвольные SQL поля. Предусмотреть нужные индексы, избежать N+1 на lists. Для MVP PostgreSQL query достаточно, не Elasticsearch.

Общий error format application/problem+json со status, code, detail, requestId, fieldErrors. Не отдавать stacktrace/SQL/секреты. Native клиенту не нужен CORS '*'. Authenticated browser admin будет отдельным security chain.

## 5. Модель и миграции

UUID identifiers. Таблицы хакатонов, topics/relations, published state, источник и verified timestamp — по фактическим нуждам B1. users, sessions, saved, application_marks добавлять в B2; moderation — в B3. Flyway — источник схемы. Hibernate ddl-auto=validate, не update/create-drop в production. Dev/test fixtures отдельно от production migrations.

DTO EventTime хранит точность даты/времени. Не превращать дату без времени в полночь/23:59. Технические timestamps UTC, пользовательские event date/zone сохраняются. Проверять starts/ends и связанные поля. В тестах Clock injected; не зависеть от реального today.

Registration status и event lifecycle отдельные. Индивидуальная заявка и возможность выступать одному отдельные. Приз: BigDecimal → decimal string, currency, cash/mixed/noncash, unknown/none разные. Null не заменять0. LastVerifiedAt меняется после сверки источника, updatedAt при изменении записи — это разные события.

Public catalogue только published соответствующих состояний; draft не раскрывать. Опубликованные завершённые/отменённые детали могут оставаться по прямой ссылке, чтобы закладки не исчезали. Архивирование предпочтительнее разрушительного удаления. Точная семантика в README.

## 6. Auth и личные функции — только следующий этап B2

Заранее заложи совместимость с договором, но не пиши всё в первой сессии.
POST auth/register/login/logout; GET/PATCH me; GET me/saved и идемпотентные PUT/DELETE me/saved/{id}; GET/PUT/DELETE application marks.

Выбрана простая серверная opaque bearer session, не JWT. Token не менее256 random bits, в DB только token hash, сроки и отзыв. Срок по умолчанию30дней, configurable, без бесконечного продления; endpoint refresh не существует. Пароли через готовый Spring Security adaptive PasswordEncoder, предпочтительно Argon2id с проверенными параметрами/зависимостями; не SHA256 для passwords и не собственная криптография. На login выдача токена no-store. Не логировать credentials. Ограничить auth попытки.

API bearer auth через Authorization header; проверка ownership на каждом запросе, не доверять переданному userId. Два тестовых пользователя должны быть полностью изолированы. PATCH разрешает только указанные profile поля, не roles/admin/email. Unique(user_id,event_id) для saved/marks, повтор PUT без дублей, повтор DELETE безопасен.

Logout отзывает текущую сессию сервером. Account deletion/password reset позже отзывают все соответствующие сессии. 401/403 различать. Не отключать CSRF глобально: API header-only token chain отдельно от cookie-based admin формы с CSRF и secure/HttpOnly cookies.

«Я подал(а) заявку» — selfReported=true, не acceptance. External URL click не пишет application mark. Backend не регистрирует пользователя у стороннего организатора.

Public signup в production до account-lifecycle hardening выключен. До публичного релиза согласовать OpenAPI расширение verification/reset и действующую отправку email; не имитировать успешную отправку.

## 7. Наполнение и модерация — B3

Минимальная admin web: login ADMIN, создать draft, заполнить данные/источник, опубликовать, изменить, снять с публикации, указать перенос/отмену, пометить сверку. ADMIN назначается безопасной управляемой процедурой, не client-side флагом и не public registration role.

Добавить suggestions/reports с rate limit и PENDING; публикация после review. Не делать backend fetch произвольных URL пользователя; разрешённый HTTPS URL проверяется без скачивания, чтобы не создавать SSRF. Не выбирать production-данные из случайных сайтов без проверки.

## 8. Dev и интеграция

Dev порт8080, DB отдельная от других проектов, credentials из .env (в git только .env.example без реальных секретов). Документировать docker compose up, миграции, запуск Maven wrapper для PowerShell и bash. Проверить готовность DB; health не выдаёт внутренние endpoints публично без необходимости.

Mobile должен иметь конфиг API URL. На реальном телефоне localhost не ПК. Дай другу правильные варианты для эмулятора, LAN и HTTPS staging, ограничения firewall/server binding. Не выставлять PostgreSQL в интернет и не вводить wildcard CORS вместо решения сетевого доступа.

Fixtures в api/examples имеют isDemo=true. Для B1 dev seed используй те же IDs и данные; production default demo=false. Seed должен быть idempotent и включаться явно; не дублировать при restart. Составь smoke request examples, не писать реальные tokens в git. Не покупать домены/серверы и не публиковать сервис без команды пользователя.

## 9. Проверки и GitHub

Твоя зона backend/, infra/, backend-ci, docs/STATUS_BACKEND.md. Не редактировать mobile/ и дизайн. api/ меняется совместно после согласованного PR.

Начальные проверки: schema validation для fixtures; catalog DTO serialization; valid/invalid search params; pagination; unknown prize/deadline; status calculation на fixed Clock; published/draft access; Flyway чистая DB и повторный запуск; PostgreSQL integration, а не только H2. На B2 добавить security tests для anonymous/userA/userB/admin, expired/revoked session, idempotency и guest saved import semantics.

Если Docker/Testcontainers/JDK недоступны, укажи фактическую ошибку и точные шаги исправления. Не отключай tests и не объявляй PostgreSQL проверенным на основании unit tests.

Работай в feat/backend-... ветке. Не force push, reset--hard, clean-fd и не перезаписывай чужие changes. Не выполнять push/merge/deploy без отдельного разрешения. Для PR дать summary/tests/API/migrations/known-blockers. В main после общего первоначального bootstrap изменения через PR.

## 10. Что сделать прямо сейчас

В первой сессии выполни только B0 и B1:
1. Изучи контекст и существующий репозиторий, проверь окружение, коротко покажи план.
2. Создай Java21 Spring Boot проект с Maven wrapper и версионной фиксацией.
3. Подними локальную PostgreSQL конфигурацию, Flyway и dev/test profiles; без вмешательства в другие БД.
4. Реализуй полноценный public list/detail/filter-metadata по контракту с data seed.
5. Добавь/запусти tests, error mapping, health и инструкции запуска.
6. Обнови STATUS_BACKEND и подготовь сообщение другу: реализованные endpoints, пример запроса/ответа, base URL, как запустить, что пока отсутствует.
7. Остановись перед B2. Не генерировать одновременно auth, админку, deployment и десятки незавершённых фич.

Финал: готовые файлы; фактически выполненные команды и результат; отдельно непроверенное; команды для PowerShell; контрактные вопросы; текст для передачи другу; следующий этап. Не заявлять, что сервер опубликован или подключён к реальному мобильному приложению, если этого не было.
