# Совместимость account lifecycle: 0.1.0 → 0.1.1 → целевой 0.2

Исходная версия1ноября2026: main `7f6795530489e1be409c9457d10d83f4e5645761`,
mobile PR21. Уточнение7ноября: main `1563bef`, включая mobile PR31 и локальный
прогон PR32. Канонический API **0.1.1** прежний; закрытый N2 — отдельный кандидат
**0.2.0-test.1**. Текущее уточнение меняет документы/тестовые HTTP-примеры,
не авторизацию, runtime, SQL, mobile или канонические схемы/fixtures.

Подробные [правила клиента](../docs/superpowers/specs/2026-11-05-account-lifecycle-client-matrix.md)
ещё требуют совместного утверждения. Merge документов и успешный backend CI не
принимают pending bytes/offline policy, minimum client version или strict rollout.

## Переходный 0.1.1

- Прежние request schemas, AuthResponse envelope, Bearer, response status maps и
  9 кодов Problem сохранены. JWT/refresh не добавлены.
- Profile.emailVerified — optional non-null boolean без default. Missing = UNKNOWN,
  а не true/false. Null/строка/число невалидны; старые обязательные поля остаются.
- Пять новых POST имеют отдельную фазу ACCOUNT_LIFECYCLE и статус planned.
  Metadata контракта не является runtime capabilities, поле само не включает feature.
- LifecycleProblem изолирует будущие коды. Старые error refs/403 не расширяются до
  отдельного активационного изменения. Метафлаги future guards не меняют авторизацию.
- Шесть legacy examples и manifest остаются: mobile parser test ожидает ровно 6.
  Nested manifest lifecycle проверяется отдельно, не требует изменения mobile tests.

## Нынешний клиент и границы доказательства

ContractDtos.kt использует ignoreUnknownKeys=true; ProfileDto пока не содержит флага,
ProblemDto.code — String. Replay неизменённых Kotlin-исходников проверяет profile
без флага/false/true и новые code strings. Это **JVM-десериализация**, не поддержка
pending/OTP/reset UI. AuthSessionClient очищает сессию на protected 401; прочие ошибки
пока generic Http. Поэтому разобрать JSON не означает уметь пройти новый сценарий.

Saved/import уже подключён (PR21), account-applications добавлен PR31. Оба private
stores пока не различают emailVerified; понятие verified в store означает прежнюю
проверку аутентификации. Legacy0.1.1 сохраняет принятый offline read-only сценарий
обоих stores и подтверждаемый guest copy. В закрытом N2 сервер уже блокирует9
pending-операций, но мобильные состояния/UX для этого не реализованы.

Предложение для required source: pending bytes сохранить и скрыть для ОБОИХ stores,
required cold start offline скрывает private до server check. Это два отдельных
неутверждённых продуктовых решения; второе меняет принятый mobile offline-сценарий.
Не переносить их в legacy автоматически. Guest bytes не стирать при partial failure,
private cache не становится guest. Cached identity/profile не доказывают verified.

## Матрица

| Клиент | Нынешний backend без lifecycle | Будущий backend с активным lifecycle |
|---|---|---|
| Нынешний | Прежние сценарии работают, missing flag не подтверждает почту | Pending/recovery не поддержаны; enforcement для такой аудитории не включать |
| Обновлённый, явно разрешённая закрытая legacy среда | UNKNOWN, прежняя приватная работа без verified badge и вызовов planned API | Переключить среду на обязательный режим; auto-fallback не оставлять |
| Обновлённый, EMAIL_VERIFICATION_REQUIRED | Missing flag/operations → unsupported server, личные функции fail closed | Полный сценарий после реализации/совместной приёмки |

Названия режимов — требования к **будущей** конфигурации deployment/client, не
добавленные сейчас settings. Нового endpoint capabilities/negotiation здесь нет.
В required mode missing → unsupported, false → pending, true → серверное подтверждение;
present null/неверный тип → InvalidResponse. Не делать downgrade при 404/501 или
отсутствии поля. 403 EMAIL_NOT_VERIFIED сохраняет Bearer; 400 EMAIL_ACTION_INVALID
не вызывает logout. Значение401 определяется operation + Problem.code + scope,
а не одним статусом:

| Контекст | Результат | Действие клиента по предложенной матрице |
|---|---|---|
| Действующая protected операция с отозванным/истёкшим Bearer |401 AUTH_REQUIRED | Очистить только совпадающую текущую source/user/session generation и оба private stores; guest сохранить |
| POST /me/delete-account, неверный пароль |401 INVALID_CREDENTIALS | Ошибка формы; Bearer и private stores сохранить. Тот же Bearer продолжает GET /me200 |
| POST /auth/login, неверные credentials |401 INVALID_CREDENTIALS | Не устанавливать новую сессию; не очищать другую действующую identity |
| Неизвестное сочетание operation/status/code | Нарушение контракта | Не угадывать expiry и не выполнять global logout; показать ошибку/ограничить неподтверждённый private доступ |

