# Legacy-аккаунты: предложение по delivery address и восстановлению

7 ноября 2026. База: main `1563bef`, N2 после PR28 и mobile после PR31.
**Предложение для совместного согласования. Ни один вариант ещё не принят.**
Документ не реализует новую авторизацию, endpoint, миграцию, привязку адреса,
обход reset-required или strict rollout. Канонический API0.1.1 прежний;
0.2.0-test.1 работает только как закрытый кандидат.

## Проблема, подтверждённая текущим кодом

- [V8](../../../backend/src/main/resources/db/migration/V8__account_lifecycle_foundation.sql)
  добавляет nullable email_delivery_address. У прежних аккаунтов он NULL,
  email_verified=false; исходный delivery address из normalized email не восстановлен.
- [AuthService.register](../../../backend/src/main/java/com/hackatlas/auth/AuthService.java)
  сохраняет введённый адрес для НОВЫХ аккаунтов. Само наличие адреса не доказывает
  владение почтой: verified появляется только после принятого proof.
- [LifecycleService](../../../backend/src/main/java/com/hackatlas/auth/lifecycle/LifecycleService.java)
  принимает generic reset request202, а delivery job требует ненулевой address.
 202 означает приём запроса, не доставку письма или наличие recoverable account.
- Pending legacy без address может войти по существующим credentials, но доставка
  verification не состоится. В текущем API нет операции привязки недостающего address.
- [V9 restore preparation](../../../backend/src/main/resources/db/migration/V9__lifecycle_test_runtime.sql)
  отзывает sessions/proofs и требует password reset. После restore прежний пароль
  не даёт recovery bypass: корректный password login получает403 PASSWORD_RESET_REQUIRED.
  При NULL delivery address существующего самосервисного пути дальше нет.

Это открытый блокер переноса реальных legacy-аккаунтов в общий strict source.
Скрипт восстановления/операторское approval не создают missing delivery proof.
В тестовом N2 успешная проверка нового аккаунта этот случай не покрывает.

## Ограничения предложения

Сохранить opaque Bearer и смысл PASSWORD_RESET_REQUIRED только на login; guard не
отключать ради восстановления. Не подставлять normalized email в delivery address,
не выставлять verified SQL и не объединять аккаунты по совпавшему адресу автоматически.
Не удалять legacy-данные и не превращать private saved/applications в guest.
Существующий несовместимый клиент и email-only ввод после restore не доказывают
владение прежним аккаунтом. Публичный каталог остаётся доступен без входа.

## Три подхода

| Вариант | Как работает | Сложность / стоимость / поддержка | Совместимость и слабое место |
|---|---|---|---|
| A. Отдельный новый strict test source | N2 использует выделенную БД и новые synthetic accounts с записанным address; legacy source не активируется | Низкие: текущие Java/PostgreSQL/Mailpit, без новых сервисов или платной инфраструктуры | Подходит для закрытого N2 сейчас. Не переносит старые private данные и не решает их recovery; старый источник сохраняется, данные не удаляются |
| B. Привязка до cutover | На ещё доступном legacy source владелец подтверждает текущие credentials и явно введённый delivery address через одноразовый mail proof; привязка к прежнему UUID атомарна | Средние: отдельный согласованный контракт, backend/mobile flows, rate limits, expiry, audit и backup/restore tests; текущий стек достаточен | Сохраняет account-saved/applications. Нужны правила identity/address matching, collision, re-authentication и пользователей, не прошедших переход. Не помогает уже restored orphan без прежнего trusted recovery proof |
| C. Операторское восстановление исключений | Поддержка проверяет заранее утверждённые независимые доказательства владельца и выполняет отдельный контролируемый recovery flow | Высокая постоянная стоимость ручной поддержки и защиты приватных данных; нужны правила отказа, аудит и защита от социальной инженерии | Возможен для исключений после restore, но сейчас нет достаточных доказательств/процесса. Старый пароль, заявление адреса и содержимое backup сами по себе недостаточны |

**Рекомендация автора, не принятое решение:** оставить A для текущего закрытого N2.
Если подтверждено, что реальные legacy-аккаунты нужно сохранить при общем cutover,
следующим отдельным дизайном проработать B. C не включать в MVP без утверждённого
стандарта доказательств и ответственного оператора. Не заменять выбор B удалением
данных или массовой перерегистрацией без отдельного решения владельцев продукта.

