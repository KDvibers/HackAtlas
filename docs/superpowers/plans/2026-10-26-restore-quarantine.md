# B4.2 Restore Quarantine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Пользователь явно выбрал работу одним агентом; commit/push/PR выполняет сам.

**Goal:** Восстановленная БД закрыта до подтверждённого повторного применения удалений; открытие и удаления атомарны.

**Architecture:** Существующий restore сохраняет deployment-local JSON state в comment новой БД. Новый операторский CLI валидирует приватный review и в одной транзакции повторяет удаления, очищает sessions и выдаёт runtime CONNECT. Приложение, API и миграции не меняются.

**Tech Stack:** Python stdlib, PostgreSQL 17, существующий Docker Compose и B4 test runner.

**Spec:** [утверждённый дизайн](../specs/2026-10-26-restore-quarantine-design.md).

## Global Constraints

- Только локальные синтетические данные; source DB не изменяется ops-командами восстановления/одобрения.
- Только новые `hackatlas_restore_*`; при отсутствии достоверного списка удалений БД остаётся закрытой.
- Операторская полнота и остановка всех writers подтверждаются вручную; автоматического независимого журнала не строим.
- Не добавлять зависимости, менять Java auth/API, canonical migrations, mobile или STATUS_MOBILE.
- Никаких commit/push/PR/deploy автоматически; текущая отдельная feature branch используется без нового worktree.

## Review Focus

- Replay review от другой попытки восстановления: bind project/database/backup SHA/restoreId и актуальность timestamp.
- Неоднозначные/публичные review files: duplicate JSON keys, неправильные права/symlink, дубли UUID.
- Повторный Flyway не должен открывать CONNECT; повторное одобрение уже открытой БД запрещено.
- Ошибка DELETE/audit должна откатывать и удаления, и открытие доступа; нет промежуточного окна.
- UUID отсутствует в snapshot: допускается ноль удалённых строк; после успешного одобрения source fingerprints неизменны.

## Task 1: Контракты review и карантин restore

**Files:** create `infra/release/ops/restore_review.py`, `scripts/b4/test_restore_review.py`; modify `infra/release/ops/restore.py`, `scripts/b4/run_rehearsal.py`.

**Interfaces:** `validate_review(review: dict, state: dict, *, now: datetime) -> list[str]`; `read_private_review(path: Path) -> dict`; `target_database(value: str) -> str`. Restore JSON: state `QUARANTINED`, `runtimeAccessGranted=false`, `readyForSmoke=false`, `requiresPrivacyReview=true`, project/database/backup hash/restoreId/restoredAt.

- [x] Write/run the real quarantine regression first: after current restore, runtime CONNECT must be denied. Expected RED: existing implementation grants CONNECT.
- [x] Write review validation tests before implementation: identity mismatch, confirmations, UTC chronology, invalid/duplicate UUID, duplicate JSON keys, private file permissions and symlinks. Expected RED while contract is absent; tests assert real validation/file effects.
- [x] Implement stdlib helper and restore metadata/ACL changes. Preserve source database and revoked-session count.
- [x] Run all B4 Python tests and verify real denied runtime connection before review. Expected: schema checks remain possible as migrator; runtime stays denied.

## Task 2: Атомарное операторское одобрение и сквозной сценарий

**Files:** create `infra/release/ops/approve_restore.py`; extend `restore_review.py`, `scripts/b4/run_rehearsal.py` and validation tests.

**Interfaces:** review JSON keys exactly `formatVersion`, `project`, `database`, `backupSha256`, `restoreId`, `sourceWritesStopped`, `deletionsReviewed`, `reviewedThrough`, `deletedAccountIds`. `formatVersion=1`, confirmations strict true, UUID list <=10,000; timestamp >=restoredAt, <=now+5min and <=24h old. `approval_sql(database, state, ids, reviewed_through) -> str` returns one transaction.

- [x] Extend real scenario: backup→API delete→stop owned app writers→restore→denied connection; missing/corrupt/mismatched reviews refused. Expected RED before approval exists.
- [x] Implement CLI with private JSON reading and a single transaction: advisory lock, fresh metadata equality check, DELETE by UUID with FK cascades, minimal ACCOUNT_DELETED audit, session purge, approval comment, GRANT CONNECT.
- [x] Add real audit-constraint fault: failed approval leaves two accounts, closed CONNECT and quarantined state. Remove test-only fault only in owned disposable target, then approve valid review.
- [x] Assert deleted account login 401, retained account new login 200, old tokens 401, source fingerprints unchanged; repeat approval refused. Expected GREEN complete HTTPS/restore scenario.

## Task 3: Документация и итоговая проверка

**Files:** `.gitignore`, `scripts/b4/README.md`, `infra/release/README.md`, `docs/PRIVACY.md`, `docs/DECISIONS.md`, `docs/STATUS_BACKEND.md`; backend CI only if needed for discovering new tests.

- [x] Document quarantine/approval commands, review template with confirmations false, write freeze and manual-completeness limit. Ignore real `*.restore-review.json` files. Describe requirements for future scheduled/encrypted/offsite backups without provisioning or invented retention guarantees.
- [x] Run full Python suites, canonical validator, Maven verify and HTTP exports, one final B4 rehearsal; inspect actual output and only repeat after changes/failures.
- [x] Self-review permissions, transaction rollback and sensitive output; run Gitleaks/scope/diff checks. User's single-agent preference overrides automatic reviewer dispatch.
- [x] Update backend status with exact commands/results/limits and user commit/push/PR commands. Preserve all local changes uncommitted.

## Execution decisions

- Пользователь явно утвердил дизайн и поручил продолжить реализацию: дополнительный промежуточный approval плана не запрашивается. План конкретизирует утверждённый подход, не расширяет его.
- Ledger и длинные логи хранятся в игнорируемом `backend/target/b42-*` по существующему соглашению проекта; отдельный scratch/worktree и агенты не создаются.
