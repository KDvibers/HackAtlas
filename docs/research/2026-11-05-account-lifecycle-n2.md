# N2 — подтверждение почты и восстановление доступа в закрытом профиле

5 ноября 2026. **Не является принятием строгого API 0.2.** Друг прямо подтвердил,
что canonical0.1.1 сохраняется; кандидат проверяется отдельно. Мобильные исходники
этот этап не меняет. Коммит, push и PR выполняет пользователь.

## Реализация

Профиль `lifecycle-test` доступен только вместе с dev/test, без prod/release,
на loopback и выделенной lifecycle test DB. По умолчанию runtime выключен, новая
почта не нужна. Source marker запрещает запуск активированной БД в legacy mode.

Spring Mail использует BOM проекта, SMTP adapter с timeout2с и выключенным debug.
Mailpit1.33.0 закреплён digest; это локальный приёмник, не внешний email provider.
Одна очередь16 задач в памяти и отдельные ограниченные rate-limit maps не расходуют
квоты login. Public reset не обращается к аккаунтам на HTTP-потоке; одинаково принимает
известные/неизвестные/удалённые адреса, при переполнении всем503.

N1 crypto/store сохраняет только hashes/MAC, а код формируется SecureRandom.
TTL code10мин, 8 ASCII digits, 5 persisted attempts; cooldown60с/max3active,
reset grant256bit/5мин/one-use. Ошибочные попытки коммитятся доHTTP400.
Job использует сохранённый delivery address и проверяет существование, время и
credential version. Секреты живут в оперативной памяти только пока нужны; очередь
не пишет их на диск, логи и exports редактируются. Уведомление о смене пароля
best effort после commit: ошибка SMTP/очереди не отменяет пароль и ответ204.

V9 добавляет version сессии, timestamp смены credentials, source marker и owner-only
функции подготовки restore. Успешный reset атомарно меняет пароль, повышает version,
устанавливает verified, снимает reset-required, отзывает native sessions и proofs.
Admin cookie проверяет version, а mutation повторяет проверку после ожидания lock.

Restore активированного snapshot автоматически требует lifecycle preparation.
Для старого snapshot используется `restore.py --require-lifecycle-recovery`.
В карантине всем оставшимся аккаунтам ставится reset-required, sessions/proofs
удаляются, password login требует fresh recovery. Подготовка привязана к restoreId,
повторная миграция approved БД не блокирует восстановившегося пользователя.
Callback заново закрывает EXECUTE от PUBLIC/runtime после pg_restore --no-acl.
Приватный deletion review и operator approval остаются обязательными.

## Матрица кандидата для обсуждения с другом

| Операция | Поведение в закрытом кандидате |
|---|---|
| register/login | Profile.emailVerified обязателен boolean; pending TTL30мин, verified TTL30дней |
| login с корректным старым паролем после restore | 403 PASSWORD_RESET_REQUIRED; неправильный пароль401 |
| GET /me, POST logout/delete-account | Разрешены pending; revoked/expired/version-mismatch →401 |
| PATCH /me, saved GET/PUT/DELETE, applications GET/PUT/DELETE, suggestions, reports | Pending →403 EMAIL_NOT_VERIFIED; revoked →401 |
| POST email-verification/request | Bearer того же аккаунта; без тела;202 или204 если verified |
| POST email-verification/confirm | Bearer + challengeId/code;204, invalid400 EMAIL_ACTION_INVALID |
| POST password-reset/request | Публичный email →generic202, account lookup только в job |
| POST password-reset/verify | Публичный challengeId/code →200 reset-only grant; invalid400 |
| POST password-reset/confirm | Публичный resetToken/newPassword →204, без autologin; invalid400 |
| Публичный каталог | Доступен без входа, старый приложенный Bearer не закрывает каталог |

PASSWORD_RESET_REQUIRED **только login**, не общая ошибка protected endpoints.
Reset API не требует Bearer; если клиент всё же приложил недействительный Bearer,
возвращается401. Действующий Bearer другого пользователя не меняет target recovery.
Pending после email confirm не получает новую сессию автоматически; делает GET /me.
Неверный код400 не должен сбрасывать сессию. Окончательная матрица, переход старых
клиентов, ограничения pending и очистка частного кеша ещё требуют совместного решения.

## Проверки и ограничения

Итоговый Maven verify/dependency:list: 47 unit +309 integration tests, без
ошибок/пропусков. Legacy HTTP593 и N2 HTTP115 прошли schemas/headers/matrix checks.
Python: contract13 +lifecycle9 +B4 51 +security27 =100 PASS; canonical fixtures13 PASS.
OSV:111 runtime packages, affected0. Packaged B1/B2/B3 (Bruno/Schemathesis), полный
B4 HTTPS/restore V6→V9 и фактический локальный launch/SMTP прошли.
Один независимый static security review не выявил Important/Critical в границах N2. Промежуточные RED воспроизвели гонку queued reset на равной временной
границе, публикацию тестовых портов на0.0.0.0 и блокировку SMTP приветствия rDNS.
Исправления проверяются реальными PostgreSQL/Mailpit, а не H2/mock SMTP.

