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
python scripts/security/run_container_audit.py --rehearsal-report backend/target/release-rehearsal/report.json --output-dir backend/target/container-audit
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

### Локальный кандидат PostgreSQL без gosu

Для [кандидата из PR #45](https://github.com/KDvibers/HackAtlas/pull/45) сначала
нужен успешный B4-прогон с локальным тегом `postgres:…` в отдельной копии Compose.
Обычный release Compose используется как источник закреплённого Bookworm.
Проверка из основного checkout запускается так:

```bash
python scripts/security/run_container_audit.py \
  --rehearsal-report backend/target/b4-pg-nogosu-rehearsal/report.json \
  --output-dir backend/target/b4-pg-no-go-audit \
  --postgres-without-gosu
```

Сканер сохраняет архив по ID образа из репетиции. Из двух временных контейнеров,
созданных без запуска, он получает инвентари итоговых файловых систем базы и
кандидата. Сравниваются пути, хеши файлов, права, владельцы и ссылки. У
кандидата должен быть тот же закреплённый родитель, `USER 999:999` и только
одно удаление, `/usr/local/bin/gosu`. В исходном инвентаре это единственный
ELF с признаками Go, в кандидате таких записей нет.

`postgres-no-go.json` сохраняется рядом с отчётами. При повторной проверке
политика сверяет хеш доказательства, ID образа и контейнера из репетиции,
config digest, SHA-256 архива и отчёта Trivy и заново сравнивает инвентари.
Этот путь принимает отсутствие Go-инвентаря только при полном Debian-отчёте
и корректном доказательстве. Для обычного PostgreSQL Go-инвентарь обязателен.
Находки Debian продолжают блокировать выпуск. Связь артефактов основана на
данных локального Docker daemon и хешах, отдельной подписи доказательства нет.

Неполная повторная проверка заменяет прежний readiness-результат на blocked, чтобы устаревший PASS не сохранился после ошибки. Всегда учитывайте exit code и `checkedAt`.

В GitHub Actions добавлен отдельный ручной workflow **External release readiness** (`workflow_dispatch`). Он выполняет Maven verify, OSV, локальный HTTPS/restore, container gate и аудит recovery tool; известные blockers завершают его ошибкой. JSON/log evidence сохраняются на 7 дней, в том числе при blocked. Workflow ничего не разворачивает. Обычный Backend CI проверяет также unit tests политики; его успешный статус не заменяет ручную проверку внешнего выпуска.

## Операторский инструмент B4.5

`audit_recovery_tool.py` проверяет закреплённый restic binary, а не исходники Java
и не контейнеры приложения. Использует тот же pinned Trivy, `rootfs` для Go binary,
свежую advisory DB, read-only input, offline scan без Docker socket и отдельный
owned cache с проверяемой очисткой. `filesystem` scan без Go inventory не считается
успешной проверкой. Версии main module/stdlib и findings должны присутствовать в
достаточном inventory; unversioned packages не получают молчаливого исключения.

```bash
python scripts/security/audit_recovery_tool.py \
  --restic backend/target/restic-0.19.1 \
  --output-dir backend/target/recovery-tool-audit-unique
```

Exit 0 — dependency gate, 1 — findings/inventory gap, 2 — незавершённая процедура.
`publicReleaseReady` всегда false: этот audit не разрешает deployment. Новый output
directory обязателен, старое evidence не перезаписывается. Restic 0.19.1 сейчас
заблокирован для реальных данных: [фактический отчёт](../../docs/research/2026-10-31-recovery-tool-security.md).
Manual workflow запускает этот audit и при провале предыдущего container gate;
только package evidence попадает в artifacts. Private snapshots/ключи не публикуются.