Не менять HTTP401 удаления на другой статус ради generic client handler.
PASSWORD_RESET_REQUIRED предусмотрен только на login403; любой403 не означает logout.
Поздний401 старой generation не должен стирать новую сессию. Состояние не переносится
между API source/accounts и не становится доказательством прав из offline cache.

## Этапы и gates

| Этап | Backend/контракт | Mobile и условие завершения |
|---|---|---|
| 1. Принятый0.1.1 contract-only | Optional flag, planned paths, изолированные errors/fixtures | Исторические legacy checks/DTO replay не означают поддержку N2 UI |
| 2. Подготовка клиента, ещё не выполнена | Общий source legacy; N2 отдельно | Сначала согласовать матрицу/политики, затем UNKNOWN/pending/verified/unsupported, OTP/reset, оба stores и mock/common/device checks |
| 3. Закрытая реализация N2 в main | Truthful flag,5 операций, attempts/grants/SMTP/revocation/restore работают только dev/test+lifecycle-test | Серверные tests есть; полный mobile email/reset/pending/cache E2E ещё не выполнен |
| 4. Строгий 0.2 и активация | Required flag, новые error refs/403 и enforcement | Backend уже возвращает bool везде, клиенты обновлены, fixtures/validators готовы; общая Android/iPhone приёмка и отдельное разрешение выпуска |

Клиентская реализация/приёмка и этап4 требуют следующих поручений. Недостаточно
удалить planned: нужны реальные HTTP status/schema/header checks, без потери B1/B2/B3.
Для N2 подготовлены [фактические примеры](../docs/examples/n2-http/README.md), включая
pending/verified delete-password401 → GET/me200. Это данные для будущего MockEngine,
не доказательство готовности мобильного UI.
Перед required backend уже должен выдавать поле во всех Profile/AuthResponse — это
не ломает ответы старого кода раньше его готовности. Перед включением guard его
ошибки должны быть разрешены канонической схемой. Частичный lifecycle не активировать.
Для первого однопроцессного deployment нужен контролируемый cutover; при required
нельзя смешивать старые и новые server instances на одном source.

## Legacy-аккаунты и minimum client version — открытые gates

Реализованного recovery для legacy с NULL email_delivery_address нет: request202
не обещает письмо, job пропускает аккаунт без delivery address. После restore
PASSWORD_RESET_REQUIRED запрещает обычный password login, поэтому gap критичен
для cutover. Нормализованный email нельзя молча backfill-ить как подтверждённый
delivery address. [Предложение](../docs/superpowers/specs/2026-11-07-legacy-account-recovery-design.md)
сравнивает пути; ни один новый путь этим уточнением не реализуется/не принимается.

Minimum поддерживаемая клиентская версия **не выбрана**. Backend API0.2 и версия
приложения — разные значения. Перед общим strict source нужны:

1. Отдельно принятые матрица, обе offline/pending политики и путь legacy/restore.
2. Готовый клиент и закрытый E2E обоих private stores; конкретные Android/iOS
   build identifiers и результаты, включая оставшиеся пробелы native iOS.
3. Совместно утверждённый minimum build, канал обновления, аудитория старых клиентов,
   срок перехода и поведение тех, кто не обновился. Текущие builds не объявлять совместимыми.
4. Отдельный PR принятия канонического0.2 и разрешение cutover. До этого общий
   source остаётся legacy, enforcement только в закрытом N2; automatic downgrade нет.

Заявленная клиентом версия не заменяет серверные guards. Нового version/capabilities
endpoint или готового механизма блокировки версий этот документ не добавляет.

## Rollback

Это уточнение не меняет runtime/data. После активации нельзя вернуть legacy
backend на required source: он обойдёт guards и может вернуть старые credentials.
Сначала закрыть signup/личные записи или maintenance, затем forward fix либо
совместимый ранее проверенный lifecycle release. Downgrade БД не обещается.
Restore остаётся в карантине до актуальной подготовки и операторского approval.
В закрытом N2 V9 и restore tools уже умеют lifecycle preparation/forced recovery
([отчёт](../docs/research/2026-11-05-account-lifecycle-n2.md)). Это не решает NULL
delivery address и не разрешает production restore или переход в legacy.

Внешние SMTP/domain, production keys, durable outbox, публичный signup и новый
mobile здесь не входят. Локальные N2 key/SMTP/migrations уже существуют, их код
не меняется. Register409 и email matching прежние. [Историческая проверка0.1.1](../docs/research/2026-11-01-account-lifecycle-compatibility.md).