Контракт renderer сначала проверяет invariants canonical0.1.1, затем делает копию
0.2.0-test.1 в ignored target; общий Problem enum не расширяется. Отдельная HTTP
проверка контролирует schemas, statuses, no-store, Retry-After, redaction и coverage.
Старые B1/B2/B3 проверки не ослаблены. Команды закрытого запуска:
[runbook](../../infra/lifecycle-test/README.md).

Нет публичного staging, реальных писем, проверки телефона/эмулятора или нового
согласования мобильного контракта. Очередь недолговечна; retention cleanup,
общие квоты для нескольких replicas, внешний SMTP и public signup не входят в N2.
Legacy строки без сохранённого delivery address автоматически восстановить нельзя;
не следует выдумывать исходный адрес. Их переход согласовывается перед rollout.

Источники: [Spring Mail](https://docs.spring.io/spring-framework/reference/integration/email.html),
[Mailpit release1.33.0](https://github.com/axllent/mailpit/releases/tag/v1.33.0),
[Mailpit SMTP/rDNS](https://mailpit.axllent.org/docs/configuration/runtime-options/).


Перед публичным rollout отдельно оценить timing privacy в reset verify: неизвестный
challenge заканчивается после lookup, известный использует дополнительные locks/HMAC
и persisted attempt update. Review не измерял эксплуатацию; в закрытом N2 это не blocker.
Не следует считать одинаковый400 доказательством равного времени обработки.

GitHub main090935b: workflow42055842739 завершился ошибкой скачивания restic
(HTTPError, HTTP status не сохранён installer). Java/API/contract/Gitleaks до него
прошли. Это не удалённый прогон N2; успех локальных проверок не выдаётся за зелёный CI.

Свежая загрузка restic здесь не завершилась за шесть минут и была остановлена.
Локальный B4 использовал имеющийся binary0.19.1 с проверкой закреплённого SHA256.
Повторное скачивание и удалённый CI этим результатом не подтверждаются.

## Команды для пользователя

Выполнять в исходном N2 checkout, не в чистой worktree исправления PR25.
Сначала просмотрите diff. Добавляются только проверенные зоны N2;
backend/target, settings/key и локальные логи не добавляются.

```powershell
Set-Location 'C:\Daniyar\MyProjects\HackAtlas'
git status --short --branch
git diff --check
git diff --stat
git add backend/pom.xml backend/README.md backend/ACCOUNT_LIFECYCLE.md backend/src/main/java/com/hackatlas/auth/lifecycle backend/src/main/java/com/hackatlas/web/LifecycleController.java backend/src/main/resources/application-lifecycle-test.yaml backend/src/main/resources/db/migration/V9__lifecycle_test_runtime.sql
git add backend/src/main/java/com/hackatlas/auth/AuthService.java backend/src/main/java/com/hackatlas/auth/SessionPrincipal.java backend/src/main/java/com/hackatlas/web/JsonRequests.java backend/src/main/java/com/hackatlas/admin backend/src/main/java/com/hackatlas/security backend/src/main/java/com/hackatlas/personal/PersonalDtos.java backend/src/main/java/com/hackatlas/personal/ProfileService.java
git add backend/src/test/java/com/hackatlas/AccountLifecycleMigrationIT.java backend/src/test/java/com/hackatlas/B2HttpSupport.java backend/src/test/java/com/hackatlas/N2HttpSupport.java backend/src/test/java/com/hackatlas/LifecycleAdminIT.java backend/src/test/java/com/hackatlas/LifecycleApiIT.java backend/src/test/java/com/hackatlas/LifecycleJobsIT.java backend/src/test/java/com/hackatlas/LifecycleRestoreIT.java backend/src/test/java/com/hackatlas/LifecycleContainers.java backend/src/test/java/com/hackatlas/LifecycleIntegrationConfiguration.java backend/src/test/java/com/hackatlas/auth/lifecycle
git add .github/workflows/backend-ci.yml infra/lifecycle-test scripts/lifecycle
git add infra/release/compose.yaml infra/release/database/flyway/afterMigrate.sql infra/release/ops/restore.py infra/release/ops/restore_review.py infra/release/README.md scripts/b4/run_rehearsal.py scripts/b4/test_restore_review.py scripts/b4/README.md
git add docs/DECISIONS.md docs/PRIVACY.md docs/TECHNOLOGY_DECISIONS.md docs/STATUS_BACKEND.md docs/research/2026-11-05-account-lifecycle-n2.md docs/superpowers/plans/2026-11-03-account-lifecycle-n2.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat(backend): add closed-test account lifecycle runtime"
git push -u origin feat/account-lifecycle-test-runtime
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...feat/account-lifecycle-test-runtime?expand=1'
```

PR title: **Add closed-test email verification and password recovery**.
HTTPS credential проверен как yergaliuly, author отдельно — softache
<itsyergaliuly@gmail.com>. Это не заменяет результат будущего GitHub CI.
Коммит, merge, rebase, push и PR агент не выполнял.
