# Подтверждение почты и восстановление пароля: предложение

Дата: 1 ноября 2026. **Целевой дизайн из merged PR #22; реализация этим документом не разрешена.**

Поручена только подготовка canonical 0.1.1. Её [план совместимости](../../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md)
использует optional flag, planned endpoints и LifecycleProblem без activation.
Required flag/новые ошибки старых маршрутов ниже относятся к будущему 0.2.

База обсуждения: main `991be66123b5f791d0ee9a33302b9d681170e345`, включая
PR #19 и мобильный PR #20 с экранами авторизации. Единственный действующий контракт
по-прежнему [Starter](../../../api/openapi.yaml) и [его семантика](../../../api/README.md).
[Краткая передача другу и JSON Patch](../../proposals/account-lifecycle/README.md)
описывают кандидат 0.2.0-proposal.1; это не новая действующая спецификация.

## Задача и ограничения

Перед публичной регистрацией ROADMAP требует подтверждать владение почтой и
восстанавливать забытый пароль. На базе исходного предложения register выдавал
полноценную сессию, Profile не содержал verification state, reset отсутствовал.
Мобильные core и UI использовали register → AuthResponse и register/login/logout/me;
saved sync тогда ещё не был подключён. В нынешнем main account saved/guest copy уже
подключены PR #21; это учтено в переходном плане совместимости выше.

Сохраняются Java 21/Spring Boot/PostgreSQL, opaque Bearer и абсолютный expiresAt.
Нет JWT, refresh, OAuth/IdP, смены email, passwordless login, MFA, новых социальных
сценариев, deployment или выбора платного mail provider. Подтверждение почты
не называется MFA. В этой задаче создаются только документы и кандидат контракта.

## Варианты

| Решение | Сложность / стоимость | Поддержка и совместимость |
|---|---|---|
| A. Ограниченная Bearer-сессия до подтверждения | Средняя: единый verification guard и новое состояние клиента; обычная стоимость писем | Сохраняет форму register/login. Требует проверить каждый закрытый маршрут. **Рекомендация автора** |
| B. Почта подтверждается до создания обычной сессии | Выше для текущего клиента: новый двухшаговый registration flow и DTO; обычная стоимость писем | Меньше pending-session правил, но ломает нынешний register → AuthResponse. Разумно выбрать, если команда готова пересобрать этот flow |

Для писем рассмотрены: **8 цифр** и длинный случайный token в HTTPS-ссылке.
Код удобен в текущем Android/iOS приложении без домена/deep links, но требует
строгого ограничения попыток и отдельного секрета HMAC. Длинный token проще защищать
от перебора, но добавляет link handling, домен и browser/app fallback. Предлагается
код; если друг выбирает ссылки, этот кандидат пересматривается до реализации.
Это инженерный выбор автора под текущие условия, не требование OWASP.

## Рекомендуемая семантика A

### Регистрация и подтверждение

1. Register по-прежнему принимает email/password/displayName и возвращает 201
   AuthResponse. Новый обязательный `user.emailVerified=false`; письмо автоматически
   из транзакции регистрации не отправляется.
2. Login с правильным паролем неподтверждённого аккаунта возвращает 200 с тем же
   признаком. Предлагаемый TTL pending Bearer — 30 минут, без продления/refresh.
   После expiry нужен обычный login. Активные verified sessions сохраняют нынешний TTL.
3. Pending Bearer разрешает **только** GET /me, logout, delete-account с паролем,
   request/confirm email verification. Публичный каталог по-прежнему доступен гостю.
   PATCH /me, account saved/applications, suggestions/reports и admin запрещены:
   403 `EMAIL_NOT_VERIFIED`. Блокируется и чтение, и запись; guest saved остаются локальными.
4. Request verification берёт адрес из principal, не из тела. 202 возвращает challenge;
   если почта уже подтверждена — 204 без нового письма.
5. Confirm требует **и Bearer того же аккаунта, и challengeId/code из письма**.
   Код привязан к user/purpose/credential version, не к одному устройству. Просто
   владения чужим challenge недостаточно. Успех 204 → свежий GET /me даёт true.
6. Флаг проверяется на сервере по актуальному состоянию. JWT claims/клиентский bool
   не являются источником прав. Старая pending-сессия не получает новый срок жизни.

