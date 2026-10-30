# B4.5: безопасность операторского restic

Дата: 31 октября 2026 (Asia/Qyzylorda; время проверки в UTC — 30 октября).
**Функциональная локальная проверка разрешена на synthetic data. Для реальных
данных и внешней эксплуатации текущий binary не принят.**

## Проверяемый инструмент

- Официальный [restic v0.19.1](https://github.com/restic/restic/releases/tag/v0.19.1),
  latest stable на момент проверки, опубликован 5 июля 2026; BSD-2-Clause.
- Linux amd64 asset `restic_0.19.1_linux_amd64.bz2`, SHA256
  `f415415624dcc452f2a02b8c33641791a8c6d6d3b65bbb3543fcf9a25151585c`.
  Сверен с официальным SHA256SUMS и digest release API; detached GPG signature
  отдельно не проверялась. Это pin доверенного HTTPS-источника, не заявление о GPG verification.
- Распакованный binary: **30 941 346 bytes**, SHA256
  `20d4142678d0d95ec11a4759def1b73fd9190abc9ca19e4b62d067c0b387e639`.
  Реальный вывод `version`: restic 0.19.1, **Go 1.26.4**, linux/amd64.
- [Installer](../../scripts/b4/install_restic.py) и [pin](../../infra/release/ops/restic_tool.py)
  проверяют эти hashes; приложение, Java BOM и Docker pins не меняются.

## Фактический Trivy scan

Trivy 0.76.1, pinned image
`aquasec/trivy@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c`.
Advisory DB `UpdatedAt=2026-10-30T19:07:50.77378237Z`.
Сканируется **этот binary**, read-only bind, без Docker socket; download advisory
DB выполняется отдельно, сам scan — `--network none --offline-scan`, режим `rootfs`.
Owned scanner containers/cache удаляются после проверки с проверкой labels.

| Наблюдение | Результат |
|---|---:|
| Go package inventory | 81 |
| CRITICAL | 0 |
| HIGH | 22 |
| MEDIUM | 17 |
| LOW | 2 |
| UNKNOWN | 3 |

Версия main module `github.com/restic/restic` отсутствует в Trivy inventory.
Она не подставляется молча из названия файла; inventory gap тоже блокирует gate.
По документации Trivy Go scanning анализирует зависимости, а не доказывает
отсутствие уязвимостей в основном приложении.

Примеры matches: `github.com/klauspost/compress v1.18.6` — CVE-2026-63209,
fixed 1.18.7; `golang.org/x/crypto v0.52.0` — CVE-2026-56854, fixed 0.55.0;
`golang.org/x/net v0.55.0` — CVE-2026-46600, fixed 0.56.0.
Counts — совпадения package/advisory, не число доказанно эксплуатируемых путей.
Reachability/govulncheck не выполнялись, риск не принят и findings не подавлялись.

Первый пробный `filesystem` scan не обнаружил Go packages; он **отвергнут как
неполное evidence**, не записан как zero vulnerabilities. Официальные
[правила Go scanning](https://trivy.dev/docs/dev/coverage/language/golang/)
и [реализация режимов](https://github.com/aquasecurity/trivy/blob/main/pkg/commands/artifact/run.go)
объясняют использование `rootfs` для compiled binaries.

## Воспроизводимость и решение

Команда из корня checkout (Linux/WSL с Docker):

```bash
python3 scripts/security/audit_recovery_tool.py \
  --restic backend/target/restic-0.19.1 \
  --output-dir backend/target/recovery-tool-audit-unique
```

[Audit helper](../../scripts/security/audit_recovery_tool.py) сохраняет inventory,
matches, binary/report SHA256, scanner pin, время и свежесть DB. Exit 1 означает
blocked dependency gate/inventory gap, exit 2 — незавершённую процедуру.
Он подключён к [manual release workflow](../../.github/workflows/release-readiness.yml);
старые контейнерные проверки сохранены. Обычный Backend CI по-прежнему не является
разрешением выпуска. Публичные audit artifacts содержат данные пакетов, не private
dump/export/password; сами recovery-файлы в artifacts не включаются.

Локальные evidence: ignored `backend/target/b45-restic-security-rootfs/` и
`backend/target/b45-restic-final-audit/`. Отдельный старый контейнерный аудит B4.3
не перезапускался и не заменяется этим исследованием.

Следующий security-шаг перед эксплуатацией — проверить исправленный официальный
release и повторить audit/restore либо отдельно согласовать иной инструмент или
поддерживаемую сборку. Самостоятельная пересборка Go/зависимостей в этот этап не
входит. Независимое storage, ключи и retention тоже требуют отдельного решения.
