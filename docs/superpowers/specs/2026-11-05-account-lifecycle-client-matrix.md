# Account lifecycle: матрица интеграции клиента с закрытым N2

5 ноября 2026. **Предложение для согласования двух разработчиков, не принятый контракт.**
База: main `ded9404280bd479b591197a26703c1ee21ecf025`, merged PR #28.
Его main Backend CI `42066087861` завершился успешно.

Уточнение7ноября2026 после mobile PR31 и backend PR32: main `1563bef`.
Разделы10–11 распространяют правила на оба private stores и фиксируют открытые
legacy/rollout gates. Фактические [HTTP-примеры](../../examples/n2-http/README.md)
не являются мобильной реализацией или утверждением политики.

Каноническими остаются [OpenAPI0.1.1](../../../api/openapi.yaml) и
[семантика](../../../api/README.md). Этот документ не меняет API, профиль запуска,
backend/mobile код, канонические fixtures или правила production. Согласование порядка работ
и merge N2 не означают принятие strict0.2. Кандидат `0.2.0-test.1` используется
только в явно выбранной закрытой среде; отдельный PR потребуется для канонического0.2.

Источники: [совместимость](../../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md),
[дизайн](2026-11-01-account-lifecycle-design.md),
[N2 и его ограничения](../../research/2026-11-05-account-lifecycle-n2.md),
[локальный Mailpit-стенд](../../../infra/lifecycle-test/README.md).

## 1. Что уже подтверждено и что предлагается

Факты backend ниже относятся к закрытому N2. Действия клиента — предложение:
они ещё не реализованы/не приняты этим документом. Друг подтвердил необходимость
согласовать pending, ошибки, сессию/кеш и старые клиенты; guest saved сохраняются,
PASSWORD_RESET_REQUIRED относится только к login, любой403 не означает logout.

Для частного кеша pending рассмотрены два допустимых варианта:

| Вариант | Последствия |
|---|---|
| Скрывать и сохранять owner-scoped bytes до подтверждения — **рекомендация** | Сохраняет данные при временном ограничении. Нужны отдельный access-state и защита от поздних ответов; нельзя открывать кеш по одному cached flag |
| Удалять частный кеш при переходе в pending | Проще минимизировать сохранённые данные; придётся загружать их заново после подтверждения, пропадает локальная копия при отсутствии сети |

В дальнейшей матрице предложен первый вариант. Неверный код и обычный FORBIDDEN
не удаляют кеш. Отзыв сессии, logout и подтверждённое удаление аккаунта очищают
его в обоих вариантах. Это предложение нужно явно принять или поправить при review.

## 2. Состояния клиента и доверие

Имена состояний описательные: новые enum/API/capabilities endpoint здесь не вводятся.

| Состояние | Основание | Предлагаемое поведение в required test mode |
|---|---|---|
| Signed out | Нет текущего Bearer / завершён локальный выход | Публичный каталог, guest saved, login/register/recovery; частные данные скрыты |
| Unknown / checking | Secure store прочитан, но текущая сессия ещё не получила валидный Profile | Bearer сохранить; частные данные скрыть до проверки, guest доступен |
| Pending | Сервер вернул emailVerified=false или scoped403 EMAIL_NOT_VERIFIED | Pending-профиль, подтверждение, logout/delete; запрещённые личные операции и import остановить |
| Verified | Валидный Profile.emailVerified=true для текущей source/user/session generation | Доступны личные операции, явный import и owner-scoped кеш |
| Unsupported server / invalid response | В required mode поле отсутствует, null/не boolean, либо нужная операция недоступна | Сохранить сессию, частные данные скрыть; показать несовместимость, без fallback в legacy |
| Storage unavailable | Secure store/cache нельзя безопасно прочитать или очистить | Частные данные скрыть; повторить локальную проверку/очистку, не смешивать владельцев |

