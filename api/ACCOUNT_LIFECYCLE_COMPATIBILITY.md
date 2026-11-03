# Совместимость account lifecycle: 0.1.0 → 0.1.1 → целевой 0.2

1 ноября 2026. База — main `7f6795530489e1be409c9457d10d83f4e5645761`, включая
mobile PR #21. Этот PR меняет контракт/проверки, без mail/runtime/миграций/mobile UI.

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

Saved/import уже подключён (PR #21). В текущей закрытой среде сохраняются нынешние
account saved, offline read и подтверждённый guest copy. После активации pending
блокирует сетевые личные операции/import; guest bytes не стираются при partial failure,
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
не вызывает logout, 401 означает отзыв/expiry. Состояние не переносится между API
source/accounts и не восстанавливается как доказательство прав из offline cache.

## Этапы и gates

| Этап | Backend/контракт | Mobile и условие завершения |
|---|---|---|
| 1. Этот PR, 0.1.1 contract-only | Optional flag, planned paths, изолированные errors/fixtures | Нынешний клиент работает; legacy checks и DTO replay PASS; совместный review |
| 2. Подготовка клиента | Сервер остаётся закрытым/legacy | UNKNOWN/pending/verified/unsupported, OTP/reset, secret cleanup и import guard; mock/common/device checks |
| 3. Реализация в закрытой среде | Правдивый flag и полный набор новых операций, attempts/grants/SMTP/revocation/restore | End-to-end, реальные mail/race/isolation/admin/backup tests; metadata availability меняется вместе с новым HTTP coverage; signup закрыт |
| 4. Строгий 0.2 и активация | Required flag, новые error refs/403 и enforcement | Backend уже возвращает bool везде, клиенты обновлены, fixtures/validators готовы; общая Android/iPhone приёмка и отдельное разрешение выпуска |

Этапы 2–4 требуют следующих поручений. Недостаточно удалить planned: добавить реальные
HTTP status/schema/header checks новых операций, не убирать B1/B2/B3 coverage.
Перед required backend уже должен выдавать поле во всех Profile/AuthResponse — это
не ломает ответы старого кода раньше его готовности. Перед включением guard его
ошибки должны быть разрешены канонической схемой. Частичный lifecycle не активировать.
Для первого однопроцессного deployment нужен контролируемый cutover; при required
нельзя смешивать старые и новые server instances на одном source.

## Rollback

Этот PR не меняет runtime/data. После будущей активации нельзя вернуть legacy
backend на required source: он обойдёт guards и может вернуть старые credentials.
Сначала закрыть signup/личные записи или maintenance, затем forward fix либо
совместимый ранее проверенный lifecycle release. Downgrade БД не обещается.
Restore остаётся в карантине до актуальной подготовки и forced recovery guard;
текущие restore scripts этого нового поведения пока не реализуют.

SMTP provider/domain, HMAC key, durable outbox, migrations, публичный signup и новый
mobile здесь не входят. Register 409, email matching и прежние restic/container
blockers не меняются. [Фактическая проверка](../docs/research/2026-11-01-account-lifecycle-compatibility.md).