Привязка подтверждения к уже вошедшему аккаунту важна: нельзя активировать созданный
злоумышленником аккаунт с известным ему паролем одним случайным нажатием получателя
на письмо. Получатель, не знающий пароль такого аккаунта, использует reset: после
доказательства владения почтой он задаёт свой пароль, а прежние сессии отзываются.

### Восстановление пароля

1. Request принимает email через публичный клиент **без Bearer**. Для корректного
   адреса один и тот же 202/challenge shape независимо от существования аккаунта.
   202 означает принятие запроса к обработке, не обещание доставки письма.
2. Verify принимает challengeId и code; после правильного кода возвращает короткий
   `PasswordResetGrant` с новым случайным resetToken. Challenge одноразово потребляется.
3. ResetToken разрешает только confirm нового пароля. Он не Bearer session, не refresh
   token, не подходит к /me и не продлевает авторизацию. Срок — 5 минут, один успешный reset.
4. Confirm атомарно меняет password hash, повышает credential version, отзывает все
   Bearer-сессии и browser admin-доступы, аннулирует остальные challenges/grants.
   Если email был unverified — после этого fresh email proof и нового пароля он true.
5. Успех 204, без AuthResponse и автоматического входа; пользователь входит заново.
   Уведомление о смене пароля направляется только на адрес аккаунта, без пароля/кодов.

Целевой user для reset определяется только записью grant, а не principal, email или
userId из confirm body. Даже случайно приложенный валидный Bearer другого аккаунта
не должен переадресовать reset этому principal. Клиент всё равно не прикладывает Bearer.

Восстановление не возвращает удалённый аккаунт. По неизвестному/удалённому email request
выглядит одинаково, но действующего кода/grant для него нет. Повторное создание того же
email имеет новый user UUID: старые действия не подходят. Запрос reset сам по себе
не блокирует login, не отзывает сессии и не меняет флаг подтверждения.

### Коды, повторы и ошибки

- Code — строка из **8 ASCII цифр**, включая ведущие нули; CSPRNG, TTL **10 минут**,
  максимум **5 неверных попыток на challenge**, счётчик хранится в PostgreSQL.
- ChallengeId и resetToken — независимые 256-bit случайные значения, Base64URL
  без padding (43 символа). В DB только SHA256 этих высокоэнтропийных значений.
- Короткий код не хранить как простой SHA256: использовать HMAC-SHA256 с отдельным
  server key вне DB/backups; привязать к purpose/challenge/user/credential version,
  сравнивать constant time. Потеря/смена key аннулирует pending codes, не пароли.
- Resend — новый challenge после 60 секунд. **Публичный request не аннулирует ранее
  выданные коды/grants**: иначе посторонний мог бы постоянно срывать восстановление.
  Предлагается максимум 3 активных challenges на user/purpose; успешное действие
  аннулирует связанные старые. Email и UI должны помогать сопоставить письмо с
  текущим challenge (например, одинаковая короткая метка из его последних 6 символов).
- Стартовые request limits: IP 10/15 минут, normalized email 5/час и cooldown 60 секунд;
  verify IP 30/15 минут плюс постоянные 5 попыток challenge. Это отдельные buckets,
  а не расход login quota. Одинаковые request buckets применяются к неизвестным адресам.
- При достижении внутреннего лимита активных записей новый публичный request остаётся
  generic 202 без нового кода. Не выдавать account-specific 429, раскрывающий наличие
  аккаунта; 429 допустим от одинакового для всех request limiter. Retry-After обязателен.
- Неверный/истёкший/исчерпанный/чужой/повторный код или grant → 400
  `EMAIL_ACTION_INVALID`, без различения причины. Некорректная форма → прежний
  `VALIDATION_ERROR`. 401 — только настоящая недействительность приложенного Bearer,
  чтобы не заставлять мобильный auth core удалять сессию из-за опечатки в коде.
- Конкурентное потребление: одна транзакция выигрывает, остальные получают generic 400.
  Неудачная попытка должна **зафиксировать счётчик**, а не откатить его исключением.
  Автоматические повторы POST после timeout не выполнять; проверять /me или начинать
  новый challenge, если результат verify/grant потерян.

Точные лимиты — предложение для совместного review и последующей проверки нагрузки.
Не разрешать исчезновение attempts после restart или обход лимитов сменой purpose.

## Отправка писем и серверная архитектура

Внутри монолита: отдельный lifecycle service, PostgreSQL для challenges/grants,
современный существующий PasswordEncoder и будущий Spring mail adapter. SMTP provider,
sender/domain, ключ, секреты и точные версии зависимости ещё не выбраны/не установлены.