Здесь не требуется новая библиотека, framework, микросервис или внешний IdP.
Mailpit годится для локальной проверки; для реальной доставки позже отдельно
понадобятся SMTP/domain/инфраструктура и разрешение deployment. Не выбирать их молча.

## Что отдельно спроектировать для B

Это требования к будущему предложению, а не спецификация новых HTTP paths:

1. Разрешённый source/момент enrollment ДО strict cutover и reset-required restore;
   активная identity плюс свежая проверка credentials. Нельзя сохранять privilege
   от одного старого cached Profile/Bearer или дать old password bypass после restore.
2. Правила сравнения введённого address с существующей normalized identity, регистр,
   повторная привязка, коллизии и смена адреса. Это не тот же процесс, что публичный
   password reset, и не автоматическое подтверждение нормализованной строки.
3. Proof, связанный с user/session generation, candidate address, purpose, expiry,
   credential version; attempts/resend/consuming transaction/races и отзыв устаревших
   jobs. Запись address и verified допускается только после принятого владения mailbox.
4. Audit и восстановимость принятой привязки: потеря нового enrollment при restore
   старого snapshot должна обнаруживаться. Один успешный backup rehearsal новых
   аккаунтов не подтверждает recovery старого snapshot без address.
5. Судьба dormant/unreachable аккаунтов, утраты пароля ДО enrollment и already-restored
   orphans. Если нет утверждённого trusted proof/пути восстановления, strict admission
   для этой аудитории блокируется; не обещать восстановление через текущие endpoints.

OWASP рекомендует повторную проверку credentials для изменения чувствительных
данных ([Authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#require-re-authentication-for-sensitive-features)).
Для recovery нужны ограниченные по времени одноразовые proofs, обычный login после
reset и защита от enumeration ([Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html)).
Конкретный вариант enrollment выше — предложение HackAtlas; эти рекомендации
не определяют автоматически его API и не утверждают способ доказательства legacy ownership.

## Admission, restore и переход клиентов

До общего strict source оператору нужен агрегированный inventory без вывода адресов:
число аккаунтов с NULL address и отдельно password_reset_required+NULL address.
Пример read-only SQL для будущего preflight (здесь на пользовательской БД не запускался):

```sql
SELECT count(*) FILTER (WHERE email_delivery_address IS NULL) AS missing_address,
       count(*) FILTER (WHERE password_reset_required AND email_delivery_address IS NULL) AS blocked_recovery
FROM hackatlas.user_account;
```

Ненулевые counters — не команда удалить аккаунты. Они требуют явно утверждённого
пути/исключения из cutover с сохранением данных и согласованным UX. Пустые counters
тоже не заменяют проверку proof, mail delivery и восстановления старых snapshots.
После lifecycle activation не запускать legacy binary на той же БД: source marker
и forced recovery guards сохраняются. Не открывать restored DB ради enrollment.

Minimum client version/build пока **не выбрана**. Предлагаемые gates:

1. Оба разработчика принимают матрицу, отдельные pending-bytes и required-offline
   решения, выбранный legacy путь и остаточные ограничения restore.
2. Отдельным поручением реализован и проверен клиент обоих private stores; закрытый
   E2E выполняется на Windows/API36 у пользователя, API34 regression и native iOS
   имеют отдельные фактические результаты/открытые ограничения.
3. Фиксируются реальные Android/iOS build identifiers, канал/срок обновления,
   известная аудитория и поведение старых клиентов. Номер API не считать версией
   приложения; произвольный client header не является authorization capability.
4. Отдельный PR принимает canonical0.2; оператор получает отдельное разрешение
   cutover/deployment. До этого canonical0.1.1/source legacy, enforcement только N2.

## Лист согласования

- [ ] Установлено, есть ли реальные legacy-аккаунты и требуется ли их перенос.
- [ ] Выбран A/B/C и владелец следующего отдельного дизайна; сейчас рекомендация, не approval.
- [ ] Для реальных аккаунтов закрыт NULL-address/restore gap или cutover заблокирован.
- [ ] Pending bytes сохранить/скрыть утверждено отдельно от delete-on-pending альтернативы.
- [ ] Required cold start offline hiding утверждено отдельно; legacy offline остаётся прежним.
- [ ] Minimum client builds/распространение/старые клиенты согласованы после готовности клиента.

[Матрица](2026-11-05-account-lifecycle-client-matrix.md),
[совместимость](../../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md),
[фактические HTTP-примеры](../../examples/n2-http/README.md).
Следующий этап пока ограничен review этих решений; новые auth operations здесь не реализуются.
