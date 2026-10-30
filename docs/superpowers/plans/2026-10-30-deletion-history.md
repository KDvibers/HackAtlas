# B4.4 Deletion History Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans, one implementer in this session; no reviewer agents or automated commits under the user's instructions.

**Goal:** Атомарно сохранять минимальную квитанцию удаления и готовить private export/неодобренный review для B4.2.

**Architecture:** Одна PostgreSQL транзакция сохраняет receipt вместе с удалением. Отдельные Python CLI читают явный источник и создают private files; runtime не читает журнал. Никакой гарантии независимой доставки, автоматического approval или внешней инфраструктуры.

**Tech Stack:** Java 21/Spring JDBC, Flyway/PostgreSQL 17, Python stdlib; существующие JUnit/Testcontainers, unittest и HTTPS rehearsal.

**Spec:** [утверждённый дизайн](../specs/2026-10-30-deletion-history-design.md).

## Global Constraints

- Новых сервисов и библиотек нет; текущие opaque auth/API/204 остаются.
- До 10 000 UUID / 1 MiB, без усечения. Linux private regular files, без overwrite.
- Только synthetic/disposable БД; существующий dev volume не мигрировать.
- User owns commit/push/PR. B4.3 PR #14 должен merge прежде отдельного PR B4.4.
- Пользователь явно утвердил дизайн и реализацию одним агентом; дополнительный plan-approval/выбор execution не запрашивать повторно.

## Review Focus

- Rollback после INSERT receipt/audit не оставляет ложных квитанций и частичного удаления — Task 1.
- Старый account UUID не относится к повторной регистрации того же email — Task 1.
- Uncommitted/поздний commit и ранний timestamp не должны исчезать из полного snapshot — Task 3.
- Подмена источника восстановленной БД и неверная source identity должны давать отказ — Tasks 2–3.
- Пустой/неполный export и draft с false/null не должны открывать карантин — Tasks 2–3.

## Task 1: атомарная квитанция и минимальные права

**Files:** new `backend/src/main/resources/db/migration/V7__account_deletion_receipts.sql`; modify `auth/AccountDeletionService.java`, `backend/src/test/java/com/hackatlas/ModerationApiIT.java`, `infra/release/database/flyway/afterMigrate.sql`.

**Interfaces:** receipt columns `id uuid PK`, `account_id uuid NOT NULL UNIQUE`, `recorded_at timestamptz NOT NULL`. Нет FK/sequence/PII. Runtime INSERT only.

- [x] Добавить integration tests: success→1 receipt; wrong password/повторная попытка→нет нового receipt; повторная регистрация сохраняет новый account UUID; audit/receipt fault→rollback; concurrent delete→ровно одна квитанция.
- [x] Выполнить RED: `./mvnw ... -Dit.test=ModerationApiIT -DfailIfNoTests=false verify`, ожидаемый отказ отсутствующей таблицы/квитанции.
- [x] Добавить migration, INSERT внутри существующей транзакции и allowlist INSERT.
- [x] Повторить целевой тест GREEN; не ослаблять прежние privacy/fault assertions.

## Task 2: private export и draft review

**Files:** new `infra/release/ops/deletion_history.py`, `export_deletions.py`, `prepare_restore_review.py`; new `scripts/b4/test_deletion_history.py`; `.gitignore` для `*.deletion-export.json`.

**Interfaces:** `validate_export(value, source_project, source_database, now=None) -> dict`; `make_review(export, target_state) -> dict`; `write_private_json(path, value) -> None`. Reader использует существующий `read_private_review`; IDs — `account_ids`, state — `validate_state` из restore_review.

Export exact keys: `formatVersion`, `sourceProject`, `sourceDatabase`, `exportedAt`, `accountCount`, `deletedAccountIds`, `historyComplete`. Последнее всегда false; sourceDatabase `hackatlas`. IDs canonical/sorted/unique; version strict int1, count strict int, no unknown fields; aware UTC timestamp не >5 min future. Экспорт исходной БД с любым database comment restore marker отвергается.

CLI export: `--project --database hackatlas --output`, SQL migrator READ ONLY REPEATABLE READ; `LIMIT 10001` позволяет отказать >10000 без усечения. Snapshot охватывает все committed receipts; без time filter/sequence watermark.

CLI prepare: `--export --source-project --source-database --project --database --output`. Source identity сверяется с явными аргументами; target state совпадает с project/database и QUARANTINED. Draft содержит ровно 9 B4.2 fields, false/false/null, UUID из export. CLI не пишет в БД и не approve.

- [x] Unit RED: strict fields/types/UUID/count/time/identity/limits; draft не проходит validate_review; empty export допустим только как неполная история; exclusive file creation/mode/fsync/regular-file guards.
- [x] Implement helpers/CLI, reuse private reader и safe SQL/common. SQL/result errors не выводят сырые IDs.
- [x] Unit GREEN: `python -m unittest discover -s scripts/b4 -p test_deletion_history.py` на Linux. CLI stdout — только counters/status, без account UUID.

## Task 3: настоящая PostgreSQL и HTTPS/restore

**Files:** `scripts/b4/run_rehearsal.py`, при необходимости focused checks в `scripts/b4/test_rehearsal.py`.

- [x] RED/проверки: реальный HTTP delete оставляет source receipt; runtime INSERT разрешён, SELECT/UPDATE/DELETE/DDL denied; export snapshot исключает uncommitted row, новый полный export включает поздний commit с ранним recorded_at.
- [x] Приватный source export→draft; stdout не содержит IDs. Неправильный source, восстановленная БД, повтор output, malformed/private-file ошибки отвергаются, target остаётся закрытым.
- [x] Восстановить snapshot до delete, подготовить draft через новые CLI; false/null отклоняется approve. Только последующее ручное synthetic review применяет удаление; исходный export не изменён.
- [x] Snapshot fingerprint включает receipt table; сравнение source/restore учитывает, что поздняя source receipt отсутствовала в старом backup. Не копировать внешнюю историю в target скрыто.
- [x] Полный `verify`, 3 HTTP export validators, Python B4/security, canonical validator, полный B4 rehearsal на окончательном JAR. Docker pins неизменны: Trivy повторять только при новом риске, не выдавать старый scan за новый.
- [x] Дополнительный обнаруженный compatibility risk: genuine V6 backup→restore без новой таблицы→V7 migration, карантин сохраняется. RED на безусловном GRANT; callback выдаёт INSERT только при существующей таблице.

## Task 4: документация, проверки и передача

**Files:** `docs/PRIVACY.md`, `docs/STATUS_BACKEND.md`, `docs/DECISIONS.md`, `infra/release/README.md`, `scripts/b4/README.md`, релевантные root README/START_HERE и TECHNOLOGY_DECISIONS.

- [x] Записать только фактический результат, полный export/false draft и ручную границу полноты; отдельный purpose-limited receipt не считать универсальным outbox.
- [x] Self-review scope/API/permissions/rollback/privacy/file limits и пяти Review Focus; без дополнительных агентов по предпочтению пользователя.
- [x] `git diff --check`, local links/UTF-8, Gitleaks history+changed files, no staged/secrets/PII; обновить только backend STATUS.
- [x] Предоставить команды commit/push/PR, английский title/русское описание; ничего не коммитить/пушить и не переходить к внешней инфраструктуре.