Восстановленный с диска Profile сам не доказывает verified. После cold start в
required mode нужен GET /me; без сети это Unknown с сохранёнными, но скрытыми private
bytes. Если в текущем процессе уже был успешный server check для той же сессии,
временный offline не отменяет Verified сам по себе: ранее загруженную копию можно
читать до локального expiry; сетевые записи недоступны. Это консервативная граница,
которую нужно согласовать: offline cold start не открывает private cache.
Серверный отзыв на другом устройстве нельзя обнаружить/стереть локально без сети.

## 3. Матрица операций N2

Все пути ниже относительно `/api/v1`. Pending имеет действующий Bearer; public
операции не становятся приватными. Указаны нормальные результаты и специфический
pending-отказ; общие ошибки перечислены отдельно в разделе4.

| Метод | Путь | Доступ pending | Нормальный ответ / отказ | Предлагаемое действие клиента |
|---|---|---|---|---|
| GET | /hackathons | Публичный | 200 | Каталог без проверки почты |
| GET | /hackathons/{id} | Публичный | 200 | Показать событие |
| GET | /metadata/filters | Публичный | 200 | Фильтры без входа |
| POST | /auth/register | Публичный, если dev signup включён | 201 | Принять Profile/token только после проверки ответа; false →pending |
| POST | /auth/login | Публичный | 200 | Принять Profile/token только после проверки ответа; false →pending |
| POST | /auth/logout | Разрешён | 204 | Локальный выход и очистка текущего private scope |
| GET | /me | Разрешён | 200 | Обновить truthful emailVerified; не продлевать TTL |
| PATCH | /me | Запрещён | 403 EMAIL_NOT_VERIFIED | Сохранить Bearer, открыть подтверждение |
| GET | /me/saved | Запрещён | 403 EMAIL_NOT_VERIFIED | Не читать private list, скрыть его кеш |
| PUT | /me/saved/{id} | Запрещён | 403 EMAIL_NOT_VERIFIED | Остановить запись/import; guest не стирать |
| DELETE | /me/saved/{id} | Запрещён | 403 EMAIL_NOT_VERIFIED | Не считать удаление выполненным |
| GET | /me/applications | Запрещён | 403 EMAIL_NOT_VERIFIED | Не читать private list, скрыть его кеш |
| PUT | /me/applications/{id} | Запрещён | 403 EMAIL_NOT_VERIFIED | Не записывать private state |
| DELETE | /me/applications/{id} | Запрещён | 403 EMAIL_NOT_VERIFIED | Не считать удаление выполненным |
| POST | /suggestions | Запрещён | 403 EMAIL_NOT_VERIFIED | Предложение не принято; предложить подтверждение |
| POST | /hackathons/{id}/reports | Запрещён | 403 EMAIL_NOT_VERIFIED | Жалоба не принята; предложить подтверждение |
| POST | /me/delete-account | Разрешён с паролем | 204 | Аккаунт удалён; очистить его session/private scope, guest сохранить |
| POST | /auth/email-verification/request | Разрешён, без тела | 202 /204 если уже verified | 202 →форма кода; 204 →GET /me, без вывода verified по одному статусу |
| POST | /auth/email-verification/confirm | Разрешён, тот же Bearer + challengeId/code | 204 | Очистить ввод кода; GET /me той же сессией |
| POST | /auth/password-reset/request | Публичный, клиент без Bearer | 202 | Один текст для существующего/неизвестного/удалённого адреса; доставка не обещана |
| POST | /auth/password-reset/verify | Публичный, клиент без Bearer | 200 | resetToken только в RAM; не устанавливать auth session |
| POST | /auth/password-reset/confirm | Публичный, клиент без Bearer | 204 | Очистить recovery secrets; перейти к обычному login, без autologin |

Итого22 операции;9 запрещены pending. Клиент блокирует их заранее, но серверный
guard остаётся источником решения. После email confirm прежний pending Bearer
сохраняет первоначальный expiresAt (30мин от выдачи). Получить новый30-дневный
verified Bearer можно обычным login; refresh endpoint отсутствует.

