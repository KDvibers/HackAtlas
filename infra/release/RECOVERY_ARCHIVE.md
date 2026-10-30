# B4.5: локальный шифрованный архив

Этот этап проверяет **только синтетические данные в локальной лаборатории**.
Restic 0.19.1 имеет [открытые security findings](../../docs/research/2026-10-31-recovery-tool-security.md)
и пока не принят для реальных данных. Облако, cron, production retention и ключи
эксплуатации не выбраны. API и текущие команды [backup/restore/review](README.md)
сохранены; новый инструмент не обращается к PostgreSQL и не выдаёт runtime CONNECT.

## Установка и ключ

Нужны Linux amd64/WSL, Python 3.11+, native Linux filesystem и один операторский
Unix user. Права на `/mnt/c` могут не обеспечивать private modes — файлы данных
хранить в Linux home. На Windows напрямую helpers отказывают.

Из корня checkout после сборки backend:

```bash
python3 scripts/b4/install_restic.py --output backend/target/restic-0.19.1
backend/target/restic-0.19.1 version
recovery_private="$HOME/recovery-private"
install -d -m 0700 "$recovery_private" "$recovery_private/keys" "$recovery_private/input"
python3 - "$recovery_private/keys/archive-password" <<'PY'
import os, secrets, sys
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write(secrets.token_hex(32) + '\n')
    output.flush()
    os.fsync(output.fileno())
PY
archive=(python3 infra/release/ops/recovery_archive.py
  --restic "$PWD/backend/target/restic-0.19.1"
  --repository "$recovery_private/restic-repository"
  --password-file "$recovery_private/keys/archive-password")
"${archive[@]}" init
```