Рассмотрены два способа доставки:

| Вариант | Последствия |
|---|---|
| Ограниченный executor в процессе | Меньше инфраструктуры; после restart письмо/задача могут потеряться. Возможен resend. **Предлагается для первого этапа** |
| Durable PostgreSQL mail jobs | Восстановление задач и retries надёжнее, но plaintext code/token в таблице недопустим: нужны шифрование payload, ключи, worker, cleanup. Отдельное расширение, если такая гарантия нужна |

Рекомендуемый request сначала проходит валидацию/общий admission control, получает
challengeId/deadline, резервирует место в bounded executor и возвращает generic 202.
В reset request DB lookup выполняется в job, не создаёт ветку времени ответа по
наличию email. Job сохраняет challenge до SMTP send, проверяет срок/account version
и актуальность аккаунта. Полная очередь/отсутствие общей конфигурации → 503 для всех.
После ACK ошибки DB/SMTP не меняют уже отданный 202; нужны безопасные счётчики/alert,
resend и честный текст «Если отправка возможна…». Это **не durable доставка**.

Code существует в памяти задачи и письме, не в DB/outbox/logs. SMTP acceptance
не равно доставке во входящие. Рестарт/переполнение/отложенная почта — обязательные
проверки; нельзя рисовать фиктивный успех отправки. Before-public приёмка должна
включать настоящее письмо в контролируемый inbox, а не только mock SMTP.
Reset notification после успешной DB-транзакции также best effort: её сбой не
откатывает смену пароля и не выдаёт пользователю ложную ошибку «пароль не изменён».

Нужны HTTPS, no-store для всех lifecycle ответов и отдельные request-body redactions.
Email, пароли, коды, grants и Bearer не попадают в логи/analytics/crash reports.
Сравнение email пока сохраняем как в AuthService: strip + lowercase ROOT, без удаления
точек/plus-aliases. Для новых accounts предлагается дополнительно сохранять исходный
адрес без внешних пробелов как delivery address: SMTP всегда отправляет на сохранённый
адрес, а не на email, произвольно переданный в reset request. Иначе lowercasing может
направить письмо не в тот mailbox у case-sensitive provider. Profile.email пока
сохраняет нынешнее canonical представление; matching policy не меняется молча.
У старых строк исходный регистр утрачен: его нельзя выдумать или объявить адрес
verified из факта существования account. Перед публичным допуском нужны согласованная
миграция/повторная проверка закрытых beta accounts. Unicode/IDN и изменение правил
сопоставления требуют отдельной оценки коллизий, а не автоматической нормализации.

## Сессии, хранение и восстановление

- Новые таблицы/поля только будущими миграциями. FK к user с CASCADE; никакого
  автоматического признания старых пользователей verified. Dev/test fixtures
  задают verified явно; старые реальные адреса требуют доказательства владения.
- Credential version проверяется для native и admin. `AdminPrincipal` сейчас хранит
  только userId; будущая доработка должна сравнивать версию при каждом admin request
  и внутри изменяющей транзакции. Нельзя отозвать только Bearer и оставить старую cookie.
- Login/reset/delete сериализуются одним account lock. Отзыв действует на следующую
  проверку; уже авторизованный in-flight request может закончиться. Чувствительные
  admin mutations повторно проверяют account/version внутри транзакции.
- Удаление аккаунта очищает lifecycle rows и отменяет ещё не отправленные задачи;
  уже принятый SMTP message отозвать невозможно, но его код больше не действует.
- Предлагаемый cleanup: consumed/expired challenge/grant хранить не дольше 24 часов
  после expiry, без plaintext credentials. Это стартовая policy на согласование,
  не установленная автоматическая задача. Минимальный audit без free text/email/code;
  его retention согласуется с общей privacy policy.

### Старый backup не должен возвращать старый пароль

Существующая deletion history не содержит password resets. Отзыв sessions при restore
не мешает войти с password hash из старого snapshot. **Предлагается fail-closed правило:**

1. После restore все challenges/grants очищены, mail tasks не возобновляются.
2. До отдельного approve всем восстановленным accounts ставится явный
   `password_reset_required=true`; нельзя выводить это из timestamp.
3. Login с подходящим старым паролем возвращает 403 `PASSWORD_RESET_REQUIRED`,
   без Bearer. Старые admin credentials также не открывают browser session.