## 4. Статус + код + действие при отказе

Разбирать валидный Problem.code вместе с operation/status, не по локализованному
detail. Для диагностики достаточно requestId. Ошибка/успех применяется только к
той source/user/session generation, с которой был начат запрос.

| Где / HTTP | Код | Предлагаемое действие | Сессия и частный кеш |
|---|---|---|---|
| Login:401 | INVALID_CREDENTIALS | Ошибка email/пароля; повторный ввод | Не устанавливать новую сессию; чужую уже активную не очищать |
| Delete-account:401 | INVALID_CREDENTIALS | Неверный подтверждающий пароль; аккаунт не удалён | **Сохранить текущий Bearer и кеш**; не общий logout handler |
| Текущий защищённый запрос:401 | AUTH_REQUIRED | Токен отсутствует/истёк/отозван/version mismatch; нужен вход | Инвалидировать текущий scope, убрать Bearer и private cache; guest сохранить |
| Защищённый401 с неизвестным/невалидным Problem | Не интерпретировать как специальный code | Fail closed для этого текущего auth scope; не применять исключение неверного delete-password без валидного кода | Очистить только совпавший текущий scope; поздний ответ старой сессии игнорировать |
| Public reset:401 при ошибочно приложенном Bearer | AUTH_REQUIRED | Исправить разделение public client; recovery отправлять без Authorization | Сам по себе ответ public flow не очищает другую активную сессию |
| Public reset:401 без приложенного Bearer | Неожиданный отказ | Показать несовместимость среды/ошибку маршрутизации; не бесконечный retry | Не выводить из другого аккаунта |
| Login:403 | PASSWORD_RESET_REQUIRED | Старый пароль подошёл, но требуется email recovery после restore; auth token не выдаётся | Новую сессию не создавать; recovery начинается signed-out |
| 9 pending-guard операций из раздела3:403 | EMAIL_NOT_VERIFIED | Установить Pending для совпавшего scope, остановить private writes/import, предложить подтверждение; GET /me для сверки | Bearer и owner-scoped bytes сохранить, private UI скрыть; guest сохранить |
| Любой ожидаемый403 | FORBIDDEN | Отказ конкретной операции; например signup закрыт | Не logout, не менять Verified на Pending по одному статусу, кеш не удалять |
| Другой403 / неизвестный code | Не угадывать смысл | Сообщить отказ; без automatic logout. Неверный code/status/path считать нарушением контракта | Сессию сохранить; при невозможности подтвердить Profile private UI блокировать до проверки |
| Любая валидация:400 | VALIDATION_ERROR | Показать ошибку поля/формы | Сессию/кеш не очищать |
| Confirm email или verify reset:400 | EMAIL_ACTION_INVALID | Одинаковое сообщение для всех причин; очистить введённый code, оставить challenge в RAM для допустимого повтора/предложить resend | Не logout, не заявлять точную причину или остаток попыток |
| Confirm reset:400 | EMAIL_ACTION_INVALID | Grant непригоден/использован; очистить его и начать новый recovery | Не autologin и не logout другого аккаунта |
| Register:409 | EMAIL_ALREADY_EXISTS | Предложить login/recovery; нынешняя enumeration-семантика не меняется | Новую сессию не устанавливать; guest сохранить |
| Каталог/private resource:404 | NOT_FOUND | Событие/ресурс отсутствует; обновить соответствующие данные | Сессию не очищать; это не признак отсутствия lifecycle |
| Нужный lifecycle endpoint:404/501 | Ответ может быть вне кандидата | Required source несовместим; остановить новый flow, без legacy fallback | Bearer сохранить, private access не объявлять поддержанным |
| Любая квота:429 | RATE_LIMITED | Уважать Retry-After в секундах; не создавать retry loop | Bearer/кеш сохранить; TTL кода/токена не продлевается |
| Внутренняя ошибка:500 | INTERNAL_ERROR | Сообщить сбой с requestId | Нет вывода об expiry/неверной почте; сессию/кеш сохранить |
| Недоступность/полная очередь:503 | SERVICE_UNAVAILABLE | Сообщить недоступность; explicit retry с учётом стадии flow | Сессию/кеш сохранить; не обещать доставку |
| Сеть/timeout/offline, нет достоверного HTTP | Нет Problem.code | Не угадывать исход mutation; см.раздел6 | Сессию/кеш сохранить до самостоятельного local expiry; guest сохранить |

