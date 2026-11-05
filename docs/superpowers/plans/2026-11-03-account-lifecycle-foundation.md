# Account lifecycle foundation — план реализации

> Исполнение: superpowers:executing-plans, один агент. Commit/push/PR выполняет пользователь.

**Goal:** подготовить внутреннее хранилище одноразовых email challenges/reset grants,
не включая почту, HTTP lifecycle и новые ограничения действующей авторизации.
**Architecture:** Java JCA для криптографии, Flyway/PostgreSQL для состояния,
JdbcTemplate внутри транзакции вызывающего сервиса. Компоненты пока не Spring beans;
будущий HTTP/mail слой должен подключить их отдельно и атомарно выполнить действие.
**Tech Stack:** существующие Java 21, Spring Boot 4.1.2, PostgreSQL 17.11, Flyway,
JUnit/Testcontainers. Без новых зависимостей и инфраструктурных сервисов.
**Spec:** [целевой дизайн](../specs/2026-11-01-account-lifecycle-design.md),
[план совместимости](../../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md).
**Base:** main `2d05c6fab434829b5c34f52af2bd2b947ed22e36`, merged PR #23;
ветка `feat/account-lifecycle-foundation`, исходно чистая.

## Global Constraints

- Это первый backend-подэтап реализации после contract-only PR; mobile остаётся у друга.
- Canonical 0.1.1, planned paths, существующие HTTP responses/Bearer не изменять.
- Не включать частичный lifecycle; signup остаётся закрытым по прежним правилам.
- SMTP/provider/key deployment, runtime wiring/permissions, guards, forced recovery
  при restore и публичный выпуск — отдельный следующий этап, не результат этой основы.
- Коды: 8 ASCII цифр, TTL 10 минут, 5 неверных попыток, resend 60 секунд,
  максимум 3 активных challenge на user/purpose, без отмены старых при resend.
- Challenge/reset token: 32 случайных байта, Base64URL 43 символа; в БД SHA256.
  Code: HMAC-SHA256 с отдельным 32-byte key в памяти, binding purpose/user/version/id.
- Reset grant: 5 минут, одноразовый; не Bearer. Секреты не логировать/не писать в БД.
- Блокировки: user account → challenge/grant; как существующие login/delete.
- Store требует активную транзакцию. Invalid возвращается значением, не исключением;
  будущая facade должна commit attempts до HTTP error mapping и завершать успешное
  изменение аккаунта в той же транзакции, что consumption.

## Review Focus

- Конкурентные consume/resend и пятая попытка: один победитель, лимиты не обходятся.
- Чужой user/purpose/version и воссозданный email: старые proofs не подходят.
- Ключ после restart/ротации, граница expiry и задержанная задача: fail closed.
- Rollback после consumption: код/grant и изменение аккаунта откатываются вместе.
- Наследованные аккаунты не получают verified или выдуманный delivery address;
  runtime роль не получает доступ к неподключённым таблицам.

## Task 1 — миграция и crypto

**Files:** новая `V8__account_lifecycle_foundation.sql`,
`auth/lifecycle/EmailActionCrypto.java`, `auth/lifecycle/EmailActionPurpose.java`;
tests `AccountLifecycleMigrationIT`, `auth/lifecycle/EmailActionCryptoTest`.
**Interfaces:** crypto создаёт Challenge/ResetGrant с redacted toString, hashToken,
codeMac и matchesCode. Миграция даёт credential_version, verified/reset-required,
nullable email_delivery_address; таблицы email_action_challenge/password_reset_grant.

- [x] Проверить независимый HMAC-вектор, привязки контекста и смену ключа.
  Проверить ASCII-цифры с ведущими нулями, expiry и redaction; V7→V8 сохраняет credentials,
  legacy false/0/null, DB constraints/CASCADE, без plaintext secret columns.
- [x] Наблюдать RED: отсутствующие классы/миграция, затем реализовать минимальный
  JCA/schema код; запустить `-Dtest=EmailActionCryptoTest test` и migration IT.
