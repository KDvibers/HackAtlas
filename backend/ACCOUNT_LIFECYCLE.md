# N2: закрытый lifecycle runtime

5 ноября 2026. HTTP/mail facade доступен только в явно включённом локальном
`dev/test + lifecycle-test` профиле. Обычный API0.1.1 прежний; strict0.2 не принят.
[Запуск/остановка](../infra/lifecycle-test/README.md),
[матрица, restore и ограничения](../docs/research/2026-11-05-account-lifecycle-n2.md).

V9 добавляет session version, credential_changed_at, source marker и owner-only
prepare/require restore functions. V1–V8 не изменяются. Активированная БД не стартует
в legacy mode. Старый snapshot требует явного --require-lifecycle-recovery, active
snapshot распознаётся автоматически. Quarantine/deletion review/approval обязательны.
Ниже сохранены сведения N1; её утверждения о неактивности относятся к default legacy,
а не к N2 test profile. Внешний rollout не входит в N2.

# Account lifecycle foundation N1

3 ноября 2026. Внутренняя основа после canonical PR #23; **lifecycle не включён**.
Канон остаётся 0.1.1, пять новых HTTP operations — planned. Старые Bearer/DTO/TTL,
admin auth, saved/import и закрытая регистрация сохраняют прежнее поведение.
[План](../docs/superpowers/plans/2026-11-03-account-lifecycle-foundation.md),
[совместимость](../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md).

## Данные V8

Миграция автоматически применяется Flyway при обычном migrate/старте приложения,
как предыдущие миграции. Real dev/production DB этим заданием не мигрировалась;
проверки выполняются только на собственных disposable PostgreSQL.

- user_account: email_verified=false, credential_version=0,
  password_reset_required=false, nullable email_delivery_address.
- Старые строки остаются unverified; исходный регистр email утрачен и не выдумывается.
  NULL delivery address блокирует создание challenge. Способ перехода старых beta
  accounts нужно определить до включения почты.
- Новая регистрация валидирует canonical email как раньше, сохраняет canonical для
  matching/Profile и stripped исходное написание отдельно для будущей доставки.
  Это не подтверждение владения почтой и не изменение правил сопоставления.
- email_action_challenge: hash случайного ID, user/purpose/version, HMAC кода,
  failed_attempts, timestamps. password_reset_grant: hash reset token, привязка к
  одному challenge/user/version, timestamps. FK CASCADE удаляет оба вида записей
  вместе с аккаунтом. В БД нет plaintext code/challengeId/resetToken.
- TTL: challenge 10 минут, grant 5 минут; в точке expiry proof уже недействителен.
  Миграция не сбрасывает пароли/сессии и не реализует forced recovery после restore.

## Внутренние компоненты

EmailActionCrypto использует штатный [Java JCA Mac/HmacSHA256](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html),
SecureRandom, SHA256 случайных identifiers и constant-time comparison MAC.
Короткий 8-digit code привязан к purpose/UUID/credential version/challengeId;
отдельный key защищает короткий код от перебора по одному read-only DB dump.
Key принимает constructor: ровно 32 байта, защитная копия, без config/default key.
Смена ключа делает прежние коды недействительными; issued high-entropy grants имеют
собственный короткий expiry. Новый deployment/key provider пока не подключён.

EmailActionStore использует JdbcTemplate и [user-first PostgreSQL row locks](https://www.postgresql.org/docs/17/explicit-locking.html):
тот же account lock, что у нынешних login/delete. Затем lock challenge/grant.
Проверка времени выполняется после ожидания account lock.
Cooldown использует accepted_at — момент принятия в хранилище под lock, не время
генерации кандидата и не HTTP 202. Задержанная задача не обходит интервал и не
продлевает заранее заданный expiresAt; original created_at/expiry сохраняются.
Resend допускается через 60 секунд, максимум 3 активных challenge на user/purpose/version;
новый запрос не отменяет старые коды/grants. Пять неверных attempts хранятся в БД.
Это внутренние ограничения; публичные IP/email admission limits ещё не реализованы.

**Композиция будущей facade:**

1. Открыть writable транзакцию на той же DataSource; без неё store откажет до SQL.
   Например, существующая Spring @Transactional facade либо
   [TransactionTemplate](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html).
2. Consume verification/reset grant возвращает Optional<VerifiedAction>, только
   UUID/version. Сам store не меняет verified/пароль и не выдаёт сессию.
3. После успешного consume выполнить изменение аккаунта и необходимую инвалидацию
   сессий/связанных proofs **в той же транзакции**. Exchange reset code уже атомарно
   создаёт grant вместе с consumption; rollback возвращает оба в прежнее состояние.
4. Invalid — обычное пустое значение. Сначала commit failed_attempts, **после выхода
   из транзакции** преобразовать outcome в HTTP 400. Исключение внутри общей
   транзакции откатит attempts — так будущую facade писать нельзя.

Кандидат сам по себе не резервирует версию аккаунта и не является правом на действие.
Будущий worker/facade обязан проверять актуальный account/version под тем же lock;
если ожидаемая версия уже была известна до queue, устаревшую задачу нужно отклонить.
Публичный reset ищет account только внутри job; timestamp кандидата не доказывает
его актуальность. Это обязательное условие следующего wiring этапа, jobs в N1 нет.

Crypto/store не зарегистрированы как Spring beans и не вызываются действующими
controllers/auth flows. Runtime роль БД не получила прав на новые таблицы:
нынешний afterMigrate allowlist оставляет их закрытыми. HMAC key не нужен для старта.
Индексы подготовлены для последующей retention cleanup; автоматической задачи нет.

## Проверка и следующий этап

Maven verify включает crypto tests, V7→V8 migration IT и real PostgreSQL store IT:
binding/key rotation, expiry/lock wait, постоянные attempts, concurrent consume/resend,
rollback, delete/recreate, no Bearer use, metadata реальной HTTP-регистрации.
Для полной команды сборки см. [backend README](README.md).

N1 не обеспечивает рабочую email verification/password recovery. Далее нужны facade,
admission/SMTP executor и adapter, endpoints/HTTP coverage, credential-version проверки
native/admin, restore cleanup/forced recovery и cleanup policy. Расширение контракта
и активация требуют готовности мобильного клиента; partial lifecycle не включать.
Ни signup, ни deployment этим этапом не открываются. Поведение public register 409
и известные release security blockers остаются в прежних границах.