**Уточнение к прежней общей формулировке «401 = отзыв/expiry»:** delete-account
уже сейчас возвращает401 INVALID_CREDENTIALS при неверном пароле. Это не новая
ошибка N2 и не отзыв Bearer: [AccountDeletionService](../../../backend/src/main/java/com/hackatlas/auth/AccountDeletionService.java)
и [ModerationApiIT](../../../backend/src/test/java/com/hackatlas/ModerationApiIT.java),
`wrongOrTrimmedPasswordCannotDeleteAndTheErrorIsGeneric`, подтверждают последующий200 GET /me.
Менять backend401 на400 этим предложением не разрешается.
PASSWORD_RESET_REQUIRED вне login не является согласованным поведением; его нельзя
глобально трактовать как требование logout/recovery на всех protected операциях.
403 EMAIL_NOT_VERIFIED на GET /me, logout/delete-account или verification endpoints
также не объявлен кандидатом: это нарушение матрицы, не новый pending guard.

## 5. Сессия, частный кеш и guest

| Событие | Предложение |
|---|---|
| 403 EMAIL_NOT_VERIFIED | Скрыть private UI, отменить private writes/import; сохранить Bearer и bytes того же владельца до сверки/подтверждения |
| 400 invalid code, обычный403,429,5xx,transport error | Не очищать auth/private/guest. Для неподтверждённого доступа private UI остаётся скрытым |
| 401 AUTH_REQUIRED для текущего private scope, local expiry | Сразу убрать private данные из памяти/UI, отменить задачи; очистить SecureTokenStore и частное хранилище, перейти к входу |
| Explicit logout, включая offline/ошибку revoke | Очистить локальную сессию/private. При сетевом сбое серверный revoke не подтверждён — не сообщать обратное |
| Delete-account204 | Очистить сессию/private/recovery context этого пользователя; account→guest conversion не выполнять |
| Смена пользователя, apiBaseUrl или явно выбранного режима | Инвалидировать старую generation/операции и private UI; очистить старый активный private/session scope перед новой активацией. Старый Bearer не отправлять новому source |
| Новая успешная login/register сессия, даже того же UUID | Новая generation; очистить/загрузить заново private snapshot, не доверять прежнему emailVerified или ответам старых запросов |
| Перезапуск приложения / background | Не восстанавливать OTP/code/newPassword/resetToken из settings/rememberSaveable/cache. Reset grant и введённые секреты сбрасывать при background; challengeId можно держать только в RAM до TTL/выхода из flow |
| Ошибка физической очистки secure store/cache | Скрыть private UI и заблокировать его новую активацию до успешной очистки; не заявлять об удалении, которого не было |

Любые success/error callbacks, cache writes и import completions сверяются с
`(apiBaseUrl, userId, local session generation)` и явным режимом доступа. Generation
меняется при смене/инвалидации сессии; expiresAt одного недостаточно (два login могут
получить одинаковое время expiry). Поздний401 не очищает новую сессию, поздний200/204
не возвращает её private данные. Это требование к будущему mobile, не новый API field.

У public recovery нет авторизованного userId: для него нужен отдельный RAM flow
generation, привязанный к source. Выход/background/новый recovery инвалидируют его;
поздний ответ verify не восстанавливает resetToken после закрытия flow. Верификация
почты также привязана к исходной native session и своему challenge, не к текущему
экрану, который мог уже переключиться.

