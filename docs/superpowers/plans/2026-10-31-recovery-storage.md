# B4.5 — план локального шифрованного архива

> Исполнение: superpowers:executing-plans, один агент по предпочтению пользователя.
> Commit, push и PR выполняет пользователь; повторное утверждение плана не требуется
> после явного поручения реализовать утверждённый дизайн.

**Цель:** сохранить завершённые backup/export в локальном шифрованном архиве,
проверить извлечение и передать результат существующему восстановлению в карантин.
**Архитектура:** существующие PostgreSQL helpers → строгий private bundle → restic;
извлечение только известных файлов по точному snapshot ID, отдельный ручной review.
**Стек:** Python stdlib, restic 0.19.1 Linux amd64 (BSD-2-Clause), существующие Docker/PostgreSQL/Java.
**Дизайн:** [утверждённый B4.5](../specs/2026-10-31-recovery-storage-design.md).

## Общие ограничения

- Только локальные Linux/WSL paths и synthetic disposable resources. Нет remote storage,
  cron, retention/prune, новых сервисов, changes API/auth/mobile/Java/SQL.
- Checkout уже подходит; новая ветка `feat/backend-recovery-archive` от main `52d265e`.
- Новый CLI принимает явные binary/repository/password-file/source-project/database.
  Password вне repo/input/output; private files; не наследовать RESTIC_* hooks.
- Manifest v1: `formatVersion`, `kind` (`backup` либо `deletions`), `sourceProject`,
  `sourceDatabase`, `createdAt`, `historyComplete=false`, `files` (name/size/sha256).
- Строгие имена: `manifest.json`, `source.deletion-export.json`, для backup также
  `database.dump` и `database.dump.sha256`. Никаких произвольных файлов/путей.
- Локальный лимит dump 1 GiB, export 1 MiB, manifest 16 KiB, checksum 65 bytes;
  большие наборы отвергать, не усекать. Лимит этапа, не production capacity.
- Export-only snapshot сохраняется отдельно от старого dump. Полнота истории
  не доказывается snapshot ID, timestamp, manifest или шифрованием.

## Review focus

- Подмена/незавершённое чтение файла: приватная staged-копия, hash/size и export
  проверяются перед snapshot; ошибка без подтверждения успеха (Task 1/2).
- Symlink/FIFO/hardlink, traversal, занятый путь: отказ, исходные данные и чужие
  файлы не менять; extraction только в новый private directory (Task 1/2).
- Неверный пароль, corrupt repository или partial restic exit: санитизированный
  отказ, без UUID/секретов/ложного успешного snapshot (Task 2/3).
- Архивирование самого пароля или repo: фиксированный allowlist, проверка разделения
  путей и отсутствие наследуемых restic hooks (Task 2).
- Backup старее позднего удаления: новый export-only snapshot, фактический restore
  из извлечённых файлов, draft остаётся false/null, CONNECT закрыт (Task 3).

## Task 1 — bundle и закреплённый инструмент

Файлы: `infra/release/ops/recovery_bundle.py`, `scripts/b4/install_restic.py`,
`scripts/b4/test_recovery_archive.py`.

Интерфейсы: `stage_bundle(directory, project, database, export, backup=None)`;
`validate_bundle(directory, project, database)` возвращает проверенный manifest.
Installer: `--output` explicit path, no overwrite, pinned compressed and binary SHA256.

- [x] Написать тесты strict manifest/export/source/count/hash/header/private paths,
  duplicate keys/oversize/unknown files, password-independent bundle.
- [x] Зафиксировать RED отсутствующего module; реализовать и получить GREEN.
- [x] Сверить официальный release/checksums/license, установить локально, проверить version.

## Task 2 — local restic CLI

Файлы: `infra/release/ops/recovery_archive.py`, тесты Task 1,
`.github/workflows/backend-ci.yml`, `.github/workflows/release-readiness.yml` при необходимости.

CLI: `init`, `store --export [--backup]`, `extract --snapshot --output`, `check`.
Общие `--restic --repository --password-file`; store/extract также explicit source identity.
Чтение snapshot через restic dump allowlisted files, не произвольное извлечение дерева.

- [x] Написать реальные restic tests roundtrip/export-only/wrong password/invalid
  source/output collision/corrupt data/unknown snapshot/no secret output/hook isolation.
- [x] Наблюдать RED; реализовать, проверить GREEN; installer перед обязательной CI suite.
- [x] Не применять forget/prune, не удалять исходные файлы, не вызывать approve.

## Task 3 — настоящая PostgreSQL и HTTPS

Файлы: `scripts/b4/run_rehearsal.py` и новый небольшой
`scripts/b4/recovery_archive_checks.py`, использующий runner.run/temp/project.

- [x] Использовать RED→GREEN реального Archive из Task 2; отдельный mock-only RED
  для wiring не добавлять. Проверить старый dump+export и поздний export настоящей
  репетицией. Это уточнение плана зафиксировано в ledger.
- [x] Полный `check --read-data`; извлечённый dump подать существующему restore,
  поздний export — существующему draft; сохранить все старые negative checks.
- [x] Проверить wrong password/corruption и cleanup только owned synthetic paths.
  Report отдельно фиксирует localOnly/historyComplete=false и IDs снимков, без UUID пользователей.

## Task 4 — документация и итоговая проверка

Файлы: release/scripts runbooks, `docs/PRIVACY.md`, `docs/DECISIONS.md`,
`docs/TECHNOLOGY_DECISIONS.md`, `docs/STATUS_BACKEND.md`, `.gitignore`.

- [x] Обновить инструкции установки/init/store/check/extract и правила ключей,
  ошибок, локальной ограниченности, отсутствия production retention/offsite.
- [x] Maven verify, все Python suites, Starter и fresh HTTP exports, полная расширенная
  HTTPS/restore репетиция, Gitleaks, links/syntax/diff/scope checks.
- [x] Авторский review; отчёт с ограничениями, точными пользовательскими commit/push/PR
  командами. Независимый агент/reviewer не заявляется.

Дополнение по результатам реализации: официальный restic binary имеет dependency
findings и unversioned main module. В Task 4 добавлены `audit_recovery_tool.py`
и пять policy tests, новый fail-closed шаг manual release readiness, security report.
Аудит остаётся BLOCKED, локальная функциональная приёмка не означает его прохождение.
