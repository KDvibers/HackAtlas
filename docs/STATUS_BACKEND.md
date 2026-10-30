# BACKEND: статус

Дата актуального дополнения: 31 октября 2026.

## B4.4 — квитанции удалений и приватные review реализованы локально

Пользователь утвердил локальный вариант B из [дизайна](superpowers/specs/2026-10-30-deletion-history-design.md) и поручил реализацию по [плану](superpowers/plans/2026-10-30-deletion-history.md). Работа выполнена одним агентом в `feat/backend-deletion-history`, база — пользовательский B4.3 commit `0e8d53653ac6b40e5474fa9ba590fe6c74c86345`. В начале [PR #14](https://github.com/KDvibers/HackAtlas/pull/14) был открыт, оба его Backend CI прошли. Финальная проверка подтвердила merge PR #14, успешный [main CI 41109653984](https://github.com/KDvibers/HackAtlas/actions/runs/41109653984) на `24a36cf` и последующий merge mobile PR #15: свежая main `58d29d8a3d779333e68699c97d603b3294db1fdc`. Её [CI 41111335734](https://github.com/KDvibers/HackAtlas/actions/runs/41111335734) на момент проверки queued; успех не заявляется.

`git merge-base --is-ancestor HEAD origin/main` успешен; между базой ветки и свежей main различается только mobile-область, server/API/собственные docs идентичны. Локальные merge/rebase не выполнялись, работа напарника не менялась. Git HTTPS account **yergaliuly** проверен отдельно от author identity. Commit, staging Git, push и PR B4.4 не выполнялись; изменения оставлены пользователю.

### Что изменено

- [V7 migration](../backend/src/main/resources/db/migration/V7__account_deletion_receipts.sql), [AccountDeletionService](../backend/src/main/java/com/hackatlas/auth/AccountDeletionService.java): минимальная `account_deletion_receipt` (record UUID/account UUID/recorded_at), без FK/email/credentials/контента, в той же транзакции с удалением и audit. Неверный пароль/rollback не оставляют ложной квитанции; повторная регистрация имеет новый UUID.
- [Permissions callback](../infra/release/database/flyway/afterMigrate.sql): runtime INSERT-only, без SELECT/UPDATE/DELETE. Новый GRANT условен на существование таблицы: restore V6 выполняет текущий callback до upgrade; настоящий старый backup теперь восстанавливается и мигрирует в карантине.
- [Export helper](../infra/release/ops/export_deletions.py), [draft helper](../infra/release/ops/prepare_restore_review.py), [validation/private writer](../infra/release/ops/deletion_history.py): READ ONLY REPEATABLE READ, все committed receipts без time/sequence cutoff; строгие format/identity/limits, source marker refusal, <=10 000 UUID / 1 MiB без усечения. Новые файлы 0600/private parent0700, O_EXCL/fsync, без overwrite. CLI не печатает account UUID.
- Draft имеет ровно девять полей B4.2, target hash/restoreId и false/false/null; он не выдаёт CONNECT и не вызывает approve. Отдельный export содержит `historyComplete=false`; source identity не добавляется в strict review.
- [Java IT](../backend/src/test/java/com/hackatlas/ModerationApiIT.java), [Python tests](../scripts/b4/test_deletion_history.py), [Docker/HTTPS runner](../scripts/b4/run_rehearsal.py): атомарность/ошибки 500/503/конкурентное удаление, isolation/private files, реальные ACL/snapshot/late commit, draft refusal и V6→V7. [Runbook](../infra/release/README.md), [PRIVACY](PRIVACY.md), backend docs, решения/технологии и ignore rules обновлены.

**Starter/API, opaque Bearer auth, public endpoints, Docker pins/dependencies, mobile/design и STATUS_MOBILE не менялись.** Новая migration V7 входит в этап; старые migration-файлы не изменены. Реальные dev/production volumes не запускались и не мигрировались; все изменяемые данные synthetic в owned disposable resources.

### Фактические проверки

| Команда / область | Результат |
|---|---|
| WSL root, backend: `bash ./mvnw --batch-mode --no-transfer-progress verify` | **BUILD SUCCESS, 282 теста: 21 unit + 261 integration; 0 failures/errors/skipped**, 1:37 |
| `python -m unittest discover -s scripts/b4 -p 'test_*.py'`; аналогично scripts/security | **32 + 22 = 54 PASS**, 0 skips; новый module 12 тестов |
| `scripts/validate_contract.py` и три `validate_http_contract.py` по fresh Maven exports | Starter/fixtures PASS; **139 + 345 + 106 = 590 HTTP-ответов PASS** |
| `run_rehearsal.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --output-dir backend/target/b44-final` | **PASS, 226.123 с**: HTTPS/admin/least privilege, source export/draft/rollback/replay, V6→V7 и cleanup |
| Gitleaks 8.30.1 history + all changed/new files; UTF-8/links/scope/Python syntax/XML/ignore/`git diff --check` | **PASS, no leaks**; 20 changed/new files, staged/API/mobile/design изменений нет |

RED→GREEN: отсутствие receipt schema подтверждено до migration; 68 ModerationApiIT затем прошли. Новый Python module сначала отсутствовал, после реализации 10 tests прошли; окончательный module расширен до 12. Настоящий V6 archive отдельно подтвердил отказ restore на новом безусловном GRANT; после условного grant финальная репетиция прошла. Старые проверки и security gates не ослаблялись.

Project `hackatlas-b4-71bbd8ff3459`: backup **40 535 bytes / 0.275 с**, restore **1.290 с**, блок до HTTPS **132.710 с**, включая deliberate negative tests, не production RTO. Управляемая открытая транзакция доказала отсутствие uncommitted receipt в export и появление позднего commit с recorded_at 2000 года в следующем полном export. Source receipt rows 2, старый snapshot/restore rows 1; поздняя история не подменяется. Review из 2 UUID фактически удалил 1 аккаунт. Draft false/null и неправильный source отвергнуты; overwrite запрещён, private export не изменён, исходная БД нетронута. V6 schema действительно не содержала receipt table; upgrade добавил пустую V7 таблицу, CONNECT сохранился запрещённым.

Логи/отчёты — ignored `backend/target/b44-task1-{red,green}.log`, `b44-task2-{red,green}.log`, `b44-verify.log`, `b44-python.log`, `b44-contract.log`, `b44-rehearsal/`, `b44-legacy-red/`, `b44-final/`, `b44-final-checks.log`, `b44-progress.md`. Собственные containers/volumes/secrets/dumps/exports/reviews удалены; built images оставлены. Иные контейнеры не были целями cleanup.

### Ограничения и следующий отдельный шаг

История в той же БД **не независима от restore**; независим только уже выгруженный private file. Потеря источника после последней выгрузки оставляет неизвестный интервал: без достоверной дополнительной истории — карантин. Старые удаления и старые writers/ручной SQL не покрываются задним числом; retained UUID псевдонимны. Нет автоматической доставки, offsite/encryption, retention/очистки receipts и exports или доказательства глобальной полноты. Эти решения нужны до реальных пользователей.

Следующий отдельный шаг — согласовать независимое хранение/доставку exports, доступы и сроки вместе с backup policy на выбранной инфраструктуре. Внешний deployment/M4 и публичный выпуск не разрешены. Контейнерные blockers B4.3 остаются; Docker/dependency pins неизменны, новый Trivy/OSV/Bruno/Schemathesis scan не выполнялся и не выдаётся за выполненный. Права/SQL/API проверены fresh suites и real HTTPS/restore.

Итоговый self-review выполнен автором; независимый reviewer/agent не подключался по предпочтению пользователя. Обнаруженная V6-несовместимость исправлена и доказана RED→GREEN. Неисправленных существенных замечаний или отложенных minor findings нет; пользователь/напарник выполняет PR review.

Сообщение напарнику для ручной передачи: «B4.4 не меняет API/auth. Добавлены только внутренние recovery receipts и операторские private export/draft. Каталог и B2/B3 можно подключать как прежде. PR #15 browser/share уже в main; оставшиеся mobile/iOS пункты — по твоему STATUS. Общего staging URL пока нет». Сообщение не отправлялось.

### Пользовательский commit / push / PR

PR title: **Record account deletions and prepare private restore reviews**.

Описание: «Добавлены атомарные recovery-квитанции, приватная полная выгрузка UUID и подготовка неодобренного restore review. Runtime имеет только INSERT; откат, конкурентное удаление, private files и совместимость V6→V7 проверены. Пройдены 282 Java-теста, 54 Python-теста, 590 HTTP-ответов и полная HTTPS/restore репетиция. Карантин сохраняется; независимая доставка и доказательство полноты истории не реализованы. API и opaque auth прежние».

```powershell
git status --short --branch
git add .gitignore backend/src/main/java/com/hackatlas/auth/AccountDeletionService.java backend/src/main/resources/db/migration/V7__account_deletion_receipts.sql backend/src/test/java/com/hackatlas/ModerationApiIT.java
git add infra/release/database/flyway/afterMigrate.sql infra/release/ops/deletion_history.py infra/release/ops/export_deletions.py infra/release/ops/prepare_restore_review.py
git add scripts/b4/run_rehearsal.py scripts/b4/test_deletion_history.py scripts/b4/README.md infra/release/README.md backend/README.md backend/MODERATION.md
git add docs/DECISIONS.md docs/PRIVACY.md docs/TECHNOLOGY_DECISIONS.md docs/STATUS_BACKEND.md docs/superpowers/specs/2026-10-30-deletion-history-design.md docs/superpowers/plans/2026-10-30-deletion-history.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat(backend): record deletions and prepare private restore reviews"
git push -u origin feat/backend-deletion-history
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...feat/backend-deletion-history?expand=1'
```

Ниже сохранены исторические статусы; их прежние ограничения относятся к соответствующему этапу.

## B4.3 — обновление официальных Java/PostgreSQL images

Пользователь поручил продолжить работу. Проверены GitHub и чистый checkout: [B4.2 PR #13](https://github.com/KDvibers/HackAtlas/pull/13) объединён в main `a4394f50aba09936df5e49234f276976d98e5ff4`, [Backend CI 40370128829](https://github.com/KDvibers/HackAtlas/actions/runs/40370128829) успешен. [Mobile PR #12](https://github.com/KDvibers/HackAtlas/pull/12) также объединён; по статусу напарника live Task 5 прошла на Android-эмуляторе, весь M2 ещё не завершён. Это результат напарника, а не новая device-проверка этой сессии.

Выбрана ограниченная следующая работа: проверить ранее открытые security blockers при появившихся upstream updates. Новая ветка `fix/backend-runtime-security` создана от `origin/main`, одним агентом в текущем checkout. Независимый журнал удалений требует отдельного выбора хранилища и правил, поэтому не реализуется молча. Прежнее разрешение — только подготовка и локальные проверки, без внешней инфраструктуры.

Git HTTPS account через `/user`: **yergaliuly**; автор отдельно **softache <itsyergaliuly@gmail.com>**. Commit, Git staging, push и PR не выполнялись.

### Изменения

- [backend/Dockerfile](../backend/Dockerfile): официальный Temurin JRE **21.0.13+9**, опубликованный 29 октября; закреплён index digest. Прямой запуск подтвердил реальную версию, host JDK 21.0.12.1 не обновлялся.
- [dev Compose](../infra/compose.yaml), [release Compose](../infra/release/compose.yaml), [Testcontainers configuration](../backend/src/test/java/com/hackatlas/IntegrationTestConfiguration.java), [CLI runner](../scripts/b1/run_contract_checks.py): единый новый PostgreSQL 17.11 Bookworm digest официальной пересборки от 28 октября. Major/version/schema не менялись, существующий dev volume не запускался.
- Caddy pin сохранён: сравнение официальных registry manifests с SHA-256 показало одинаковый Linux amd64 образ у прежнего и нового index. Добавление riscv64 не исправляет Go/x/net на проверяемой платформе.
- [Security report](research/2026-10-30-runtime-security.md), toolchain/runbook/решения/README обновлены; исторический security report сохранён с указателем на новый. START_HERE учитывает завершённую Task 5 напарника и оставшиеся browser/share в M2.

**API, opaque Bearer sessions, Java production code, Flyway migrations, mobile/design и STATUS_MOBILE не изменены.** Новых библиотек/сервисов/HTTP endpoints нет.

### Проверки

| Команда / область | Фактический результат |
|---|---|
| WSL root, backend: `bash ./mvnw --batch-mode --no-transfer-progress verify dependency:list -DincludeScope=runtime -DoutputFile=target/b43-runtime-dependencies.txt` | **BUILD SUCCESS, 279 тестов (21 unit + 258 integration), 0 failures/errors/skipped**, 1:37; новая PostgreSQL |
| `python -m unittest discover -s scripts/b4 -p 'test_*.py'`; аналогично scripts/security | **20 + 22 = 42 PASS**, 0 skips |
| `scripts/validate_contract.py`; `validate_http_contract.py` по свежим B1/B2/B3 exports | Starter/fixtures и **139 + 345 + 102 = 586 HTTP-ответов PASS** |
| `check_maven_dependencies.py --dependencies backend/target/b43-runtime-dependencies.txt --output backend/target/b43-dependency-audit.json` | **106 runtime packages, 0 OSV matches** на дату проверки |
| `run_rehearsal.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --output-dir backend/target/b43-rehearsal` | **PASS, 184.533 с**: HTTPS/admin/least privilege, quarantine, invalid/replay/active-session/rollback checks, deletion, source isolation и post-approval auth |
| `run_container_audit.py --rehearsal-report backend/target/b43-rehearsal/report.json --output-dir backend/target/b43-container-audit` | **Exit 1, аудит завершён полностью**: JRE blocker снят, остаются proxy/PostgreSQL HIGH/CRITICAL/UNKNOWN; policy не ослаблялась |
| Gitleaks 8.30.1 по истории и изменённым/новым файлам; pins/scope/links/XML/`git diff --check` | **PASS, no leaks**, 14 изменённых/новых файлов; staged/API/mobile/design изменений нет |

Rehearsal project `hackatlas-b4-a345d660ca56`: backup 38 879 bytes / 0.275 с, restore 1.270 с. Блок восстановления до HTTPS — 118.353 с, включая намеренные отказы; это не production RTO. Review: 2 UUID, фактически удалён 1 аккаунт; source/snapshot/approved account rows = 1/2/1. Собственные containers/volumes/secrets/backup удалены, images оставлены для связанного аудита. Все данные synthetic.

Trivy 0.76.1 с DB/Java index от **30 октября** проверил точные app/proxy/PostgreSQL images из репетиции. App: **0 CRITICAL/HIGH, 27 MEDIUM, 4 LOW**, JAR findings 0. Proxy: **7 HIGH, 16 MEDIUM, 1 LOW, 3 UNKNOWN**, Alpine findings 0. PostgreSQL: **4 CRITICAL, 94 HIGH, 187 MEDIUM, 171 LOW, 6 UNKNOWN**. Это package/advisory matches, не уникальные CVE и не reachability. **evidenceComplete=true, containerGatePassed=false, publicReleaseReady=false**. Новый JRE проходит прежний минимальный порог; его blocker отсутствует. Собственный scanner cache и архивы удалены, JSON/log оставлены локально.

Новые proxy advisories появились в свежей DB при прежнем бинарнике; разницу с B4.1 нельзя целиком считать эффектом обновления images. Полные counts, hashes/источники, ограничения и команды — [security report](research/2026-10-30-runtime-security.md) и ignored `backend/target/b43-*`. Bruno/Schemathesis и отдельный container scan старых images на той же новой DB не повторялись; сравнение counts не выдаётся за контролируемый before/after эксперимент. Итоговый diff проверен автором, отдельный агент не использовался.

### Ограничения и следующий отдельный шаг

Независимый источник истории удалений, backup schedule/offsite/encryption/retention, согласованная внешняя инфраструктура и M4 остаются открытыми. Нельзя объявлять полный B4 или публичный выпуск завершёнными. Отдельный manual GitHub workflow не запускался, Linux amd64 проверки не подтверждают другие архитектуры; новый iOS/device/live mobile прогон не выполнялся.

После пользовательского review/merge этой небольшой доработки следующий отдельный шаг — согласовать дизайн независимой истории удалений: где хранится приватный список, как подтверждается доставка и полнота, кто имеет доступ и когда записи удаляются. До такого решения остаётся операторская процедура B4.2 и закрытый restore при недостоверной истории. Проверять upstream security fixes повторно имеет смысл при новых публикациях, а не просто повторять тот же scan.

Сообщение напарнику для ручной передачи: «B4.2 уже в main. В B4.3 обновлены только runtime images; Starter, каталог, B2/B3 API и auth прежние. По твоему статусу дальше остаются browser/share в M2; server auth для будущего M3 уже реализована, но M3 требует завершённого M2 и отдельного задания. Общего staging URL пока нет».

### Пользовательский commit / push / PR

PR title: **Update backend runtime images and recheck release security**.

```powershell
git status --short --branch
git add backend/Dockerfile backend/TOOLCHAIN.md backend/src/test/java/com/hackatlas/IntegrationTestConfiguration.java
git add infra/compose.yaml infra/release/compose.yaml infra/README.md infra/release/README.md scripts/b1/run_contract_checks.py
git add README.md START_HERE.md docs/DECISIONS.md docs/STATUS_BACKEND.md docs/research/2026-10-26-release-security.md docs/research/2026-10-30-runtime-security.md
git diff --cached --check
git diff --cached --stat
git commit -m "fix(backend): update Java and PostgreSQL runtime images"
git push -u origin fix/backend-runtime-security
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...fix/backend-runtime-security?expand=1'
```

Ниже сохранены исторические отчёты. Их сведения об отсутствии B4.2 в main и старом JRE относятся к прежней дате.

## B4.2 — карантин восстановления и операторская проверка реализованы локально

**Согласованный локальный результат B4.2 выполнен; публичный выпуск не разрешён.** Пользователь утвердил [дизайн](superpowers/specs/2026-10-26-restore-quarantine-design.md) и поручил продолжить реализацию по [плану](superpowers/plans/2026-10-26-restore-quarantine.md), одним агентом. [B4.1 PR #11](https://github.com/KDvibers/HackAtlas/pull/11) объединён; [main CI 40357289928](https://github.com/KDvibers/HackAtlas/actions/runs/40357289928) завершён успешно. Чистая ветка `fix/backend-restore-quarantine` создана от main `fd6a6a18fcd3d014474403fe5195f888b9b88e6a`; финальный fetch подтвердил ту же main.

Commit, staging Git, push и PR не выполнялись. Git HTTPS account проверен через `/user`: **yergaliuly**; автор отдельно — **softache <itsyergaliuly@gmail.com>**. Изменения оставлены для пользовательского review/коммита.

### Что изменено и почему

- [restore.py](../infra/release/ops/restore.py): после успешного pg_restore/отзыва сессий БД остаётся `QUARANTINED`, runtime CONNECT запрещён, `readyForSmoke=false`. В comment новой БД записываются project/database/hash/restoreId/time; user UUID там нет. Мигратор может проверить схему, но не открывает runtime.
- [restore_review.py](../infra/release/ops/restore_review.py), [approve_restore.py](../infra/release/ops/approve_restore.py): приватный review JSON связывается с конкретной попыткой восстановления. Проверяются подтверждения оператора, UTC-время, UUID/дубликаты, поля/версия, private regular file <=1 MiB. Symlink/FIFO и открытые для группы/остальных файлы отвергаются. Одна транзакция под advisory lock перепроверяет metadata, удаляет подтверждённые UUID через существующие каскады, добавляет минимальный audit, очищает sessions и выдаёт CONNECT. Открытый runtime, устаревшее состояние и повторное одобрение отвергаются.
- [B4 runner](../scripts/b4/run_rehearsal.py), [review tests](../scripts/b4/test_restore_review.py): реальная PostgreSQL проверяет карантин, неправильные review, callback мигратора, stale metadata, surviving runtime connection после REVOKE, audit fault/rollback, отсутствующий UUID, удаление personal/free-text связей и вход после одобрения.
- [.gitignore](../.gitignore), [runbook](../infra/release/README.md), [PRIVACY](PRIVACY.md): реальные `*.restore-review.json` не коммитятся, описаны остановка writers, независимая сверка, время начала backup, private review и требования к будущим регулярным backups. Обновлены проектные решения и краткие README.

**HTTP API, opaque Bearer sessions, Java-логика и миграции не менялись.** `api/`, mobile/design и `STATUS_MOBILE.md` не затронуты. Изменился deployment-local JSON restore: до одобрения он больше не сообщает готовность runtime; отдельный approve возвращает `APPROVED_FOR_SMOKE` и `operatorReviewAccepted=true`. Это не новый публичный endpoint.

### Выполненные команды и результаты

| Проверка | Фактический результат |
|---|---|
| WSL root, из backend: `bash ./mvnw --batch-mode --no-transfer-progress verify` | **BUILD SUCCESS; 279 тестов (21 unit + 258 integration), 0 failures/errors/skipped** |
| `python -m unittest discover -s scripts/b4 -p 'test_*.py'` в pinned Linux venv | **20 тестов**, PASS: 8 safety + 12 review/private-file |
| Аналогично `scripts/security` | **22 теста**, PASS; всего Python **42**, 0 skips |
| `validate_contract.py` и `validate_http_contract.py` по новым B1/B2/B3 exports | Канонический контракт/fixtures и **139 + 345 + 102 = 586 HTTP-ответов**, PASS |
| `run_rehearsal.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --output-dir backend/target/b42-final` | **PASS, 179.427 с**: TLS/admin/permissions, restore/quarantine, все отрицательные review/rollback проверки, source isolation и post-approval auth |
| Gitleaks 8.30.1 по доступной истории и всем изменённым/новым файлам, scope/UTF-8/Markdown links, syntax и `git diff --check` | **No leaks found**, 15 файлов / 142 локальные ссылки, PASS; private review ignore rule проверен |

До исправления реальный regression завершился RED: `Restored runtime CONNECT must remain denied until privacy review`. После исправления реальное runtime-подключение запрещено до/после мигратора. Дополнительно FIFO regression сначала подтвердил блокирующее открытие; `O_NONBLOCK` исправил отказ не-regular files, итоговый review suite GREEN. Негативные тесты и production guards не ослаблялись.

Окончательный сценарий: backup 2 аккаунтов → удаление одного через API → остановка source app → restore 2 аккаунтов в закрытую БД → review из 2 UUID (1 отсутствует в snapshot) → фактически удалён 1 аккаунт и его личные связи/обращения → runtime открыт. Старые 2 токена дают 401; удалённый аккаунт не входит по паролю, сохранённый получает новую сессию. Source fingerprints неизменны. Audit fault откатывает удаление/открытие; stale metadata и уже подключённый runtime отвергаются. Повтор approve запрещён.

Backup: **38 876 байт / 0.266 с**; restore: **1.247 с**. Полный блок восстановления до HTTPS занял **117.022 с**, включая намеренные отказы, fault injection и connection probe; это **не production RTO** и не время реального человека на сверку. Все данные синтетические; собственные containers/volumes/secrets/dump/review и probe очищены. Логи: игнорируемые `backend/target/b42-verify.log`, `b42-red/`, `b42-review-red.log`, `b42-green/`, `b42-final/`, `b42-progress.md`.

### Ограничения и следующий шаг

Полнота списка удалений и остановка всех writers — **ручные подтверждения оператора**. Независимый автоматический журнал не построен; `admin_audit` не хранит UUID удалённого аккаунта и таким журналом не является. Без достоверных идентификаторов копия остаётся закрытой. Approval применяется только к подтверждённому списку и не стирает старые backups.

Не выполнены external deployment, реальная инфраструктура/ACME, mobile M4 и автоматические schedule/offsite/encryption/retention. Контейнерные blockers B4.1 этой работой не закрывались; Dockerfiles/pins/dependencies не менялись, повторный большой Trivy scan не выполнялся. Новая ветка на GitHub ещё не проверена до пользовательского push. Итоговая проверка прав/транзакций и diff выполнена автором; отдельный агент не подключался по предпочтению пользователя.

Сообщение напарнику: «Публичный каталог и B2/B3 API прежние, интеграцию можно продолжать. B4.2 меняет только операторское восстановление: новая копия БД закрыта до подтверждённого review; общего staging URL пока нет». Сообщение подготовлено, не отправлялось.

Следующий отдельный шаг: решить надёжный независимый источник истории удалений до реальных пользователей и затем автоматизацию backup на согласованной инфраструктуре. Ранее открытые security blockers проверять отдельно при новых upstream fixes. Полный B4/M4 и публичный запуск остаются открытыми.

### Пользовательский commit / push / PR

PR title: **Quarantine restored databases until deletion review**.

Описание: «Раньше restore отзывал сессии, но сразу выдавал приложению CONNECT, позволяя вернуть аккаунты, удалённые после snapshot. Теперь новая БД остаётся в карантине. Отдельная операторская команда принимает приватный review, атомарно повторяет подтверждённые удаления, очищает сессии и открывает доступ. Добавлены привязка к project/database/hash/restoreId, защита private JSON и проверки replay/active session/rollback. Пройдены 279 Java-тестов, 586 HTTP-ответов, 42 Python-теста и полный PostgreSQL/HTTPS сценарий. Полноту истории подтверждает оператор; автоматического журнала и внешнего deployment нет. API, auth и миграции сохранены».

```powershell
git status --short --branch
git add .gitignore README.md START_HERE.md infra/release/ops infra/release/README.md scripts/b4 docs/PRIVACY.md docs/DECISIONS.md docs/STATUS_BACKEND.md docs/superpowers/specs/2026-10-26-restore-quarantine-design.md docs/superpowers/plans/2026-10-26-restore-quarantine.md
git diff --cached --check
git diff --cached --stat
git commit -m "fix(backend): quarantine restores until deletion review"
git push -u origin fix/backend-restore-quarantine
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...fix/backend-restore-quarantine?expand=1'
```

## B4.1 — исправление proxy и воспроизводимая проверка контейнеров

Продолжение B4 выполнено в локальной границе: **внешний выпуск остаётся BLOCKED**. На GitHub объединён [B4 PR #10](https://github.com/KDvibers/HackAtlas/pull/10); [Backend CI main 40236439132](https://github.com/KDvibers/HackAtlas/actions/runs/40236439132) завершился успешно. Ветка `fix/backend-release-security` создана от `origin/main=35614e30a5d27eb50c58a088eef12de313f3df8c` при чистом checkout. Staging/публикация, commit, staging Git, push и PR этой доработки не выполнялись.

### Изменения

- [Proxy Dockerfile](../infra/release/proxy/Dockerfile): fixed `brotli-libs=1.2.1-r0`, `curl=8.23.0-r0`, `libcurl=8.23.0-r0` из signed Alpine repositories для Brotli/curl advisories, включая CVE-2026-111497 и CVE-2026-92392. Caddy binary, Go/x/net и остальные official base pins не заменялись; актуальные stable refs проверены повторно. [Brotli package](https://pkgs.alpinelinux.org/package/v3.23/main/x86_64/brotli-libs), [curl package](https://pkgs.alpinelinux.org/package/v3.23/main/x86_64/curl), [Alpine security DB](https://secdb.alpinelinux.org/v3.23/main.json).
- [run_container_audit.py](../scripts/security/run_container_audit.py): pinned Trivy 0.76.1, offline scan app/proxy/PostgreSQL archives без Docker socket. Проверены image/container IDs из реального rehearsal, config digest из tar и SHA-256 отчётов. Нужен новый каталог evidence. Созданный Linux cache имеет уникальную ownership label; очистка не принимает чужие ресурсы. Незавершённую загрузку можно продолжить через явно указанный собственный cache volume.
- [container_policy.py](../scripts/security/container_policy.py), [tests](../scripts/security/test_container_policy.py): неполные/устаревшие/подменённые evidence не дают PASS. HIGH/CRITICAL/UNKNOWN и пакеты без версии блокируют gate; минимальный JRE — Temurin 21.0.13+9, поскольку Trivy не распознаёт tar-installed JRE. Все findings сохраняются, suppressions/risk acceptances отсутствуют.
- [External release readiness](../.github/workflows/release-readiness.yml): отдельный ручной workflow с Maven/OSV, HTTPS/restore, container gate и allowlist JSON/log artifacts на 7 дней. Известные blockers дают nonzero exit; workflow ничего не разворачивает. Обычный Backend CI автоматически подхватывает unit tests новой политики. Новый ручной workflow на GitHub ещё не запускался.
- [B4 runner](../scripts/b4/run_rehearsal.py), [safety tests](../scripts/b4/test_rehearsal.py): существующие report/log не перезаписываются и не смешиваются; сохранены точные PostgreSQL и runtime container image IDs для привязки scan. Runbook, toolchain, проектные решения и README актуализированы.

**API/авторизация не изменены.** Java runtime/API code, миграции, `api/`, `mobile/`, дизайн и `STATUS_MOBILE.md` не менялись. Opaque Bearer sessions сохранены. Новые продуктовые функции, внешние сервисы и новая Go/JRE supply chain не вводились.

### Выполненные проверки

| Проверка | Результат |
|---|---|
| `backend/mvnw --batch-mode --no-transfer-progress verify dependency:list -DincludeScope=runtime -DoutputFile=target/b41-runtime-dependencies.txt` через WSL root | **BUILD SUCCESS; 279 тестов (21 unit + 258 integration), 0 failures/errors/skips** |
| `validate_http_contract.py` по новым B1/B2/B3 exports | **139 + 345 + 102 = 586 реальных ответов**, PASS |
| `python -m unittest discover -s scripts/security -p 'test_*.py'` | **22 теста**, PASS: evidence binding/свежесть/ошибки/сохранение findings/старый JRE/защита cleanup/версионированный Caddy module |
| Аналогично `scripts/b4` | **8 тестов**, PASS, включая отказ перезаписывать предыдущие evidence |
| `check_maven_dependencies.py --dependencies backend/target/b41-runtime-dependencies.txt --output backend/target/b41-dependency-audit.json` | **106 packages, 0 совпадений OSV** |
| `run_rehearsal.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --output-dir backend/target/b41-rehearsal-final` | **PASS, 105.641 с** на окончательном Brotli/curl image: TLS, CSRF/admin, permissions, 3 GET, миграции, backup/restore, hashes/history и old-token 401 |
| `run_container_audit.py --rehearsal-report backend/target/b41-rehearsal-final/report.json --output-dir backend/target/b41-security-release` | **Все 3 scans завершены, exit 1 / BLOCKED**, полные evidence; Brotli/curl advisories в Alpine части proxy отсутствуют |
| `container_policy.py --evidence backend/target/b41-security-release` по окончательному коду политики | **Exit 1, `evidenceComplete=true`**; real canonical Caddy module учтён, остальные findings не исключены |
| `validate_contract.py`, Python syntax, workflow YAML/pins/permissions, `git diff --check`, scope/UTF-8/Markdown links | PASS; 17 изменённых/новых файлов, staged/API/mobile/design изменений нет |
| Gitleaks 8.30.1 по доступной Git history и всем изменённым/новым файлам, `--redact=100` | **No leaks found**; это проверенный scope, не гарантия отсутствия любых секретов |

Backup синтетической БД: 38 587 байт / 0.267 с, restore: 1.256 с, recovery через HTTPS: 39.539 с. Старые 2 сессии удалены. Позднейший deleted account по-прежнему возвращается из старого snapshot: это отдельный privacy blocker, не исправление этого этапа. Production RPO/RTO не обещаются.

Логи/JSON — в игнорируемом `backend/target/`: `b41-verify.log`, `b41-rehearsal-final/`, `b41-dependency-audit.json`, `b41-security-release/`. Во время первого download Trivy прерывалась работа WSL/Docker, причина перезапуска не установлена; сохранённый собственный DB cache использован для продолжения. Ошибки cleanup больше не скрывают исходный сбой; публичные диагностические логи пишутся во время команды. Реальные Trivy reports содержат unversioned build wrapper `caddy`, но canonical `github.com/caddyserver/caddy/v2` указан с `v2.11.7`: политика сохраняет этот факт, не исключая findings. Неизвестный wrapper без подтверждённого опубликованного модуля продолжает блокировать gate.

Ограничения: исправления Go/x/net и актуальный официальный Temurin image ещё нужны; состав container findings не равен доказанной эксплуатации. External staging, Android/iOS/M4, retention/offsite backups и сверка удалений после snapshot не выполнены. Полный B4 по ROADMAP остаётся открыт.

Окончательные package/advisory matches: app **28 MEDIUM + 4 LOW**, proxy **5 HIGH + 14 MEDIUM + 1 LOW + 1 UNKNOWN** (Alpine часть 0), PostgreSQL **4 CRITICAL + 96 HIGH + 195 MEDIUM + 173 LOW + 6 UNKNOWN**. Это не количество уникальных CVE и не доказанная сетевая эксплуатация. [Область анализа, источники и triage](research/2026-10-26-release-security.md). Временные rehearsal containers/volumes/secrets/dump и audit Linux cache/archives очищены; Docker inventory содержит только исходный `focusduo-db-1`, команды управления им не выполнялись.

Git HTTPS account подтверждён как **yergaliuly**, commit author отдельно — **softache <itsyergaliuly@gmail.com>**. Финальный fetch сохранил main `35614e3`; staged изменений нет.

Сообщение напарнику: «B4 объединён в main. API публичного каталога прежний; Task 5 можно продолжать на переданном commit. Доработка B4.1 касается proxy и release evidence, не мобильной интеграции; общего staging URL пока нет». Сообщение подготовлено, не отправлялось.

Следующий отдельный шаг: процедура безопасного восстановления с учётом последующих удалений и политика регулярных backup; container blockers повторно проверять при появлении upstream fixes. Hosting/domain/deployment требуют отдельного решения.

PR title: **Patch proxy packages and enforce container release readiness**.

Описание: «Обновлены Brotli и curl/libcurl в Caddy image для устранения известных Alpine advisories. Добавлены воспроизводимый offline Trivy audit конкретных испытанных app/proxy/PostgreSQL images и fail-closed policy: повреждённые/устаревшие evidence, HIGH/CRITICAL/UNKNOWN, непроверенные версии и старый JRE не дают разрешения выпуска. Отдельный ручной GitHub workflow выполняет verify/OSV/HTTPS/restore и сохраняет отчёты даже при blocked. Проверены 279 Java-тестов, 586 HTTP-ответов, 30 Python-тестов и локальное восстановление. Остальные container/privacy blockers задокументированы; внешнего deployment и изменений API нет».

```powershell
git status --short --branch
git add .github/workflows/release-readiness.yml README.md START_HERE.md backend/TOOLCHAIN.md docs/DECISIONS.md docs/STATUS_BACKEND.md docs/TECHNOLOGY_DECISIONS.md docs/research/2026-10-26-release-security.md infra/release/README.md infra/release/proxy/Dockerfile scripts/b4 scripts/security
git diff --cached --check
git diff --cached --stat
git commit -m "fix(backend): patch proxy packages and add container release gate"
git push -u origin fix/backend-release-security
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...fix/backend-release-security?expand=1'
```

## B4 — подготовка и локальная проверка выполнены; внешнего staging нет

**Текущая согласованная часть B4 выполнена. Полный B4 по ROADMAP ещё не закрыт.** Пользователь ответил: «Пока только подготовка и локальные проверки». Хостинг, бюджет, регион и домен не выбирались, deployment/DNS/публикация не выполнялись. Публичная регистрация закрыта; реальный HTTPS staging, M4 и предрелизные условия остаются отдельной работой.

Перед началом проверены [объединённый B3 PR #8](https://github.com/KDvibers/HackAtlas/pull/8), [mobile PR #7](https://github.com/KDvibers/HackAtlas/pull/7) и успешный [main Backend CI 40227105790](https://github.com/KDvibers/HackAtlas/actions/runs/40227105790). Чистая ветка **`chore/backend-release`** создана без upstream от `origin/main=c8b6d1895253320879d6bb3a902750d9d15a1012`; финальный fetch подтвердил ту же main. Изменения не добавлены в staging и не закоммичены, push и PR не выполнялись. Git HTTPS credential проверен через GitHub `/user`: **yergaliuly**. Автор проверен отдельно: **softache <itsyergaliuly@gmail.com>**.

### Изменения и назначение

- [infra/release](../infra/release/README.md), [backend/Dockerfile](../backend/Dockerfile), [backend/.dockerignore](../backend/.dockerignore): воспроизводимый Compose-контур Caddy → Spring Boot → PostgreSQL с digest-pinned images. Локальные порты только loopback, TLS с явно доверенным тестовым CA, минимальные health/readiness, non-root/read-only/capability isolation и ограничения ресурсов. Секреты — отдельные файлы вне репозитория; приложение и БД не публикуют host ports.
- [ReleaseConfiguration](../backend/src/main/java/com/hackatlas/config/ReleaseConfiguration.java), [application-release.yaml](../backend/src/main/resources/application-release.yaml), [HackAtlasApplication](../backend/src/main/java/com/hackatlas/HackAtlasApplication.java): профиль `prod,release`, ранняя проверка несовместимых настроек, запрет DEMO/fixed clock/signup/Forwarded trust; отдельный завершающийся migration-only процесс. `application-dev.yaml` не читает dev-настройки при одновременно активном prod.
- [DB bootstrap](../infra/release/database/10-release-roles.sh), [Flyway grants](../infra/release/database/flyway/afterMigrate.sql): разные роли оператора/мигратора/runtime; runtime без DDL, выдачи admin-доступа, чтения Flyway history и изменения аудита. [AdminAccessService](../backend/src/main/java/com/hackatlas/admin/AdminAccessService.java) сохраняет lock аккаунта, но membership читает без требующего UPDATE-привилегии `FOR SHARE`; точная семантика отзыва описана в [MODERATION](../backend/MODERATION.md).
- [backup.py](../infra/release/ops/backup.py), [restore.py](../infra/release/ops/restore.py): custom-format dump + SHA-256, запрет overwrite; восстановление только в новую БД, проверка checksum, отзыв восстановленных сессий и закрытый runtime CONNECT до завершения. [PRIVACY](PRIVACY.md) описывает обязательную сверку удалений после snapshot.
- [scripts/b4](../scripts/b4/README.md): автоматическая локальная репетиция HTTPS/admin/миграций/backup/restore с синтетическими данными, проверкой схем и cleanup только собственных ресурсов.
- [pom.xml](../backend/pom.xml), [TOOLCHAIN](../backend/TOOLCHAIN.md), [scripts/security](../scripts/security/README.md): Boot BOM **4.1.1 → 4.1.2** устраняет найденные совпадения OSV в Tomcat/Jackson. Проверяется реальный runtime dependency graph; сетевой сбой и неполный ответ не превращаются в успешный аудит.
- [infra/compose.yaml](../infra/compose.yaml), [IntegrationTestConfiguration](../backend/src/test/java/com/hackatlas/IntegrationTestConfiguration.java), [B1 runner](../scripts/b1/run_contract_checks.py): свежая официальная пересборка PostgreSQL 17.11 от 21 октября закреплена единым digest `858bb5e98eed02eb8cafbcdc007f9a65d1b613972028944a8ec2133ccaf80d27` во всех dev/test/release контурах. Существующая dev-БД не запускалась и не менялась.
- [Backend CI](../.github/workflows/backend-ci.yml): сохранены B1–B3 и Gitleaks, добавлены dependency audit и локальный B4 rehearsal. CI не разворачивает внешний сервер. Решения, общие README и передача каталога актуализированы; изменён только backend status.

**API не изменён:** `api/`, fixtures, mobile, дизайн и `STATUS_MOBILE.md` сохранены. Opaque Bearer sessions без JWT/refresh сохранены. Backend, инфраструктура и QA работали в непересекающихся файлах; итог проверен координатором.

### Реально выполненные проверки

| Команда / проверка | Результат |
|---|---|
| WSL root, из backend: `bash ./mvnw --batch-mode --no-transfer-progress verify dependency:list -DincludeScope=runtime -DoutputFile=target/b4-runtime-dependencies.txt` | **BUILD SUCCESS: 279 тестов — 21 unit + 258 integration; 0 failures/errors/skipped** |
| `.venv/Scripts/python.exe scripts/validate_contract.py` | PASS канонический контракт и все fixtures |
| `validate_http_contract.py` по `target/catalog-contract-responses.json`, `target/b2-contract`, `target/b3-contract` | **139 B1 + 345 B2 + 102 B3 = 586 реальных HTTP-ответов**, PASS |
| `scripts/b1/run_contract_checks.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar` | **12 HTTP cases, Bruno 10/10, Schemathesis 201/201**, seed 20261020 |
| Аналогичный `scripts/b2/run_contract_checks.py` | **30 HTTP cases, Bruno 26/26**, PASS |
| Аналогичный `scripts/b3/run_contract_checks.py` | **36 HTTP cases, Bruno 24/24**, PASS |
| `python -m unittest discover -s scripts/security -p 'test_*.py'` и аналогично `scripts/b4` | **5 + 7 = 12 проверок**, PASS |
| `scripts/security/check_maven_dependencies.py --dependencies backend/target/b4-runtime-dependencies.txt --output backend/target/b4-dependency-audit.json` | **106 runtime dependencies, 0 совпадений OSV** после обновления BOM; отдельные container findings остаются |
| `scripts/b4/run_rehearsal.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --output-dir backend/target/b4-rehearsal` | **PASS, 101.833 с** на окончательном PG image; TLS validation включён, отдельные миграции, runtime permissions, admin login/CSRF/verify/archive/publish, 3 публичных GET, полный backup/restore |
| Gitleaks 8.30.1 `git --redact=100 --log-opts=--all` и `dir` по изменённым/новым файлам | Доступная история и рабочие изменения проверены; **no leaks found** |
| `git diff --check`, scope/UTF-8/Markdown links, CI YAML, Python syntax и secret/backup ignore rules | PASS; staged/API/mobile/design изменений нет |

CLI runner использовал `/root/.local/share/hackatlas-b2-tools/venv/bin/python` и Node `/root/.local/share/hackatlas-b2-tools/node-v24.19.0-linux-x64/bin/node`. Java 21.0.12.1, Docker 29.1.3, Compose 2.40.3, PostgreSQL 17.11. SHA-256 проверенного JAR: `4cc10be9e211c5ca304fec12efd873de9af4fec8a1b38133d2aa39ed16bf6b38`.

В первых проверках исправлены ошибочный configtree path в тесте, capability официального Caddy binary при строгом запуске и явный HTTP→HTTPS redirect 308. Introspection запрета Flyway перенесён на оператора с указанием проверяемой runtime-роли; сами реальные запрещённые SELECT/DDL по-прежнему выполняются runtime и получают SQLSTATE 42501. Привилегии не расширялись, assertions не ослаблялись. После исправлений итоговый полный verify и rehearsal выполнены успешно.

При смене PostgreSQL image первый повтор verify встретил timeout загрузки образа в Testcontainers. После успешного `docker pull` выполнен полный повтор, а не отключение теста или увеличение watchdog ради зелёного результата. Диагностика неудачной загрузки сохранена отдельно в `b4-verify-image-pull.log`.

### Восстановление и его пределы

Резервная копия синтетической БД: **38 600 байт, 0.260 с**. Восстановление в новую БД: **1.299 с**; до повторного HTTPS smoke — **39.849 с**. Сверены fingerprints 11 таблиц приложения и истории Flyway; ожидаемое отличие — удаление 2 сессий. Оба старых токена получили 401, исходная БД осталась без изменений от restore, повторная проверка миграций прошла.

Специально проверено удаление аккаунта после backup: в исходной БД остался 1 аккаунт, старый snapshot вернул 2. Поэтому результат содержит `requiresPrivacyReview=true`, `publicReleaseReady=false`. Отзыв сессий не предотвращает повторный вход восстановленного аккаунта по паролю. Перед открытием восстановленного сервиса нужно применить последующие удаления. Эти локальные времена не являются обещанием production RPO/RTO: расписание/off-site backup, retention, журнал удалений, время оператора, DNS и production объём не реализованы/не измерены.

Логи и JSON находятся в игнорируемом `backend/target/`: `b4-verify.log`, `b4-b1-regression.log`, `b4-b2-regression.log`, `b4-b3-regression.log`, `b4-dependency-audit.json`, `b4-rehearsal/report.json`, `b4-security/`. Временные БД, контейнеры, volumes, passwords и dump rehearsal удалены. Это были синтетические данные; внешних пользователей и серверов нет.

Окончательный Trivy scan выполнен по app/proxy images из успешного rehearsal `hackatlas-b4-3e5371b6f85b` и новому PostgreSQL digest. Собственные Linux scanner cache/image также очищены; финальный `docker ps` показывает только исходный чужой `focusduo-db-1`, который не изменялся. Собранные app/proxy images и raw audit reports сохранены локально для проверки.

### Что остаётся перед внешним выпуском

Trivy обнаружил уязвимости в container dependencies; итоговый состав, severity и пределы анализа — в [аудите](research/2026-10-26-release-security.md). Функциональный PASS и отсутствие OSV findings в Maven не означают, что security gate внешнего выпуска пройден. Потребуются исправленные/проверенные образы и triage без скрытия известных проблем.

App image: 32 MEDIUM/LOW package matches, 0 в 94 распознанных Java packages; proxy: 6 HIGH; PostgreSQL: 4 CRITICAL/96 HIGH до контекстного triage. Trivy не распознал сам Temurin JRE; upstream 21.0.13+9 уже есть, официальный Docker image этой версии ещё отсутствует. Ни одна из этих границ не скрыта за общим «тесты зелёные».

Автоматическая проверка разрешений отклонила удаление временного Windows-кэша Trivy `backend/target/b4-security/cache` с единственным объяснением «blocked by policy». Удаление не повторялось, кэш оставлен в игнорируемой области. Это не данные приложения и не изменение исходников.

Новый B4 workflow ещё не запускался на GitHub — пользователь не сделал push. Не проверялись внешний домен/ACME, целевой сервер, Android/iOS/телефон/компьютер напарника и M4. IP-лимит за единственным proxy общий, account limits остаются индивидуальными; лимитеры однопроцессные. Публичная регистрация закрыта, email verification/reset не добавлены. Следующий отдельный шаг: устранить release security blockers, согласовать инфраструктуру и только затем провести внешний staging/M4 и проверить эксплуатационные процедуры на целевой среде.

Сообщение напарнику: «B3 уже в main; публичные list/detail/filters работают по прежнему Starter без входа. Поднимай свой main по B1_MOBILE_HANDOFF: общего staging URL пока нет. B4 добавляет локальные HTTPS и backup/restore проверки, API и мобильный UI не меняет. Подключение реальных устройств остаётся отдельной проверкой». Сообщение подготовлено, автоматически не отправлялось.

### Пользовательский commit / push / PR

PR title: **Prepare B4 release infrastructure and recovery checks**.

Описание для GitHub: «Подготовлен локальный release-контур Caddy / Java / PostgreSQL: HTTPS, отдельные роли мигратора и runtime, file secrets, безопасная конфигурация и backup/restore в новую БД с отзывом сессий. Добавлены автоматическая репетиция выпуска и OSV-проверка зависимостей в CI; Boot обновлён до 4.1.2. Пройдены 279 Java-тестов, 586 проверок HTTP-контракта, Bruno для B1–B3, Schemathesis для B1 и полное восстановление с HTTPS smoke. Внешний deployment не выполнялся: контейнерные security findings и восстановление позднее удалённых аккаунтов явно документированы. API, opaque sessions и mobile не изменены».

```powershell
git status --short --branch
git add .github/workflows/backend-ci.yml .gitignore README.md START_HERE.md backend infra scripts/b1/run_contract_checks.py scripts/b4 scripts/security docs/DECISIONS.md docs/STATUS_BACKEND.md docs/PRIVACY.md docs/TECHNOLOGY_DECISIONS.md docs/B1_MOBILE_HANDOFF.md docs/research/2026-10-26-release-security.md
git diff --cached --check
git diff --cached --stat
git commit -m "chore(backend): prepare B4 release infrastructure and recovery checks"
git push -u origin chore/backend-release
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...chore/backend-release?expand=1'
```

## B3 — исторический отчёт о модерации и удалении аккаунта

**B3 выполнен, B4 не начат.** Перед работой проверены GitHub и чистый checkout: [B2 PR #5](https://github.com/KDvibers/HackAtlas/pull/5) пользователя `yergaliuly` объединён 25 октября; [main workflow 40223237200](https://github.com/KDvibers/HackAtlas/actions/runs/40223237200) завершён успешно. Ветка `feat/backend-moderation` создана без upstream от `origin/main=20464e76534e64e424b4ea98acc965d4c6aaaf04`. Это база с B2 и мобильным PR #4.

Commit, staging, push, PR и deployment не выполнялись. Локальные Git HTTPS credentials повторно проверены через GitHub `/user`: **yergaliuly**; настройка credential username также `yergaliuly`. Автор коммита проверен отдельно: **softache <itsyergaliuly@gmail.com>**. Изменения оставлены пользователю для review и коммита.

Финальный GitHub check и fetch обнаружили уже объединённый [mobile PR #6](https://github.com/KDvibers/HackAtlas/pull/6): `origin/main=04407db5a37eed1ad388894c2ca2741716414117`. Он меняет только mobile и его документы: `git diff HEAD..origin/main` подтвердил отсутствие изменений backend/API/infra и пересечений с файлами B3. Эта локальная ветка осталась на базе `20464e7`; merge/rebase не выполнялись. Инструкция другу обновлена для свежей main. Тесты мобильного PR — результат работы напарника, не этой сессии.

### Что изменено

- `backend/src/main/java/com/hackatlas/admin/`, `security/`, `config/AdminCookiePolicy.java`, `templates/admin/`, `static/admin/`: Thymeleaf-редактор draft/publish/archive, даты/перенос/отмена, проверка источника, очередь. Отдельная browser session с CSRF, отзывом доступа и Secure cookie в prod; обычный USER и Bearer-токен не дают admin-доступ. [Руководство оператора](../backend/MODERATION.md).
- `backend/src/main/java/com/hackatlas/moderation/`, `web/ModerationController.java`, `web/JsonRequests.java`: suggestions/reports с PENDING, strict JSON, HTTPS syntax validation без сетевых скачиваний, IP/account limits. [B3 API и ограничения](../backend/MODERATION.md).
- `backend/src/main/java/com/hackatlas/auth/AccountDeletionService.java`, `AuthService.java`, `personal/PersonalListService.java`: повторная проверка пароля, атомарное удаление аккаунта/сессий/личных данных/обращений; блокировки согласованы с login и новыми личными связями.
- `backend/src/main/resources/db/migration/V5__moderation_submissions.sql`, `V6__admin_access_and_audit.sql`: только новые миграции, FK/очередь/права/минимальный аудит. Нет автоматических admin grants и seed-аккаунтов. [Правила удаления и хранения](PRIVACY.md).
- `backend/pom.xml`, `application.yaml`, `application-dev.yaml`, `web/DataFailures.java`, `web/ApiExceptionHandler.java`: Thymeleaf из Boot BOM, cookie/limits, безопасная диагностика ошибок БД, различение 500/503. Opaque Bearer B2 сохранён. [Версии и выбор](../backend/TOOLCHAIN.md).
- `backend/src/test/`, `scripts/b3/`, `tools/bruno-b3/`, `.github/workflows/backend-ci.yml`: PostgreSQL/HTTP/admin/fault tests, экспорт ответов и B3 contract runner, 24 Bruno requests; B1/B2 проверки сохранены.
- [B1_MOBILE_HANDOFF](B1_MOBILE_HANDOFF.md): готовая инструкция напарнику для публичного каталога из main, без зависимости от B3. Обновлены README/START_HERE/DECISIONS и только backend status.

**Изменения API: отсутствуют.** Реализованы три уже предусмотренные B3 операции; `api/`, fixtures, `mobile/`, `design/` и `STATUS_MOBILE.md` не менялись. Backend, QA и tooling работали в непересекающихся файлах; итог интегрирован и проверен координатором.

### Выполненные команды и результаты

| Команда / проверка | Результат |
|---|---|
| WSL root: `cd backend && bash ./mvnw --batch-mode --no-transfer-progress verify` | **BUILD SUCCESS; 267 тестов: 9 unit + 258 integration; failures/errors/skipped = 0** |
| `.venv/Scripts/python.exe scripts/validate_contract.py` | PASS OpenAPI 3.1, refs, фазы, opaque auth и все fixtures |
| `validate_http_contract.py --schema api/openapi.yaml --responses backend/target/catalog-contract-responses.json` | **139 реальных B1 ответов**, PASS |
| Тот же validator с `--responses backend/target/b2-contract` | **345 B2 ответов**, все 11 операций и 36 обязательных method/path/status pairs, PASS |
| Тот же validator с `--responses backend/target/b3-contract` | **102 B3 ответа**, все 3 операции и 19 обязательных method/path/status pairs, PASS |
| `scripts/b1/run_contract_checks.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar` | **12 HTTP cases, Bruno 10/10, Schemathesis 201/201**, seed 20261020 |
| `scripts/b2/run_contract_checks.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar` | **30 HTTP cases, Bruno 26/26**, PASS |
| `scripts/b3/run_contract_checks.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar` | **36 HTTP cases, Bruno 24/24**, PASS; две учётные записи, отзыв всех сессий, повторная регистрация с новым UUID и пустыми списками |
| Встроенный Chromium, отдельный JAR/PostgreSQL | Вход редактора, сохранение POSTPONED, подтверждение изменения публичным GET, каталог/редактор/очередь, logout — PASS; [screenshots](../backend/artifacts/b3/README.md) |
| `backend/target/b3-final-checks.py`, `git diff --check`, Gitleaks 8.30.1 `git/dir --redact=100` | Scope, UTF-8/форматирование, локальные Markdown-ссылки и screenshots — PASS; доступная история и изменённые/новые файлы — **no leaks found** |

CLI прогоны выполнены с `/root/.local/share/hackatlas-b2-tools/venv/bin/python` и `--node /root/.local/share/hackatlas-b2-tools/node-v24.19.0-linux-x64/bin/node`; schema validator — с Windows `.venv`. Java 21.0.12.1, Docker 29.1.3, PostgreSQL 17.11; версии не менялись, добавлен только BOM-managed Thymeleaf 3.1.5.RELEASE.

В первом прогоне 2 fault tests выявили 500 вместо 503 после потери соединения при rollback. Исправлена классификация, затем выполнен полный успешный `verify` и проверки окончательного JAR. SQL constraint fault возвращает 500; потеря соединения — 503; в обоих случаях транзакция удаления откатывает аккаунт, все сессии и личные связи. Тестовые ожидания не ослаблялись. Дополнительно исправлены порядок блокировок admin/account и очистка logout cookie с правильным Path; проверки CSRF и prod Secure cookie проходят.

Логи находятся в игнорируемом `backend/target/`: `b3-verify.log`, `surefire-reports/`, `failsafe-reports/`, `catalog-contract-responses.json`, `b2-contract/`, `b3-contract/`, `b3-b1-regression.log`, `b3-b2-regression.log`, `b3-checks/contract-checks.log`. Тесты используют вымышленные данные; токены и тела auth-запросов не публикуются в отчёте. Все runner/JVM и временные PostgreSQL завершены; после очистки остаётся только исходный контейнер другого проекта `focusduo-db-1`, который не изменялся.

### Ограничения и передача

Новый B3 workflow ещё не запускался на GitHub: для этого нужен пользовательский push. После mobile PR #6 свежий [Backend CI main 40225964082](https://github.com/KDvibers/HackAtlas/actions/runs/40225964082) также завершён успешно; это проверка main с B2, без локальных изменений B3. Schemathesis fuzzing выполняет только B1; B2/B3 проверяются сценариями состояния и PostgreSQL fault tests, а не полным security audit.

Админка минимальная: темы/призы/eligibility существующих карточек сохраняются, но не редактируются этой формой; у новых карточек они остаются неизвестными. Очередь показывает первые 100 PENDING; автоматического сбора событий нет. Лимитеры работают в одном процессе. Production signup закрыт. TLS, deployment, backup/retention и предрелизные условия относятся к отдельному B4; они не выполнялись.

Windows→IP WSL проверен на временном сервере, Windows→WSL localhost в этой среде не отвечает. Сервер остановлен, общего staging URL нет. Доступ с компьютера напарника, Android Emulator, физического телефона и iOS не проверялся. [Инструкция для друга](B1_MOBILE_HANDOFF.md) рекомендует собственный запуск из main и даёт адреса/команды/DEMO ID/clock. Авторизацию B2 для публичного каталога подключать не нужно.

Сообщение напарнику: «Публичные list/detail/metadata работают по прежнему Starter. Поднимай main по B1_MOBILE_HANDOFF; гостевые закладки пока локальные. B3 подготовлен отдельно: после его merge появятся suggestions/reports/delete-account. Статусы/даты опубликованного события в saved обновляются; POSTPONED/CANCELLED и архив должны обновлять snapshots/reminders при будущей M3-интеграции. Мобильный UI и контракт не менялись». Сообщение подготовлено, автоматически не отправлялось.

### Пользовательский commit / push / PR

PR title: **Implement B3 catalog moderation and account deletion**.

Описание: «Добавлена админка каталога на Thymeleaf с отдельной сессией и CSRF: черновики, публикация, архив, даты, перенос/отмена и ручная проверка источника. Реализованы suggestions/reports с очередью PENDING и лимитами, удаление аккаунта с подтверждением пароля и атомарным отзывом всех сессий/удалением личных данных. Добавлены миграции V5/V6, минимальный аудит и правила хранения. Контракт Starter и opaque Bearer протокол не изменены. Проверки: Maven 267 тестов, 586 HTTP-ответов по OpenAPI, Bruno B1/B2/B3 10/26/24, Schemathesis B1 201. Админка проверена в браузере на одноразовой БД. B4 и мобильная реализация не входят».

Из корня репозитория, после своего review:

```powershell
git status --short --branch
git diff --check
git add .github/workflows/backend-ci.yml README.md START_HERE.md backend docs/DECISIONS.md docs/STATUS_BACKEND.md docs/PRIVACY.md docs/B1_MOBILE_HANDOFF.md scripts/b3 tools/bruno-b3
git diff --cached --stat
git commit -m "feat(backend): implement B3 moderation and account deletion"
git push -u origin feat/backend-moderation
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...feat/backend-moderation?expand=1'
```

Последняя команда открывает форму PR; вставьте название/описание выше и создайте PR самостоятельно под `yergaliuly`. GitHub CLI на этой машине не установлен. Никакой force push не нужен. Следующий отдельный этап — **B4**, только по новому поручению. Ниже сохранены исторические статусы: их слова «B3 не начат» относятся к прежним этапам.

## Исторический B2 — аккаунты и личные данные завершены и объединены

**B2 выполнен, B3 не начат.** GitHub проверен до начала: [B1 PR #3](https://github.com/KDvibers/HackAtlas/pull/3) и [mobile PR #4](https://github.com/KDvibers/HackAtlas/pull/4) объединены. Workflow main [40220360992](https://github.com/KDvibers/HackAtlas/actions/runs/40220360992) завершён успешно. Новый чистый checkout `feat/backend-auth` основан на `origin/main=b62306583814d6634bad8dddf826529ba850e872`; мобильные изменения включены в базу и не редактировались.

Изменения B2 оставлены без staging и коммита. GitHub connector и отдельно локальные Git HTTPS credentials подтверждены как `yergaliuly` через API `/user`; автор Git — `softache <itsyergaliuly@gmail.com>`. Commit, push, PR и deployment не выполнялись. У новой ветки нет upstream до пользовательского `git push -u`.

### Что реализовано и зачем

- `backend/src/main/java/com/hackatlas/auth/`, `security/`: Spring Security 7.1.1, Argon2id, случайные opaque sessions, SHA-256 token hashes, абсолютный TTL, отзыв текущей сессии, IP/account limits и ограничение параллельных Argon2 вычислений. No-store и безопасный Problem без токенов/паролей/PII в логах; CSRF сохраняется вне native API.
- `backend/src/main/java/com/hackatlas/personal/`, `web/AuthController.java`, `web/PersonalController.java`, `web/JsonRequests.java`, `web/PersonalQueryParser.java`: все 11 операций B2, профиль, strict JSON/allowlist/absent-null, интересы из metadata, независимые личные списки с идемпотентными PUT/DELETE и изоляцией по principal.
- `backend/src/main/java/com/hackatlas/catalog/CatalogService.java`: сохранённые используют тот же расчёт статусов и ограниченную выборку без N+1; опубликованные ended/cancelled доступны, draft/archived скрыты. Счётчики соответствуют доступным спискам.
- `backend/src/main/resources/db/migration/V3__accounts_and_sessions.sql`, `V4__personal_lists.sql`: новые таблицы, FK, уникальные ключи и индексы. Старые миграции и существующие данные не изменены; seed-аккаунтов нет.
- `backend/pom.xml`, `backend/src/main/resources/application.yaml`: Security/Validation starters, Bouncy Castle 1.86; закрытая регистрация, configurable session TTL, отсутствие доверия к Forwarded headers. Подробности и причины выбора — [AUTH](../backend/AUTH.md) и [TOOLCHAIN](../backend/TOOLCHAIN.md).
- `backend/src/test/java/com/hackatlas/`, `backend/src/test/resources/validate_http_contract.py`: HTTP/PostgreSQL tests, экспорт ответов без настоящих токенов, проверка схем/форматов/обязательных operation-status pairs.
- `scripts/b2/`, `tools/bruno-b2/`, `.github/workflows/backend-ci.yml`: изолированный запуск собранного JAR, 26 читаемых Bruno requests и CI. B1 Bruno/Schemathesis сохранены. `api/`, fixtures, mobile, design и STATUS_MOBILE не менялись.

### Выполненные проверки

| Команда / проверка | Фактический результат |
|---|---|
| WSL root, `cd backend && bash ./mvnw --batch-mode --no-transfer-progress verify` | **BUILD SUCCESS; 190 тестов: 9 unit + 181 integration, 0 failures/errors/skipped** |
| `.venv/Scripts/python.exe scripts/validate_contract.py` | OpenAPI 3.1, refs, фазы/opaque auth и все fixtures проходят |
| `validate_http_contract.py --schema api/openapi.yaml --responses backend/target/catalog-contract-responses.json` | **139 реальных HTTP-ответов B1** соответствуют контракту |
| Тот же validator, `--responses backend/target/b2-contract` | **345 реальных HTTP-ответов B2**, все 11 операций и 36 обязательных method/path/status pairs соответствуют контракту |
| `scripts/b1/run_contract_checks.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar` | 12 deterministic HTTP cases, **Bruno 10/10**, **Schemathesis 201/201**, seed 20261020 |
| `scripts/b2/run_contract_checks.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar` | **30 HTTP/schema сценариев + Bruno 26/26**, две учётные записи, отзыв сессии, независимые списки, повторный guest import |
| Argon2id локальный замер Java 21 / WSL | 19 MiB / t2 / p1, 3 warmups + 9 verify samples: **min 20 ms, median 25 ms, max 47 ms**; это локальное измерение, перед публичным запуском параметры проверяются на целевом сервере |
| `backend/target/b2-final-checks.py`, Gitleaks 8.30.1 `git/dir --redact=100` | **77 изменённых/новых файлов, 95 относительных Markdown-ссылок**, scope/text и `git diff --check` проходят; доступная Git-история и изменённые файлы — **no leaks found** |

В независимом review и тестах исправлены два реальных пограничных случая: Spring Security `RequestRejectedException` теперь даёт безопасный 400 вместо регрессионного 500 для плохих query names; длина email повторно проверяется после Unicode lowercase, которое может расширить строку перед PostgreSQL. Проверки firewall и тестовые ожидания не ослаблялись. Отдельно исправлены ошибки самих новых проверок: изменение пароля в unit-тесте внутри surrogate pair и пропущенная JSON-скобка одного Bruno login request.

Логи: `backend/target/b2-verify.log`, `surefire-reports/`, `failsafe-reports/`, `b2-contract/`, `b1-checks/contract-checks.log`, `b2-checks/contract-checks.log`, `b2-argon2-benchmark.log` (все под `backend/target/`, игнорируются Git). В export accessToken заменяется placeholder; Authorization и request bodies не экспортируются.

### Окружение и границы проверки

Проверено в Ubuntu WSL2 с Java 21.0.12.1, Docker 29.1.3 и PostgreSQL 17.11. CLI: Bruno 4.2.1/Node 24.19.0, Schemathesis 4.32.2; локальные инструменты размещены в `/root/.local/share/hackatlas-b2-tools/`, чтобы переживать очистку WSL `/tmp`. Оба runner удалили только собственные временные БД и остановили собственные JVM. Dev volume и БД другого проекта не затрагивались.

Schemathesis проверяет только публичные B1 операции: 201 fuzz cases; request Examples отсутствуют, Coverage/Stateful не входят в выбранный прогон. Для B2 выполнены явные сценарии состояния; B2 fuzzing или полный security audit не заявляются. Обновлённый GitHub Actions workflow ещё не запускался удалённо — нужен пользовательский push; успешный main workflow выше относится к базе.

Регистрация закрыта по умолчанию и всегда при активном prod; private dev/test включается отдельно. Rate limiter — один процесс, сбрасывается при рестарте и не объединяется между экземплярами. Email verification/reset и общая anti-abuse политика нужны до публичного запуска, в отдельном согласованном scope. Админка/удаление аккаунта B3 не реализованы.

Проверен loopback внутри WSL. Внешнего staging URL нет; доступ телефона/другого компьютера и Windows forwarding в B2 не проверялись. Android/iOS и мобильная интеграция не запускались. Результаты из mobile PR — работа напарника, не проверка этой сессии.

### Передача и пользовательский PR

Напарнику: «B2 готов в `feat/backend-auth`, контракт Starter не менялся. Есть register/login/logout, GET/PATCH me, saved и applications. Подключение клиента выполняется после отдельного согласования и передачи доступного base URL; см. [AUTH](../backend/AUTH.md), [запуск](../backend/README.md), [Bruno B2](../tools/bruno-b2/README.md). Токен хранить через native SecureTokenStore; при 401 нужен вход, refresh нет. Guest import — повторяемые PUT с подтверждением каждого ID; аккаунты/кеши не смешивать. Я подал заявку — личная отметка selfReported, не результат организатора». Сообщение подготовлено, автоматически никому не отправлялось.

PR title: **Implement B2 authentication and personal APIs**.

Описание: «Реализованы opaque Bearer sessions, регистрация/вход/выход, профиль, личные закладки и отметки заявок по неизменённому Starter API. Добавлены Argon2id, ограничения попыток входа, миграции V3/V4, тесты изоляции пользователей и коллекция Bruno. Maven: 190 тестов; 484 HTTP-ответа проверены по OpenAPI; Bruno B1/B2 10/10 и 26/26; Schemathesis B1 201/201. Регистрация production закрыта. B3 и мобильная интеграция не входят».

Следующий отдельный серверный этап — **B3**, только после пользовательского review B2 и нового поручения. Ниже сохранены исторические отчёты; их формулировки «B2 не начат» и «auth нет» относятся к прошлым этапам.

## Исторический B1 — публичный каталог завершён и объединён

**B1 выполнен, B2 не начат.** Ветка `feat/catalog-api` создана от B0 `208e097`; в начале задания checkout был чистым. Повторный `git fetch origin` подтвердил, что пользователь уже объединил B0: `origin/main=43b11e4` (merge PR #2), `208e097` входит в его историю. База PR B1 — **main**. Merge/rebase текущей ветки не выполнялся.

Все изменения B1 оставлены **без staging и коммита**. Commit, push и PR делает пользователь; в итоговом отчёте дать готовые команды. Read-only GitHub API повторно подтвердил `yergaliuly` и push-доступ к KDvibers/HackAtlas; Git author отдельно — `softache <itsyergaliuly@gmail.com>`. Никакого push/PR/merge/deployment в этом задании не выполнялось.

### Реализовано

- `backend/src/main/java/com/hackatlas/catalog/`: сущности, DTO/mapping, параметризованные запросы каталога, AND-фильтры, стабильная сортировка, BigInteger pagination, metadata, статусы от Clock и идемпотентный явный DEMO seed.
- `backend/src/main/java/com/hackatlas/web/`, `config/ClockConfiguration.java`: три публичных GET, строгая валидация, безопасный Problem/requestId; фиксированный DEMO Clock разрешён только в dev/test с флагом.
- `backend/src/main/resources/db/migration/V2__create_catalog.sql`: таблицы каталога/тегов/связей, ограничения и индексы, без production seed.
- `backend/pom.xml`: канонические fixtures как classpath resources; новых Java dependencies нет.
- `backend/src/test/`: реальные PostgreSQL/Testcontainers HTTP-тесты, prod DEMO gate, экспорт ответов и проверка JSON Schema 2020-12.
- `tools/bruno/`: 10 запросов, CLI 4.2.1 с package-lock. `scripts/b1/`: pinned Schemathesis 4.32.2, изолированный runner собранного JAR, README/requirements. CI дополнен этими проверками.
- Документация: `backend/CATALOG.md`, README/START_HERE, backend/infra/toolchain, DECISIONS и правила пользовательских commit/push/PR. `api/`, fixtures, mobile, визуальный референс и авторизация не менялись.

### Выполненные проверки

| Команда / проверка | Результат |
|---|---|
| WSL root: `cd /mnt/c/Daniyar/MyProjects/HackAtlas/backend && sh ./mvnw --batch-mode --no-transfer-progress verify` | **BUILD SUCCESS, 91 tests, 0 failures/errors/skipped**, итоговый прогон после всех Java-исправлений |
| Windows: `.\.venv\Scripts\python.exe backend/src/test/resources/validate_http_contract.py --schema api/openapi.yaml --responses backend/target/catalog-contract-responses.json` | **PASS 139 реальных HTTP-ответов**, включая 3 GET/200, list/400/500, detail/400/404 |
| `.\.venv\Scripts\python.exe scripts/validate_contract.py` и `-m pip check` | PASS; неизменённый OpenAPI и все 6 fixtures |
| Изолированный runner на окончательном JAR | PASS offline validator, 12 реальных HTTP schema/semantic сценариев, **Bruno 10 requests / 10 tests** |
| Schemathesis 4.32.2 | **201 generated / 201 passed**, выбраны и проверены ровно 3/17 операций B1; seed `20261020`, max-examples 100. Fuzzing прошёл; Examples не имел request examples и пропустил три операции. Coverage/Stateful не входят в этот прогон |
| GitHub read-only API | Подтверждены `yergaliuly`, репозиторий KDvibers/HackAtlas и write-доступ; Git author проверен отдельно |
| Независимое review и regression checks | Исправлены порядок NEWEST seed, snapshot чтения, U+0000 в query/city/topic и ошибочный 500 при пустом имени query-параметра; итоговые Java tests покрывают исправления |

Логи: `backend/target/b1-verify.log`, `backend/target/failsafe-reports/`, `backend/target/catalog-contract-responses.json`, `backend/target/b1-checks/contract-checks.log`, `backend/target/b1-checks/final-checks.log`. Они игнорируются Git. Helper проверяет именно экспорт HTTP, требует обязательные пары operation/status и завершает работу ненулевым кодом при нарушении схемы/покрытия.

Первый Schemathesis запуск выявил неверные одинарные кавычки в селекторе; использован JSON literal `"B1"`, после чего выбраны ровно три маршрута. Реальный fuzz обнаружил, что Tomcat `InvalidParameterException` при пустом имени query-параметра попадал в общий 500. Добавлена безопасная обработка 400 `VALIDATION_ERROR` и пять HTTP regression cases для malformed/unknown names и повторов. Ошибки не исключались из генерации и checks не отключались ради прохождения.

### Окружение и ограничения

Docker/JVM проверки выполнены в Ubuntu WSL2 root с Java 21 и PostgreSQL 17.11. Bruno использовал Node 24.19.0, Schemathesis — отдельный Python 3.14 venv. CI настроен на Python 3.12; его dependency resolution проверен, но **удалённый GitHub Actions run ещё не выполнялся**. Полный Windows JVM + Docker прогон, Android/iOS и устройства не проверялись.

Runner копирует JAR в собственный Linux temporary directory, чтобы JVM не удерживала исходный файл на Windows mount. Его отдельная PostgreSQL использует случайное имя/owner label и tmpfs, credentials передаются через env. В итоговом прогоне cleanup успешно остановил JVM и удалил только собственный контейнер. Общая dev-БД, её volume и БД другого проекта не использовались и не удалялись.

API проверен по loopback внутри WSL. Отдельная попытка Windows PowerShell подключиться к активному серверу WSL по `127.0.0.1` получила connection refused; Windows localhost forwarding здесь не подтверждён и не настраивался. Для mobile потребуется отдельно проверить доступный host mapping/LAN либо staging. Внешнего staging URL нет, сервер после проверок остановлен.

Команды для нового окружения и locks — в [backend/README](../backend/README.md) и [scripts/b1/README](../scripts/b1/README.md). Фактически выполненный локальный runner из PowerShell:

   ```powershell
   wsl -d Ubuntu -u root --exec /tmp/hackatlas-b1-tools-20261020-326a/venv/bin/python /mnt/c/Daniyar/MyProjects/HackAtlas/scripts/b1/run_contract_checks.py --jar /mnt/c/Daniyar/MyProjects/HackAtlas/backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --node /tmp/hackatlas-b1-tools-20261020-326a/node-v24.19.0-linux-x64/bin/node
   ```

### Финальный scope и передача

`git diff --check` и проверка новых файлов/Markdown-ссылок проходят; staged diff пуст. Gitleaks CLI 8.30.1 проверяет доступную Git-историю и временную копию всех 58 изменённых/новых неигнорируемых файлов, без staging и без чтения реального `.env`: утечек не найдено. Проверку выполняет обычный WSL-пользователь; root Git не доверяет checkout. Повторяемый локальный скрипт проверки сохранён в игнорируемом `backend/target/b1-final-checks.py`. Это проверка распространённых шаблонов, не полный security audit.

Для напарника: «B1 готов локально в `feat/catalog-api`, PR будет в main. Доступны публичные GET `/api/v1/hackathons`, `/api/v1/hackathons/{id}`, `/api/v1/metadata/filters`; Starter не менялся. После пользовательского merge обнови main и используй [CATALOG](../backend/CATALOG.md). DEMO требует явного флага, фиксированная дата fixtures — `2026-10-12T10:00:00Z`; `example.com` не является реальной регистрацией. Базовый URL локального сервера в WSL — `http://127.0.0.1:8080/api/v1`; для твоего устройства/машины нужен отдельно проверенный доступный адрес. Auth ещё нет». Сообщение подготовлено, автоматически никому не отправлялось.

Следующий отдельный этап — **B2: аккаунты и opaque Bearer sessions** по неизменённому Starter. Он не начат; после B1 остановиться и ждать отдельного поручения. Для пользовательского PR: title `Implement B1 public catalog API`; описание — публичный каталог/поиск/фильтры/metadata, V2/явный DEMO, безопасные ошибки и фактические проверки выше. Commit, push и создание PR не выполнялись.

Далее сохранены исторические отчёты; их утверждения о ещё не начатом B1 относятся к предыдущим заданиям.

## Исторический B0 — основа backend завершена локально

Пользователь отдельно поручил **только B0**, со сборкой и тестами. Ветка — `feat/backend-bootstrap`, основа — локальный planning commit `6e6071c`; `origin/main` при начале работы оставался на `0128f7b`. B1 не начат. Push, создание PR, merge и deployment не выполнялись.

### Что изменено

- [backend/pom.xml](../backend/pom.xml), `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`: Java 21, Spring Boot 4.1.1/BOM, Maven 3.9.16, Wrapper 3.3.4, MVC/JPA/Flyway/Actuator, Failsafe и явный Mockito Java agent.
- [HackAtlasApplication.java](../backend/src/main/java/com/hackatlas/HackAtlasApplication.java) и `backend/src/main/resources/application*.yaml`: каркас и раздельные `dev`/`test`/`prod`, безопасный default `prod`, loopback bind, health/liveness/readiness без внутренних деталей. `test` настройки находятся в test resources.
- [V1__create_application_schema.sql](../backend/src/main/resources/db/migration/V1__create_application_schema.sql): только пустая схема `hackatlas`, без доменных таблиц и seed. История Flyway — в `public`; Hibernate — `ddl-auto=validate`.
- [BootstrapIT.java](../backend/src/test/java/com/hackatlas/BootstrapIT.java): реальные PostgreSQL 17.11/Testcontainers проверки миграции, повторного запуска, HTTP health и границы B0.
- [infra/compose.yaml](../infra/compose.yaml), [infra/.env.example](../infra/.env.example), [infra/README.md](../infra/README.md): отдельный dev PostgreSQL с закреплённым digest, пароль вне Git, порт только `127.0.0.1:5432`.
- [backend-ci.yml](../.github/workflows/backend-ci.yml): contract check, Java/PostgreSQL verify и Gitleaks CLI; Actions закреплены SHA, разрешения только `contents: read`.
- [backend/README.md](../backend/README.md), [backend/TOOLCHAIN.md](../backend/TOOLCHAIN.md), README/START_HERE и общие TOOLCHAIN/DECISIONS: версии, команды, результаты и следующая граница. `backend/AGENTS.md` больше не предписывает автоматически выполнять B0+B1.

API и auth-протокол **не изменены**. Каталог, аккаунты, сессии, admin и mobile не реализовывались; `api/`, fixtures, `mobile/`, `STATUS_MOBILE` и визуальный референс сохранены.

### Выполненные проверки

Команды ниже выполнялись 20 октября 2026; результат относится к локальной машине.

| Проверка / команда | Результат |
|---|---|
| Windows `.\mvnw.cmd --version` и WSL `sh ./mvnw --version` с отдельными пустыми кешами | Exit 0; Maven 3.9.16 / JDK 21.0.12.1; загрузки проверены checksum |
| `wsl -d Ubuntu -u root --exec /bin/sh -lc 'cd /mnt/c/Daniyar/MyProjects/HackAtlas/backend && sh ./mvnw --batch-mode --no-transfer-progress verify'` | **BUILD SUCCESS, 22 tests, 0 failures, 0 errors, 0 skipped**, исполняемый JAR создан |
| `.\.venv\Scripts\python.exe scripts/validate_contract.py` | Exit 0; OpenAPI, границы B1/B2, opaque Bearer и все 6 fixtures проходят |
| `.\.venv\Scripts\python.exe -m pip check` | Exit 0; `No broken requirements found` |
| Compose `config --quiet`, `up -d --wait --wait-timeout 60` | Exit 0; отдельный dev PostgreSQL стал healthy |
| `java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev` из `backend/` | Загружен локальный `.env`, применена V1, приложение запустилось на loopback:8080 |
| HTTP smoke собранного JAR | Health/liveness/readiness: 200/UP, без внутренних деталей; `/actuator/env` и `/api/v1/hackathons`: 404 |
| Тот же JAR без профиля и без `HACKATLAS_*` / `SPRING_*` env | Ожидаемый exit 1: выбран `prod`, datasource не может стартовать без JDBC URL; dev `.env` не подхватывается |
| Read-only GitHub API `/user` и repository metadata | Авторизован **yergaliuly**, write-доступ к KDvibers/HackAtlas подтверждён; Git author отдельно: `softache <itsyergaliuly@gmail.com>` |

Интеграционные тесты создали новую PostgreSQL 17.11 на случайном порту, проверили успешную V1, checksum/validation и `migrationsExecuted=0` при повторном вызове. Health root выдаёт только `status` и публичные имена групп `liveness`/`readiness`; дочерние probes — только `status`, даже запрос `showDetails=true` не раскрывает компоненты. Тесты отсутствия доменных таблиц и API относятся к B0 и должны быть заменены соответствующими сценариями при B1.

В процессе проверок устранены три несовместимости: null `Target` в официальном Windows Wrapper (минимальный guard по upstream issue #395), запись `tag@digest` в parser Testcontainers (заменена на `postgres@digest` с тем же digest), динамическое Mockito self-attach на Java 21 (настроен официальный Java agent). Ожидание root health уточнено под стандартные имена публичных probes; точная JSON-проверка сохранена. Итоговый `verify` выполнен после этих исправлений.

Дополнительный smoke выполнялся временным скриптом `backend/target/b0-smoke.py`; он и логи `b0-verify.log`, `b0-dev-smoke.log`, `b0-prod-smoke.log`, Failsafe XML лежат в игнорируемом `backend/target/` и не входят в коммит. Для повторной проверки в новом клоне использовать `verify` и команды запуска/health из backend README.

Финальные проверки изменений:

- Gitleaks CLI 8.30.1: скачанный Linux-архив сверен с upstream SHA-256. `gitleaks git --redact=100 --no-banner --log-opts="--all" .` проверил доступную историю до B0; exit 0, no leaks found. Затем `git diff --cached --no-ext-diff | gitleaks stdin --redact=100 --no-banner` с `set -euo pipefail` проверил staged B0 diff; exit 0, no leaks found. Проверка выполнялась обычным WSL-пользователем, которому доступен Git checkout. Это поиск распространённых шаблонов, не полный security audit.
- `git diff --cached --check` — exit 0. Проверены 63 относительные Markdown-ссылки в изменённых документах, завершающие newline и отсутствие trailing whitespace. Staged scope ограничен 23 файлами B0/документации, real `.env` и `target/` исключены. Unix Wrapper имеет Git mode `100755`.
- Независимый review агентов проверил код, инструкции запуска, CI и соответствие отчёта результатам. Замечания устранены; финальных блокирующих замечаний нет.

Результат сохраняется локальным коммитом `chore(backend): bootstrap B0 foundation`; его hash смотреть через `git log -1 --oneline`. Ветка включает также предшествующий неопубликованный planning commit `6e6071c`.

### Окружение и ограничения

Docker Engine 29.1.3 / Compose 2.40.3 и OpenJDK 21.0.12.1 доступны в Ubuntu WSL2. Полный `verify` выполнен WSL root, поскольку обычный пользователь не имеет доступа к Docker socket. Права пользователей/socket и global Git config не изменялись. В Windows отдельно проверен Wrapper на Temurin 21.0.12.1; полный Windows/JVM + Docker прогон не заявляется.

После smoke приложение штатно остановлено, dev PostgreSQL остановлен через Compose `stop postgres`; volume `hackatlas-dev_postgres-data` и игнорируемый локальный `infra/.env` сохранены. Временные Testcontainers удалены; БД другого проекта не использовалась и продолжает работать.

GitHub Actions проверен локально по YAML, bash-синтаксису и pins, но **ещё не запускался на GitHub**. Required checks и совместный review ожидают пользовательского push/PR. Production deployment, нагрузка, auth, B1-контрактные HTTP tests, Android/iOS в B0 не проверялись. Отдельных unit tests бизнес-логики пока нет: бизнес-логика ещё не реализована.

### Передача напарнику и следующий этап

«B0 готов локально: Java backend запускается, БД и миграции проверены. API Starter и дизайн прежние. Для mobile доступны прежние fixtures и работа M0/M1; готового каталога/base URL для интеграции ещё нет. После публикации пользователем получить новую ветку/коммит и читать backend README при необходимости локального сервера».

Следующая отдельная работа — **B1**, три публичных GET каталога с DTO, фильтрами, PostgreSQL tests, Bruno и Schemathesis. Автоматически к ней не переходить.

Ниже сохранены исторические отчёты; их сведения «B0 ещё не реализован» относятся к предыдущим заданиям.

## Направление и общий план — 20 октября 2026

По просьбе пользователя закреплены продуктовые ориентиры в [PROJECT](PROJECT.md), технологические решения в [TECHNOLOGY_DECISIONS](TECHNOLOGY_DECISIONS.md) и общий план backend/mobile в [ROADMAP](ROADMAP.md). Их чтение включено в корневые AGENTS, START_HERE и WORKFLOW; README ссылается на актуальные документы. В DECISIONS записано принятие направления с сохранением условий выбора кандидатов.

Исследование и документы плана сохраняются одним локальным коммитом в `chore/technology-research`; сообщение — `docs: lock HackAtlas direction and shared delivery plan`, hash доступен через `git log -1 --oneline`. Push и создание PR выполняет пользователь.

Реализация B0/B1, SDK-установки и новые зависимости не входят в это задание. API, fixtures, mobile-код, STATUS_MOBILE и визуальный референс не изменены. Следующая отдельная работа владельца backend — B0; напарника — M0/M1 с остановкой на визуальную приёмку.

Проверки фиксации плана:

- `git diff --check` — exit 0. Python-проверка 11 изменённых Markdown-файлов подтвердила 63 существующие относительные ссылки, завершающие newline и отсутствие trailing whitespace; изменения ограничены документацией.
- Выполнен review границ backend/mobile. Уточнены самостоятельные GitHub-аккаунты разработчиков, выбор своего STATUS, зависимости M2/M3 и отсутствие повторного разрешения внутри уже порученного пакета этапов.
- `java -version` — Temurin 21.0.12.1. `Get-Command java,git,docker,mvn,adb -ErrorAction SilentlyContinue` нашёл Java и Git; Docker/Maven/adb не найдены в PATH. Это не полная инвентаризация установок; Docker engine и mobile SDK не проверены.
- Локальные Git identity и выбранный credential username прочитаны; они совпадают с проверенной в G0 настройкой автора и `yergaliuly`. Новая проверка действующего GitHub-токена не выполнялась; перед пользовательским push аккаунт авторизации проверяется отдельно.
- Тесты приложения не запускались: изменения затрагивают только документы. Публикация и передача изменений в клон напарника ещё не выполнены.

Передача напарнику: «Общий план и технологии закреплены в ROADMAP и TECHNOLOGY_DECISIONS. После публикации пользователем получить ту же версию документов. Начать M0/M1 на Starter fixtures, сохранить дизайн; Ktor-интеграция ждёт B1. Шрифт, navigation и offline DB выбираются по оговорённым проверкам. Работающего base URL пока нет».

## Исследование технологий — 20 октября 2026

Подготовлен [Technology Research & Product Brainstorm](research/2026-10-20-technology-research.md): backend, KMP/Compose, GitHub-инструменты, тестирование, источники событий, эксплуатация, лицензии и продуктовые гипотезы. Рекомендации отделены от принятых решений; реализации и установки новых зависимостей нет.

После `git fetch origin` подтверждено, что G0 находится в `origin/main` (`0128f7b`, merge PR #1). Создана ветка `chore/technology-research` от этой основы. Backend по-прежнему не реализован; следующий отдельный этап — B0. API, авторизация, mobile и дизайн не изменены.

Проверки этого задания: чтение инструкций/контракта и официальных upstream-источников; review рекомендаций; проверка относительных ссылок отчёта и `git diff --check`. Сборки приложения, тесты PostgreSQL, iOS/device-проверки и новые проверки SDK не выполнялись. GitHub push, создание PR и deployment не выполнялись.

Результаты локальной проверки документации:

- `git status --short --branch` — ветка `chore/technology-research`, изменены только этот статус и новый `docs/research/2026-10-20-technology-research.md`.
- `git diff --check` — exit 0, замечаний к отслеживаемому diff нет.
- `git diff --no-index --check -- NUL docs/research/2026-10-20-technology-research.md` — нет whitespace-диагностик; exit 1 соответствует сравнению нового файла с пустым.
- Ad hoc Python-проверка через `.venv/Scripts/python.exe` — 7 относительных ссылок в двух документах существуют; у отчёта нет trailing whitespace и незаменённых служебных ссылок источников, есть завершающий newline.

Ниже сохранён исторический отчёт G0 с окружением и результатами на 12 октября; он не заменяет новую проверку toolchain перед B0.

## Исторический отчёт G0 — 12 октября 2026

**G0 / этап 1: локальная подготовка завершена.** Публикация ветки, PR и совместный review остаются за пользователем и напарником. Реализация backend, БД и авторизации ещё не начата.

Ветка: `chore/starter-setup`. Основа: `origin/main`, commit `7824a4b79d94db816fb31de2a01fa148f4b2680c`. Локальный commit этапа имеет сообщение `chore: prepare Starter specification and G0 checks`; точный hash смотреть через `git log -1 --oneline`.

## Сделано

- Клонирован существующий `KDvibers/HackAtlas`, сохранена его начальная история. До G0 в репозитории был только README.
- Перенесён Starter, обновлены инструкции и README под фактический репозиторий, границы работы и пользовательские push/PR.
- Зафиксировано: только `api/openapi.yaml` + `api/README.md`, opaque Bearer sessions без JWT/refresh. Контракт и fixtures не изменены; новый контракт несовместим со старым Handoff, несмотря на тот же номер 0.1.0.
- Созданы [решения](DECISIONS.md) и [отчёт о расхождениях/окружении](SETUP_AUDIT.md).
- Усилена проверка контракта в `scripts/`, закреплена 21 developer-зависимость, подготовлена локальная игнорируемая `.venv` с Python 3.12.14.
- Через read-only GitHub API подтверждены аккаунт `yergaliuly`, write-доступ и привязка автора первоначального коммита. В local Git config закреплены аккаунт и подтверждённое авторство; global config не менялся.

## Реальные проверки

Все перечисленные команды завершились с exit code 0:

```powershell
.\.venv\Scripts\python.exe -m pip install PyYAML==6.0.3 jsonschema==4.26.0 openapi-spec-validator==0.9.0
.\.venv\Scripts\python.exe -m pip install rfc3986-validator==0.1.1
.\.venv\Scripts\python.exe .\scripts\validate_contract.py
.\.venv\Scripts\python.exe -m pip check
git diff --cached --check
```

- PASS: OpenAPI 3.1.0 / API 0.1.0, 14 paths, 17 operations, 24 schemas; локальные `$ref`, уникальность operationId и параметры путей.
- PASS: границы B1/B2/B3, три публичных маршрута каталога, opaque Bearer и отсутствие refresh.
- PASS: все шесть demo JSON из manifest; date/date-time/UUID/URI/email имеют активные format checkers.
- PASS: семь негативных сценариев в памяти отклонены, включая JWT, refresh, public `/me`, повтор operationId, отсутствие path parameter/x-phase и невалидный URI. Исходные файлы при этих проверках не менялись.
- PASS: `pip check` — `No broken requirements found`.
- PASS: 14 относительных Markdown-ссылок в 24 файлах существуют. Внешние URL и anchors не проверялись.
- PASS: все 15 рабочих файлов `api/`, `design/`, `mobile/` совпадают с файлами Starter по SHA-256; hash референса `fd7dd0a85b8dd39a278481cc43faf96199f8ad098fbad1e885be6ab75c2be1cf`.
- PASS: проверены состав изменений и распространённые шаблоны секретов; `.venv` исключена Git ignore. Это ограниченная проверка, не полноценный security audit.
- Независимое review общих инструкций выполнено; замечание о точности описания исходного START_HERE исправлено.

Для повторного запуска достаточно `.\.venv\Scripts\python.exe .\scripts\validate_contract.py`. Создание окружения на другой машине описано в [scripts/README](../scripts/README.md).

## Не проверено / ещё не реализовано

Нет серверных endpoints, Maven Wrapper, PostgreSQL migrations, backend CI и Java-тестов. Сборка backend, Testcontainers, безопасность работающей авторизации и мобильные/device проверки не запускались. Push, PR, merge и deployment не выполнялись.

JDK 21 и Git доступны. `mvn`, `docker`, `gh` не найдены в PATH. Для B0 нужен Maven Wrapper и доступный Docker/Compose для PostgreSQL/Testcontainers; Docker engine пока не проверен. Общая спецификация проверяется без них.

У четырёх demo-событий в catalog-page.json скопирован текст «AI-хакатон»; это отмечено для совместной правки, схема и fixtures сохранены. GitHub handle напарника и доступ к Mac/Xcode пока не подтверждены.

## Передача напарнику

Подготовлена ветка `chore/starter-setup` со Starter. Использовать только `api/`, opaque Bearer без refresh и `design/reference/approved-reference.png`. Работающего API/base URL ещё нет. M0/M1 можно планировать по общим fixtures после получения пользовательского PR и сверки спецификации.

## Следующий отдельный этап

**B0:** обеспечить Docker/Compose, проверить совместимые стабильные версии Spring Boot 4.x и PostgreSQL 17, создать Java 21 skeleton, Maven Wrapper, окружения, Flyway и базовые проверки. Не реализовывать B2/auth заранее.