Guest saved **сохраняются при всех перечисленных auth событиях** в собственном
source-scoped хранилище. Private entries не превращаются в guest автоматически.
Guest import — только явное копирование после подтверждённого Verified; partial
failure не стирает guest bookmarks и не запускает безусловный повтор всего batch.

## 6. Password reset и неизвестный результат

Нативный recovery предлагается оставить signed-out, как в исходном дизайне. Из
активного аккаунта вход в него требует явного локального выхода с понятным пользователю
следствием очистки private cache; кнопка «забыл пароль» не очищает его скрытно.
Public recovery transport не прикладывает Bearer. На204 confirm reset в таком flow
нет активной native сессии, которую нужно устанавливать/обновлять: нужен новый login.

Backend по204 отзывает все native sessions целевого аккаунта, делает прежний admin
cookie непригодным по version, инвалидирует прочие challenges/grants. На другом
устройстве cleanup происходит при первом текущем401/local expiry/explicit logout;
push notification об отзыве не реализован. Offline-клиент не способен гарантировать
мгновенное обнаружение reset. Активный другой аккаунт B не очищается из-за recovery A:
backend определяет target по grant; наличие Bearer B в ошибочном API-клиенте не меняет target.

Не сохранять новый пароль ради autologin. Не повторять автоматически consuming POST:

- После потерянного ответа email-confirm проверить GET /me с той же сессией.
- После потерянного ответа reset-verify grant может быть выдан и утрачен; нужен
  новый запрос кода, а не обещание повторного обмена тем же challenge.
- После потерянного ответа reset-confirm пароль мог уже измениться: предложить
  обычный login с новым паролем либо новый recovery; не объявлять успех/неуспех без проверки.
- После потерянного202 письмо могло отправиться; дождаться разрешённого resend,
  получить новый challenge. Старые письма не становятся подтверждением успешного reset.

## 7. Старые клиенты и включение ограничений

| Клиент / среда | Правило предложения |
|---|---|
| Текущий клиент + canonical legacy0.1.1 server | Нынешние сценарии; отсутствующий flag не значит verified. Не требовать planned endpoints |
| Новый клиент + явно разрешённая legacy среда | Сохранить прежнее поведение без verified badge по missing flag и без lifecycle calls; никаких автоматических догадок о режиме |
| Новый клиент + явно выбранная required test среда N2 | Missing flag →unsupported; false →pending; true →verified; present null/не boolean →invalid response |
| Старый клиент + active required source | Совместимость не обещается. Не включать enforcement на общем source, пока аудитория не перешла и оба разработчика не приняли rollout |

Режим задаётся явно для deployment/client source. Ни optional field, ни metadata
OpenAPI, ни отдельный403/404 не являются capability negotiation. Не добавляется
автоматический fallback. Обычный API0.1.1/source остаётся legacy; закрытый N2 —
отдельная выделенная среда. Активированная lifecycle БД не запускается обратно
в legacy mode, старые и новые server instances не смешиваются на required source.

Для включения новых ограничений на общем source нужны **все** условия:

1. Оба разработчика явно приняли эту матрицу с правками и план перехода старых клиентов.
2. Mobile реализовал состояния/ошибки/cache isolation/secret cleanup; тесты не ограничиваются DTO parsing.
3. Закрытый E2E с Mailpit прошёл; записаны реальные Android/iOS результаты либо конкретный пробел проверки.
4. Отдельный PR принимает канонический0.2: bool/response maps/fixtures/validators с обеих сторон, без расширения общего Problem произвольными кодами.
5. Отдельно разрешён контролируемый deployment/cutover; определены версия клиента/канал обновления и поведение исключённых старых клиентов. До этого enforcement остаётся только N2.

Main CI и merge backend не заменяют ни один из этих approval/device gates.
Внешний SMTP, инфраструктура, retention cleanup и public signup — отдельные задачи.

## 8. Что требуется от мобильной реализации