- [x] Expected: crypto и реальный PostgreSQL migration PASS, ни один старый аккаунт
  не объявлен verified и ни один исходный email не выдуман.

## Task 2 — транзакционное хранилище и сохранение delivery address

**Files:** `auth/lifecycle/EmailActionStore.java`, минимальный INSERT в AuthService,
сохранение delivery spelling после canonical validation в JsonRequests,
tests `EmailActionStoreIT`, отдельный тест metadata регистрации в PersonalApiIT.
**Interfaces:** store(JdbcTemplate, Clock, EmailActionCrypto); createChallenge(user,
purpose, Challenge) → boolean; consumeVerification(user,id,code) → Optional<VerifiedAction>;
exchangeResetCode(id,code) → Optional<ResetGrant>; consumeResetGrant(token) → Optional<VerifiedAction>.
Все операции требуют caller transaction; proof несёт только UUID/version.

- [x] Написать RED tests на transaction requirement, committed attempts/restart,
  границы expiry, wrong purpose/user/version/key, cooldown/cap без отмены старых,
  replay/parallel consume, account-delete/recreate и rollback с account mutation.
- [x] Реализовать JDBC с user-first locks, счётчиками/consumption в общей транзакции.
  Exchange потребляет code и создаёт grant атомарно. Не менять пароль/verified в store:
  этим позже занимается facade, сохраняя ту же транзакцию.
- [x] Написать RED test регистрации: canonical email прежний, delivery address
  сохраняет исходный регистр после strip, новые flags false/version0.
  Добавить только metadata INSERT; существующий auth protocol/DTO/TTL не менять.
- [x] Expected: store IT и существующие auth tests PASS; raw code/token отсутствуют
  в БД, секреты не попадают в toString; grant не аутентифицируется как Bearer.

## Task 3 — регрессии и документация

**Files:** backend README/lifecycle README, собственный STATUS_BACKEND, DECISIONS,
этот план; только необходимые связанные изменения по результатам тестов.

- [x] Maven verify/dependency:list, validator + 13 transitional tests, Python B4/security,
  fresh HTTP exports и существующие packaged B1/B2/B3; HTTPS/restore rehearsal
  проверяет, что V8 не открывает quarantine и продолжает V6→V7→V8 upgrade цепочку.
  В rehearsal report заменить устаревший фиксированный migratedToVersion=7 на
  проверенную текущую V8; runtime/restore guards/ACL этим не меняются.
- [x] OSV, Gitleaks changed files, whitespace/links/scope, отдельная проверка GitHub
  HTTPS account и commit identity. Tests UID1000; без root test processes.
- [x] Проверить branch целиком, обновить STATUS/границы и сообщение другу;
  дать точные add/commit/push/PR команды, ничего не отправлять за пользователя.
- [x] Expected: фактический отчёт с числами/ограничениями, API/mobile/infra grants
  неизменны, planned остаётся planned. Отдельный следующий этап — wiring/mail/API
  и согласованное расширение контракта перед activation, не автоматический переход.

## Pre-flight / rulings

- User→store uses existing user lock; schema→store uses exact V8 fields/checks;
  store→future facade требует same-transaction completion и error mapping после commit.
- Runtime ACL уже deny-by-default для новых таблиц: на этой неактивной основе
  grant не расширяется. Реальные схемы проверяются Testcontainers/migrator role.
- Исходный дизайн и контракт уже reviewed/merged; новый brainstorming/approval цикл
  не требуется для ограниченной основы. Авторизацию не заменяем. Работа в нынешнем
  чистом checkout на отдельной feature branch, без нового worktree и без commit.
- Независимый review нашёл обход cooldown заранее созданными кандидатами: новый
  accepted_at под account lock отделён от initial created_at/expiry. Regression
  задержанных кандидатов наблюдал RED; исправление проверено полной suite: 318 PASS.