4. Только успешный новый email reset с новым паролем снимает этот флаг. Простое
   email confirmation его не снимает. Восстановленные пользователи проходят recovery
   прежде доступа; это намеренная цена отсутствия независимой password-change history.
5. Restore/upgrade старых V6/V7 snapshots и approve должны проверять наличие новой
   схемы и выполненную подготовку; missing guard → карантин. Однократная подготовка
   привязана к restoreId, чтобы обычный повторный migrate не блокировал уже восстановившихся.

Это существенное изменение эксплуатации, **включено в список решений для друга**.
В будущих тестах прежний smoke-login старым паролем заменяется mail recovery;
миграции и restore scripts сейчас не меняются. Если команда не принимает forced reset,
до реализации потребуется иной достоверный независимый учёт смен паролей.

## Совместимость и работа mobile

`Profile.additionalProperties=false` и enum Problem закрытые. Поэтому нельзя
расширить Profile через allOf поверх прежней схемы и объявить изменение безопасным.
Кандидат реально добавляет обязательный `emailVerified`, три Problem codes, 403 для
части существующих операций и пять новых endpoints. Это coordinated contract change,
**не полностью обратно совместимое обновление**, несмотря на прежние URL `/api/v1`.

Mobile нужно: распознавать pending verification, сохранить Bearer в native secure
store, показывать профиль/код вместо полного account flow, не начинать saved import
до true; после confirm обновлять /me. Public recovery использует отдельный клиент
без Authorization, resetToken живёт только в памяти. Вход в recovery — из signed-out
состояния; смена аккаунта не переносит private/guest snapshots автоматически.
Коды/новый пароль не сохранять в rememberSaveable/settings. Code — строка, не Int;
пароль не trim, правила 12–256 символов как RegisterRequest; подтверждение пароля
дважды — в UI, API принимает один newPassword. При 429 учитывать Retry-After.

Старые клиенты/старый сервер не объявляются совместимыми через default true или
silent fallback. Нужен согласованный rollout на закрытой среде; production signup
остаётся закрытым до всех условий ROADMAP. Существующий register 409
EMAIL_ALREADY_EXISTS пока сохраняется и раскрывает существование email: generic
reset не устраняет эту старую границу приватности. Изменение этого поведения
требует отдельного решения в рамках совместного review, не скрытой подмены ответа.

## Что согласовать до реализации

1. Выбираем A с pending Bearer и указанным allowlist или более крупный B?
2. Принимаем 8-значные коды/TTL/attempts и пятишаговый API ниже либо выбираем ссылки?
3. Принимаем reset без autologin и с отзывом native + admin sessions?
4. Принимаем forced email reset после восстановления старого backup?
5. Достаточна ли первая best-effort доставка с resend, или нужен durable mail-job scope?
6. Подтверждены ли mobile DTO/error/state изменения и rollout, включая старые accounts
   и сохранённый register 409? SMTP provider и реальные настройки выбираются отдельно.

После ответа обеих сторон обновить этот дизайн, затем отдельным contract PR
изменять canonical OpenAPI/README/fixtures. Только после этого — implementation plan
и явно порученная реализация. Никаких новых endpoint/писем/миграций пока нет.

## Будущая приёмка (сейчас не выполнена)

Pending allowlist на каждом закрытом маршруте; correct/wrong/expired/replayed codes;
счётчики через restart/rollback; cross-user/purpose/credential version; duplicate clicks;
enumeration по body/status/latency; queue full/DB/SMTP failures; выдача grant без Bearer;
конкурентные reset/login/delete; отзыв всех cookie/Bearer; backup rollback старого пароля;
сохранение карантина V6/V7; leading zeros, offline/background/logout и native secret
storage на mobile. Проверять реальный SMTP/inbox и Android/iPhone; только schema checks
и mocks не закрывают эти условия.

## Основания

- [OWASP: Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html):
  общие ответы, одноразовые ограниченные по времени секреты, throttling, уведомление
  и обычный login после сброса. TTL/число попыток в этом предложении выбраны автором.
- [OWASP: Email verification](https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html):
  владение адресом до account use, нормализация, приватность и logging boundaries.
- [Starter](../../../api/README.md), [ROADMAP](../../ROADMAP.md), текущие AuthService,
  AdminPrincipal/AdminAccessService и restore helpers. Forced reset после backup —
  вывод автора из существующей схемы, не готовая гарантия текущего backend.