Текущий [ProfileDto](../../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/ContractDtos.kt)
не содержит emailVerified. [AuthSessionClient](../../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/auth/AuthSessionClient.kt)
сохраняет лишь status/Retry-After в AuthError.Http; requireProtected очищает auth на
любой401. При добавлении delete-account нельзя пропустить через него401 INVALID_CREDENTIALS
без отдельного разбора. Generic403 не предоставляет пока pending/recovery UX.

[AccountCache](../../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/saved/AccountCache.kt)
уже разделён по apiBaseUrl/userId, а
[AccountSavedStore](../../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/saved/AccountSavedStore.kt)
использует epoch для старых результатов. Его функция verified(user) сейчас означает
Authenticated/error-free, **не** emailVerified: это не готовый guard для pending/import.
Нужно дополнить клиент, сохранив существующую изоляцию guest/private; здесь он не меняется.

## 9. Совместная приёмка после согласования

- [ ] Register pending; все9 private guards; GET /me/logout/delete/verification доступны.
- [ ] Неверный delete-password →401 INVALID_CREDENTIALS, затем GET /me200 без logout/cache purge.
- [ ] Login401 не удаляет другую активную identity; protected401 удаляет только текущий совпавший scope.
- [ ] Pending403 скрывает private, сохраняет Bearer/guest; FORBIDDEN403 не вызывает logout.
- [ ] Неверный/истёкший OTP400, quota429 с Retry-After,503/timeout без потери сессии.
- [ ] Email confirm204 →GET /me true, тот же expiresAt, без refresh/autologin.
- [ ] Reset с Mailpit;204 без autologin, старые native tokens401/admin cookie недействителен, новый login работает.
- [ ] Recovery A с ошибочно приложенным Bearer B не меняет password/session/cache B.
- [ ] Два пользователя/source; поздние401 и200/204 после смены generation не меняют новый scope.
- [ ] Guest import pending запрещён; после verified явная копия, partial failure сохраняет guest.
- [ ] Required mode + missing/null/wrong flag/отсутствующий endpoint не переключается в legacy.
- [ ] Offline cold start, expiry, background/kill, ошибки storage cleanup; private не появляется из cached flag.
- [ ] Потерянные ответы consuming POST не приводят к автоматическим повторам/ложному успеху.
- [ ] Поздний reset grant после background/смены recovery flow не восстанавливает секреты.
- [ ] Зафиксированы фактические платформы/device results и результат CI согласованного PR.
- [ ] Обе предложенные политики отдельно утверждены: pending bytes сохранить/скрыть;
  required cold start offline скрывает private до server check. Legacy offline прежний.
- [ ] Все пункты pending/401/expiry/logout/account-source switch выполнены для
  account-saved И account-applications, включая производные UI из раздела10.

**К согласованию:** pending-кеш скрывать/сохранять либо удалять; conservative offline
cold start; signed-out recovery; исключение delete-password401; scope/generation cleanup;
точная матрица и gates перехода. Принятие этой матрицы разрешает планировать клиентскую
интеграцию, но само по себе не активирует strict0.2 и не разрешает deployment.

## 10. Уточнение после PR31: два private stores и производный UI

**Статус: предложение для совместного утверждения.** В PR31 добавлены
ApplicationCache/ApplicationStore и личная отметка в DetailScreen; они ещё не
различают emailVerified. Общий epoch/identity механизм не является проверкой почты.
Saved и applications не превращаются в guest ни при одном auth transition.
Ниже предлагается клиентская обработка тех же9 серверных guards; новых guards нет.