Installer проверяет SHA256 официального `.bz2` и распакованного binary по
[pin](ops/restic_tool.py), TLS validation включена. Уже существующий правильный
binary только проверяется; неправильный не перезаписывается. Ничего не ставится
глобально. Лицензия — [BSD-2-Clause](https://github.com/restic/restic/blob/v0.19.1/LICENSE).

Пароль не выводится и не включается в snapshot. Private key-файл находится вне
repository, а входы — вне него и вне самого key-файла. Credentials не передаются
через значение argv. CLI не наследует RESTIC_* и credential hooks; принимает
только absolute local repository path. Не сохранять key в Git, issue, artifact,
общую папку или рядом с архивом в будущем удалённом хранилище. Лабораторная структура
на одной машине пока **не является отдельным местом аварийного восстановления ключа**.

## Сохранение завершённых файлов

`release_project` — уже созданный disposable Compose project из локальной
лаборатории. Не подставлять dev/production project для этой проверки.
Используйте уникальные имена: существующие файлы не перезаписываются.

```bash
python3 infra/release/ops/backup.py --project "$release_project" \
  --database hackatlas --output "$recovery_private/input/first.dump"
python3 infra/release/ops/export_deletions.py --project "$release_project" \
  --database hackatlas --output "$recovery_private/input/first.deletion-export.json"
"${archive[@]}" store --source-project "$release_project" --source-database hackatlas \
  --backup "$recovery_private/input/first.dump" \
  --export "$recovery_private/input/first.deletion-export.json"
```

Ответ содержит **полный `snapshotId`**, `storedAndReadVerified=true`,
`kind=backup`, `historyComplete=false`. Сохраните ID первого снимка как
`backup_snapshot_id`. Подтверждение включает обратное чтение сохранённых файлов.
Snapshot старого dump не содержит будущих удалений.

Более позднюю полную выгрузку сохранить **отдельно**, без нового dump:

```bash
python3 infra/release/ops/export_deletions.py --project "$release_project" \
  --database hackatlas --output "$recovery_private/input/later.deletion-export.json"
"${archive[@]}" store --source-project "$release_project" --source-database hackatlas \
  --export "$recovery_private/input/later.deletion-export.json"
"${archive[@]}" check
```

Сохраните второй `snapshotId` как `deletions_snapshot_id`. `check` выполняет
`restic check --read-data`: читает все данные локального repository. Это проверка
целостности, **не подтверждение полноты истории удалений**. Сбой/таймаут возвращает
ненулевой exit code; успешного JSON при отказе нет. Операторские stdout/stderr
не содержат UUID аккаунтов, содержимого dump или ключа.

## Извлечение и карантин

```bash
"${archive[@]}" extract --source-project "$release_project" --source-database hackatlas \
  --snapshot "$backup_snapshot_id" --output "$recovery_private/old-backup"
"${archive[@]}" extract --source-project "$release_project" --source-database hackatlas \
  --snapshot "$deletions_snapshot_id" --output "$recovery_private/later-history"
python3 infra/release/ops/restore.py --project "$release_project" \
  --input "$recovery_private/old-backup/database.dump" \
  --database hackatlas_restore_unique
python3 infra/release/ops/prepare_restore_review.py \
  --export "$recovery_private/later-history/source.deletion-export.json" \
  --source-project "$release_project" --source-database hackatlas \
  --project "$release_project" --database hackatlas_restore_unique \
  --output "$recovery_private/unique.restore-review.json"
```

Замените `hackatlas_restore_unique` новым target; output paths тоже должны быть
новыми. Только полный 64-символьный snapshot ID: `latest`/короткие ID запрещены.
Каждый набор проверяется по manifest, source identity, allowlist, размеру и SHA256.
Из архива извлекаются только известные regular files; symlinks, extra files и
traversal не допускаются. Новый каталог и файлы private, existing output не меняется.

Итоговый review по-прежнему false/false/null, restored DB — **QUARANTINED**.
Проверить источник, остановку writers, недостающие удаления и approved review нужно
отдельно по [B4.2 runbook](README.md). Команду approve новый CLI не вызывает.
Время manifest/export не является границей commit или доказательством отсутствия
поздних удалений. При потере источника после последнего export остаётся неизвестный
интервал; шифрование его не устраняет.

## Формат и отказ

Проверка source identity сверяет export/manifest с явными аргументами оператора.
Она не удостоверяет происхождение dump: SHA256 подтверждает сохранность bytes,
а не принадлежность исходной БД. Оператор должен получить dump через `backup.py`
из нужного project и сверить результат; чужой корректный dump с подходящей
checksum сам по себе не доказывает правильность выбора источника.

Manifest v1 имеет `formatVersion`, `kind`, `sourceProject`, `sourceDatabase`,
`createdAt`, `historyComplete=false`, `files` с `size`/`sha256`.
Имена фиксированы: `manifest.json`, `source.deletion-export.json`; для `backup`
также `database.dump`, `database.dump.sha256`. Каталог произвольных пользовательских
файлов не принимается. Временные копии private, копируются только явные inputs;
`store` не удаляет и не меняет исходники. Не редактируйте inputs/repository
параллельно с выполнением операции.

Лимиты локального этапа: dump **1 GiB**, export **1 MiB / 10 000 UUID**, manifest
**16 KiB**, checksum **65 bytes**. Превышение — отказ без усечения. Custom-format
header и checksum не заменяют реальный `pg_restore`; его проверяет полная репетиция.

При сбое записи/fsync новый незавершённый output может остаться. Не использовать
его и не пытаться перезаписать повторным запуском: проверить причину и выбрать
новый путь. При сбое `store` snapshot/часть данных могут уже существовать, но
успех не подтверждён. Автоматической очистки, `forget`, `prune` и удаления копий нет.
Temporary private staging/key copy очищаются после завершения процесса; аварийное
завершение ОС может оставить их — это ещё одна причина не использовать лабораторию
для реальных данных и согласовать эксплуатационную cleanup policy отдельно.

## Проверки и ограничения выпуска

```bash
python3 -m unittest discover -s scripts/b4 -p 'test_*.py'
python3 scripts/b4/run_rehearsal.py \
  --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar \
  --output-dir backend/target/b45-unique-rehearsal
python3 scripts/security/audit_recovery_tool.py \
  --restic backend/target/restic-0.19.1 \
  --output-dir backend/target/b45-unique-tool-audit
```

Сейчас последняя команда **ожидаемо блокирует** готовность: код 1 при findings или
unversioned inventory, 2 при неполной/неудачной процедуре. Это не пройденный gate.
Обычный Backend CI проверяет функциональность; manual External release readiness
дополнительно запускает аудит restic даже при провале контейнерного gate.
Workflow автоматически не запускается и ничего не публикует.

До реальных данных нужны исправленный/проверенный инструмент, выбранное независимое
хранилище, отдельные права на запись/чтение/удаление, аварийное хранение ключа,
расписание/оповещение об ошибках и согласованные сроки всех backups/exports/reviews.
Локальные durations не являются production RPO/RTO. Шифрование не защищает от
удаления архива субъектом с соответствующими правами. Эти условия не закрываются B4.5.
