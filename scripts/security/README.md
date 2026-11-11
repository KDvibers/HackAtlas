# Проверка зависимостей перед выпуском

Используется публичный [OSV API](https://google.github.io/osv.dev/post-v1-querybatch/). Запрос содержит только имена Maven packages и разрешённые версии, без исходников, токенов, пользовательских данных и `.env`. Дополнительная библиотека Python не требуется. Проверяется фактический runtime graph Maven, а не только прямые зависимости в POM.

Из корня репозитория, после успешного `verify`:

```bash
cd backend
bash ./mvnw --batch-mode --no-transfer-progress dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
cd ..
python3 scripts/security/check_maven_dependencies.py --dependencies backend/target/runtime-dependencies.txt --output backend/target/dependency-audit.json
```

При наличии известных уязвимостей, недоступном API, пустом inventory или неполном ответе команда завершается ошибкой. Pagination обрабатывается до конца; нельзя заменять ошибку сети утверждением об отсутствии уязвимостей. Отчёт содержит дату и IDs обнаруженных advisory. Успех означает отсутствие совпадений в OSV на момент проверки: это не анализ достижимости, не полный security audit и не проверка OS/JRE/base images. Перед реальным deployment требуется повторить проверку с актуальными базами и отдельно проверить контейнерные образы.

Автообновление dependencies, bot PR и automerge не включены. Обновление совместимого Boot/BOM требует сборки и регрессионных проверок; контракт и авторизацию ради advisory не менять.

## Проверка образов и готовности внешнего выпуска

`run_container_audit.py` использует закреплённый официальный Trivy **0.76.1**. Нужны Linux/WSL, Docker и успешный отчёт свежей B4-репетиции. Приложение, proxy и PostgreSQL проверяются по тем образам, которые реально участвовали в репетиции. Включать приложение или публиковать порты этот скрипт не будет.

Из корня репозитория после успешного `verify`:

```bash
python scripts/b4/run_rehearsal.py --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar --output-dir backend/target/release-rehearsal
python scripts/security/run_container_audit.py \
  --rehearsal-report backend/target/release-rehearsal/report.json \
  --output-dir backend/target/container-audit \
  --postgres-alpine-without-gosu
```

Для повторного запуска выбирайте новые output directories. Предыдущие evidence не перезаписываются. `.env`, реальные БД и Docker socket внутрь сканера не передаются. Scanner/DB загружаются по сети, затем три архива `docker save` сканируются с `--network none`, read-only input, без root capabilities. Архивы создаются во временном каталоге `0700`; каждый отдельный файл доступен сканеру для чтения через bind mount. Кэш DB находится в собственном Linux volume; Java index распаковывается на диск, поскольку tmpfs 1 GiB недостаточен. После завершения удаляются только созданные этим запуском cache volume и архивы; JSON и диагностические логи сохраняются.

После прерывания Docker/WSL собственный cache volume может остаться. Для продолжения можно передать `--cache-volume hackatlas-audit-<32-hex-uuid>`: его имя и ownership label проверяются, затем Trivy сам проверяет актуальность DB. Чужие volumes не принимаются. После успешного доступа к Docker кэш очищается с той же проверкой владельца; диагностические ошибки очистки не подменяют исходную причину сбоя. На одну загрузку установлена внешняя граница 10 минут.

`audit-manifest.json` связывает проверенный JAR, project репетиции, image reference/ID, SHA-256 Docker archive, config digest и SHA-256 каждого Trivy report. Config digest вычисляется по содержимому Docker archive: он может отличаться от Docker OCI index ID. Фактический image ID контейнера репетиции также проверяется. Это локальная цепочка evidence, не подписанная supply-chain attestation.

`container_policy.py` проверяет целостность evidence, версию scanner/schema, наличие OS и language inventories, свежесть DB (48 часов), Java index (7 дней), evidence/reports (24 часа). Timestamp больше чем на 5 минут в будущем отвергается. Сам JRE сверяется через `java -version`: минимальная версия политики — **Temurin 21.0.13+9**, опубликованная upstream 22 октября. Trivy не распознаёт этот tar-installed JRE как OS package, поэтому нулевой JAR scan не закрывает runtime-проверку.

Коды завершения:

| Код | Значение |
|---|---|
| `0` | Container gate пройден: полные evidence, нет HIGH/CRITICAL/UNKNOWN, JRE соответствует минимуму |
| `1` | Анализ завершён, но есть блокирующие findings или устаревший JRE |
| `2` | Анализ/проверка evidence не завершены; результата «без уязвимостей» нет |

`release-readiness.json` сохраняет **все** findings. Suppressions, `ignore-unfixed`, автоматическое принятие риска и широкие исключения отсутствуют. Даже при `containerGatePassed=true` поле `publicReleaseReady` остаётся false: инфраструктура, privacy recovery, M4 и разрешение deployment проверяются отдельно. Версионные совпадения не доказывают эксплуатацию; известные контекстные пояснения находятся в [исследовании](../../docs/research/2026-10-26-release-security.md).

Пакеты без определённой версии записываются в `unversionedPackages` и блокируют gate до проверки покрытия. Запись `caddy` с PURL `pkg:golang/caddy` без версии сопоставляется только с присутствующим в Go inventory прокси версионированным модулем `github.com/caddyserver/caddy/v2`. Отчёт показывает оба факта в `coverageNotes` и `versionedCaddyModule` и сохраняет находки по самому модулю и его зависимостям. Правило требует точного имени и PURL обёртки и известной версии канонического модуля.

Повторная проверка готовых JSON без повторного скачивания/сканирования:

```bash
python scripts/security/container_policy.py --evidence backend/target/container-audit
```

### Релизный PostgreSQL без gosu

B4 собирает [образ PostgreSQL](../../infra/release/postgres/Dockerfile) из
закреплённого Alpine 3.24 для Linux amd64. Он сохраняет ID двух стадий
сборки. Для образа `<проект-репетиции>-postgres:local` используйте
`--postgres-alpine-without-gosu`. Этот же флаг передаёт
`External release readiness`. Для повторного аудита старых запусков
Bookworm доступен прежний `--postgres-without-gosu`.

Trivy получает архив по точному ID образа из репетиции. Аудит сверяет
цепочку слоёв от закреплённого Alpine через стадию пакетов к образу
под `70:70`. Из двух контейнеров, созданных без запуска, он получает
инвентари стадии пакетов и релизного образа. Сравниваются пути, хеши,
права, владельцы и ссылки. Допускается только удаление
`/usr/local/bin/gosu`, единственного ELF с метками Go в стадии пакетов.

`postgres-no-go.json` сохраняется рядом с отчётами. При повторной проверке
политика сверяет хеш доказательства, ID образа и контейнера из репетиции,
config digest, SHA-256 архива и отчёта Trivy и заново сравнивает инвентари.
Для Alpine также сверяются ID стадии пакетов и закреплённые версии
`krb5-libs`, `libxslt` и `zlib` в системном инвентаре Trivy.
Правило покрывает отсутствие Go-инвентаря только для проверенного образа.
Системные находки продолжают блокировать выпуск. Связь артефактов основана
на данных локального Docker daemon и хешах.

Неполная повторная проверка заменяет прежний readiness-результат на blocked, чтобы устаревший PASS не сохранился после ошибки. Всегда учитывайте exit code и `checkedAt`.

В GitHub Actions добавлен отдельный ручной workflow **External release readiness** (`workflow_dispatch`). Он выполняет Maven verify, OSV, локальный HTTPS/restore, container gate и аудит recovery tool; известные blockers завершают его ошибкой. JSON/log evidence сохраняются на 7 дней, в том числе при blocked. Workflow ничего не разворачивает. Обычный Backend CI проверяет также unit tests политики; его успешный статус не заменяет ручную проверку внешнего выпуска.

## Операторский инструмент B4.5

`audit_recovery_tool.py` проверяет SHA-256 установленной пересборки restic.
Закреплённый Trivy сканирует бинарник в режиме `rootfs` с input только для
чтения, без сети и Docker socket. Перед проверкой загружается свежая база
уязвимостей. Политика требует полный инвентарь и версии `v0.19.1` для
модуля restic и `v1.27.4` для Go stdlib.

```bash
python scripts/security/audit_recovery_tool.py \
  --restic backend/target/restic-0.19.1-rebuilt \
  --output-dir backend/target/recovery-tool-audit-unique
```

Код 0 означает, что аудит зависимостей прошёл. Код 1 означает
блокирующие находки или расхождение инвентаря, код 2 — незавершённую
проверку. Для каждого запуска нужен новый каталог отчёта. Ручной
workflow проверяет тот же бинарник, который использовался в репетиции,
даже при ошибке контейнерного аудита. В артефакты входят отчёты и
сводки пакетов. Допуск к staging рассматривается отдельно.