| Поверхность | Предлагаемое поведение required pending/Unknown | Cleanup при текущем protected401, local expiry, logout, смене source/user |
|---|---|---|
| account-saved: membership + полные snapshots | Скрыть private entries; остановить refresh/write/import; guest store доступен отдельно | Очистить private document/память, owner routes и derived state; guest не изменять |
| account-applications: marks + timestamps | Скрыть marks/counts и private cache; не выполнять GET/PUT/DELETE applications | Очистить applications document/память и все производные marks/counts |
| Detail из публичного каталога | Публичное событие доступно; private bookmark/mark отражают pending/Unknown, без старых account badges и mutation | Публичные данные можно оставить; private controls/state старой identity очистить |
| Detail из account-saved snapshot | Скрыть account snapshot до проверки; не выдавать его за guest/public fetch | Закрыть private route; допустим отдельный публичный GET details, если событие доступно |
| Календарь сохранённого | Не строить события из скрытых account-saved; отдельный гостевой календарь сохраняется | Убрать account-derived entries/selection/detail route, не менять guest snapshot |
| Профиль и его private counters | GET/me и pending-профиль разрешены; не считать старые cached counts/marks доказательством доступа | Очистить private производные/старый профиль; поздний ответ не восстанавливает их |
| Editor + несохранённый draft | PATCH/me запрещён; pending/Unknown не открывает editing; draft остаётся только в совпавшем private RAM scope и не отображается | Удалить private draft/RAM и закрыть editor; не отправлять его после смены generation |
| Guest import, captured candidates/progress | Только Verified текущей generation и явное подтверждение; pending/Unknown не запускает import | Отменить captured private intent/progress; partial server success не стирает guest |

Cleanup в таблице относится к **совпавшему текущему scope** и правилам раздела4.
Delete-password401 INVALID_CREDENTIALS — исключение: сохранить действующий Bearer,
оба private stores и видимый UI, показать ошибку формы. Login401 не очищает другую
активную identity. Поздний401/200/204 старой generation не очищает и не наполняет новую.
Текущий валидный403 EMAIL_NOT_VERIFIED сохраняет Bearer/guest: hidden private bytes
предлагается сохранить, а не выполнять cleanup как при отзыве. Generic403 не logout.

Два состояния на диске недостаточны: проверять также saveable/navigation state,
detail controls, profile counters, editor draft и captured import callbacks.
Завершившийся private ответ принимается только текущей source/user/session generation;
таймеры/корутины старого владельца не получают право записи после переключения.
Публичный Profile может содержать counts: таблица задаёт клиентский UX, не новый
серверный фильтр Profile или десятый guard.

### Два отдельных продуктовых решения — пока НЕ утверждены

| Решение | Предложение для required source | Почему требуется явное согласование |
|---|---|---|
| Pending bytes | Сохранить owner-scoped saved/applications bytes, скрыть до server-verified | Альтернатива — удалить bytes. Скрытие UI не равно удалению с устройства |
| Cold start offline | Required начинает с Unknown; скрыть оба private stores до GET/me | Это ухудшает принятый PR31 offline read-only cold start; согласие на N2 не означает согласие на эту потерю доступности |

Legacy0.1.1 сохраняет уже принятый offline read-only сценарий saved И applications.
Для предлагаемого required поведения после успешного server check в текущем процессе
временный offline допускает read-only до local expiry; новый процесс снова Unknown.
Missing/null/неверный flag или недоступная planned операция не включает legacy fallback.
Ни документы, ни HTTP fixtures не переключают конфигурацию клиента/сервера.

## 11. Legacy и переход старых клиентов — ещё не выбранный rollout

[Предложение по legacy](2026-11-07-legacy-account-recovery-design.md) объясняет
NULL delivery address и restore PASSWORD_RESET_REQUIRED: реализованного recovery
для такого аккаунта нет. Успешный SMTP smoke нового аккаунта не закрывает этот gap.

Minimum client version/build пока **не выбран**. До общего strict source нужны
обе отдельно утверждённые политики, legacy admission/recovery plan, реализация
обоих private stores и полный закрытый E2E. Затем оба разработчика фиксируют
конкретные Android/iOS build identifiers, канал/срок обновления и судьбу старой
аудитории. Нативный iOS требует отдельного Mac-прогона; Android PASS не заменяет его.
Канонический0.2 и cutover принимаются отдельными PR/разрешением. Заголовок версии
или User-Agent не заменяет серверную авторизацию; нового negotiation endpoint нет.
