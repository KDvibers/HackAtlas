# B4: локальная репетиция выпуска

Это подготовленный кандидат окружения Caddy → Java 21/Spring Boot → PostgreSQL 17.
Текущее поручение разрешает локальную проверку. Внешний staging, DNS, хостинг,
публичная регистрация и публикация требуют отдельного решения. Доступный staging
и совместная приёмка M4 остаются отдельными пунктами B4.

**Security gate пока не пройден:** фактический Trivy scan обнаружил HIGH findings,
в том числе в зависимостях Caddy base. На 26.10.2026 проверенный upstream latest —
тот же v2.11.7; повторный build не обновляет встроенные Go/x/net. Этот stack разрешено
использовать для текущей локальной репетиции, но нельзя считать готовым к внешнему
выпуску. Находки, версии исправлений и область проверки записаны в
[`docs/research/2026-10-26-release-security.md`](../../docs/research/2026-10-26-release-security.md).

## Состав и закреплённые образы

| Компонент | Источник образа | Назначение |
|---|---|---|
| Java runtime | `eclipse-temurin:21.0.12.1_1-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c` | Только JRE и уже проверенный JAR |
| PostgreSQL | `postgres:17.11-bookworm@sha256:858bb5e98eed02eb8cafbcdc007f9a65d1b613972028944a8ec2133ccaf80d27` | База, клиенты backup/restore и подготовка собственных volumes |
| Caddy base | `caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f` | HTTPS и reverse proxy |

Digest фиксирует содержимое, tag показывает версию. Caddy собирается из этого
официального образа с единственным изменением `setcap -r /usr/bin/caddy`:
file capability официального бинарника мешает запуску с `cap_drop: ALL`.
Внутри контейнера используются непривилегированные порты 8080/8443. Дополнительные
пакеты не устанавливаются. Java Dockerfile копирует только JAR из `backend/target`;
исходники, Maven, `.env` и секреты в build context не попадают.

Источники проверки версий:
[официальный Caddy tag](https://hub.docker.com/v2/repositories/library/caddy/tags/2.11.7-alpine),
[официальный Temurin tag](https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/21.0.12.1_1-jre-noble),
[официальный PostgreSQL tag](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm),
[Temurin Dockerfile](https://github.com/adoptium/containers/blob/main/21/jre/ubuntu/noble/Dockerfile).

На 26.10.2026 опубликован [Temurin 21.0.13+9](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9),
но официальный Docker tag `21.0.13_9-jre-noble` ещё отсутствует (404; поиск tags
по `21.0.13` пуст). `21-jre-noble` и upstream Dockerfile пока содержат 21.0.12.1+1.
Следовательно, закреплённый JRE отстаёт от свежего security release; это отдельный
blocker внешнего выпуска. Trivy не распознал установленный из tar JRE как отдельный
проверенный компонент: отсутствие Java-строки в CVE report не означает отсутствие
уязвимостей JRE. После появления проверенного image требуется обновить digest и
повторить rehearsal и проверку зависимостей.

PostgreSQL 17.11 остаётся [актуальным minor в ветке 17](https://www.postgresql.org/support/versioning/).
Закреплён свежий официальный build образа от 21.10.2026; index digest сверён с
registry manifest и Docker Hub metadata. Это обновление образа той же версии БД,
которое также требует повторной проверки миграций и восстановления.

## Изоляция и доступ

Только `proxy` публикует порты. `app` и `postgres` доступны внутри Compose networks;
сети `frontend` и `database` помечены `internal`. Base-файл содержит будущую схему
80/443; **для всех локальных запусков обязательно добавлять `compose.local.yaml`**.
Он заменяет bindings через `!override` на `127.0.0.1`, поэтому нужен Docker Compose
не ниже 2.24.4. Фиксированных `container_name` нет: уникальный project отделяет
сети, volumes и контейнеры каждой репетиции.

Java и Caddy работают под UID/GID 10001, PostgreSQL — 999. У долгоживущих
сервисов read-only rootfs, `cap_drop: ALL`, `no-new-privileges`, ограниченные tmpfs,
CPU, память, PID и размер Docker logs (3 × 10 MiB). Единственный root-процесс —
короткий `init-volumes`: без сети, только с правами смены владельцев трёх volumes
этого project. Он ничего не удаляет. Лимиты ресурсов — стартовые настройки для
локальной проверки, а не результат нагрузочного теста.

| Роль БД | Права |
|---|---|
| `hackatlas_admin` | Bootstrap, создание отдельной БД восстановления, backup; superuser, приложение её не получает |
| `hackatlas_migrator` | Владение схемой/таблицами, Flyway и grants; без superuser, CREATEROLE и CREATEDB |
| `hackatlas_runtime` | Явный список DML-прав из `database/flyway/afterMigrate.sql`; без DDL и Flyway history |

Runtime читает `admin_access`, но не может выдавать права администратора.
В `admin_audit` разрешён только INSERT. Удаление связанных данных аккаунта
выполняют существующие FK cascades. Новая таблица не получает runtime-права
автоматически: её отдельно добавляют в callback после review.

Backend использует `prod,release`; signup, DEMO и runtime Flyway выключены.
Отдельный сервис `migrate` запускает тот же JAR с migrator credentials,
`HACKATLAS_MIGRATION_ONLY=true`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none` и
`SPRING_FLYWAY_ENABLED=true`. После миграций процесс завершается. Callback
`afterMigrate.sql` выдаёт runtime-права; версии V1–V6 не меняются.

Forwarded-заголовкам backend не доверяет, proxy их удаляет. Redirects backend
относительные, admin cookie Secure. Поэтому IP rate limit видит адрес proxy и
общий для проходящих через него пользователей; лимит по аккаунту сохраняется.
Перед внешним масштабированием нужно отдельно решить передачу доверенного IP
и распределение rate limits; здесь эти ограничения не скрыты.

## Секреты

Хранить каталог вне Git в **native Linux filesystem**, например под личным
каталогом WSL, с mode 0700. Три независимых пароля создаёт helper; он печатает
только путь/число файлов. Пароли — 64 случайных hex-символа, файлы не перезаписываются.

```bash
python3 infra/release/ops/prepare_secrets.py --directory /absolute/private/new-release-secrets
```

Внутри находятся `postgres-admin-password`, `migrator-password`, `runtime-password`.
Сами файлы имеют mode 0444, доступ с host ограничивает parent 0700. Это необходимо,
поскольку file-backed Compose secrets — bind mounts: `uid/gid/mode` в Compose не
перевладевают исходным файлом для UID10001/999. Каждый сервис получает только нужные
файлы read-only; PostgreSQL получает все три для первоначального создания ролей.
Spring читает password через configtree `/run/secrets/`, не через Docker environment.
Не переносить каталог в Windows checkout или в image. Docker/socket/root доступ
на host остаётся административным доступом ко всему окружению.

## Локальный запуск после успешной сборки и тестов

Все команды ниже выполнять из корня checkout в Linux/WSL. Порты должны быть
свободны. В этой последовательности используются только loopback bindings и
локальный CA; никаких запросов к публичному ACME нет.

```bash
export HACKATLAS_APP_IMAGE=hackatlas-backend:b4-local
export HACKATLAS_PROXY_IMAGE=hackatlas-proxy:b4-local
export HACKATLAS_SECRET_DIR=/absolute/private/new-release-secrets
export HACKATLAS_SITE=localhost
export HACKATLAS_HTTP_PORT=18080
export HACKATLAS_HTTPS_PORT=18443
export HACKATLAS_DB_NAME=hackatlas
release_project=hackatlas-b4-local

compose=(docker compose --env-file /dev/null --project-name "$release_project"
  -f infra/release/compose.yaml -f infra/release/compose.local.yaml)
"${compose[@]}" config --quiet
"${compose[@]}" build app proxy
"${compose[@]}" up --detach --wait postgres
"${compose[@]}" --profile ops run --rm migrate
"${compose[@]}" up --detach --wait app proxy
```

Не запускать runtime до успешного `migrate`. Его сбой — основание остановить выпуск
и разобраться с причиной, сохранив предыдущий backup и существующую БД. Повторный
успешный Flyway запуск должен подтвердить отсутствие pending migrations. Если
приложение не стартовало, сначала проверять health/exit status; не выводить сырой
dump, пароли или Authorization в отчёт.

`--env-file /dev/null` исключает неявное чтение `.env`; переменные выше задаются
явно в окружении процесса. Автоматический runner — `scripts/b4/run_rehearsal.py`.
Он создаёт собственные уникальные ресурсы и синтетические данные только для проверки:

```bash
python3 scripts/b4/run_rehearsal.py \
  --jar /absolute/checkout/backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar \
  --output-dir /absolute/checkout/backend/target/b4-checks
```

Нужны Python 3.11+ с зависимостями contract tooling (PyYAML/jsonschema), JDK 21 и Docker
Compose. Runner пишет `report.json` и очищенные от секретов диагностические logs,
удаляет только собственные временные контейнеры/volumes. Имена и IDs построенных
app/proxy images сохраняются для отдельной проверки зависимостей.

Локальный CA создаётся в Caddy volume. Скопировать **только публичный** root certificate
в выбранный файл и передать его клиенту явно:

```bash
"${compose[@]}" cp proxy:/data/caddy/pki/authorities/local/root.crt /absolute/private/local-root.crt
curl --cacert /absolute/private/local-root.crt "https://localhost:${HACKATLAS_HTTPS_PORT}/actuator/health/readiness"
curl --cacert /absolute/private/local-root.crt "https://localhost:${HACKATLAS_HTTPS_PORT}/api/v1/hackathons"
```

`-k`, отключение TLS validation и установка CA в системное trust store не требуются.
В Caddy включён `skip_install_trust`. Его data volume содержит CA private key и
нуждается в той же защите, что и остальные secrets. Не копировать ключ в репозиторий.
HTTP перенаправляется на HTTPS с явным статусом 308, сохраняющим метод запроса.
Работа с сертификатом описана в [Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https).

## Backup и восстановление

Операторские helpers используют только PostgreSQL текущего явного project:
проверяют Compose labels `project`, `service=postgres`, `hackatlas.release=true`.
Они не читают `.env` и не обращаются к другим контейнерам. Password читается из
secret внутри `docker exec`, не попадает в argv, Docker config или stdout. stdout
содержит один JSON с путями, checksum, длительностью и результатом; raw SQL errors
не печатаются, поскольку могут содержать данные.

Каталог backup создать заранее с mode 0700 в native Linux filesystem. Файлы dump
и checksum создаются mode 0600 с `O_EXCL`; существующие файлы не перезаписываются.

```bash
python3 infra/release/ops/backup.py --project "$release_project" \
  --database hackatlas --output /absolute/private/backups/b4-unique.dump
python3 infra/release/ops/restore.py --project "$release_project" \
  --input /absolute/private/backups/b4-unique.dump --database hackatlas_restore_unique
```

Backup — PostgreSQL custom archive (`pg_dump --format=custom --no-owner --no-acl`)
и обязательный `.sha256`. Это согласованный snapshot одной БД; globals/роли и secret
files в dump не входят. Для другого окружения сначала воспроизводят bootstrap
ролей с его собственными секретами. Checksum выявляет повреждение, но не заменяет
доверие к источнику: восстанавливать только собственные доверенные архивы.

Restore принимает только **новое** имя `hackatlas_restore_<unique>`, проверяет
отсутствие БД, checksum и читаемость archive до её создания. Он не использует DROP,
`--clean` или overwrite. Snapshot archive занимает дополнительное место во временном
каталоге на время операции. Restore выполняет migrator, а не superuser;
`pg_restore --exit-on-error --no-owner --no-acl` останавливается на ошибке.

Runtime CONNECT закрыт до завершающей транзакции: она удаляет все восстановленные
`auth_session`, применяет allowlist grants и разрешает runtime CONNECT. Старые Bearer
tokens после восстановления недействительны, пользователь входит заново.
Исходная БД остаётся нетронутой. При ошибке новая неполная БД сохраняется с закрытым
runtime-доступом для диагностики; автоматического удаления нет. JSON возвращает
`readyForSmoke: true` и `requiresPrivacyReview: true`: это готовность отдельной БД
к проверке, а не разрешение включить её для пользователей.

Архив может содержать аккаунты и другие персональные данные, удалённые **после**
snapshot. Отзыв сессий сам по себе не предотвращает возвращение этих данных.
Перед переключением приложения оператор обязан сверить и повторно применить такие
удаления по независимому журналу/заявкам, затем проверить результат. В этом этапе
внешний журнал удалений и автоматическое согласование backup с ним не реализованы.
Если достоверно восстановить список последующих удалений нельзя, такую БД нельзя
открывать пользователям до отдельного решения по privacy. Локальная репетиция с
синтетическими данными доказывает целостность восстановления и отзыв tokens, но не
защиту от возвращения ранее удалённых аккаунтов.

Переключать приложение только после успешного restore, повторного Flyway, сравнения
данных и проверки последующих удалений. Для локальной репетиции с синтетическими
данными при остановленной записи:

```bash
export HACKATLAS_DB_NAME=hackatlas_restore_unique
"${compose[@]}" --profile ops run --rm migrate
"${compose[@]}" up --detach --wait --no-deps --force-recreate app
```

Далее проверить readiness, каталог, вход и отказ старым tokens. Сравнивать таблицы
и Flyway history с backup; ожидаемое исключение — очищенные `auth_session`.
Решение об откате к старой БД или старому JAR требует оценки совместимости схемы
и появившихся после backup записей. Эти scripts не выполняют обратные миграции.

Backup содержит персональные данные, password hashes и хеши сессий. Локальные
permissions не заменяют шифрование, offsite-копию и retention. Они, расписание backup,
место хранения и наблюдение за ошибками **ещё не развёрнуты**. RPO определяется
реальной частотой успешных backup, RTO — проверенным временем полного восстановления
на выбранной инфраструктуре. Локальная длительность runner не является обещанием RPO/RTO.

Семантика официальных инструментов:
[pg_dump 17](https://www.postgresql.org/docs/17/app-pgdump.html),
[pg_restore 17](https://www.postgresql.org/docs/17/app-pgrestore.html).

## Логи и остановка

Caddy access log выключен. Его runtime logger удаляет поле `request`, чтобы error
logs не сохраняли URI/query/headers; backend не включает HTTP request/body logging.
PostgreSQL не пишет statements и параметры ошибочных запросов. Docker logs ограничены
по объёму, но срок хранения и внешняя доставка ещё не настроены.

Для обычной остановки используется `"${compose[@]}" stop`. Не удалять volumes или
backup реального окружения командами очистки лаборатории. Автоматический QA runner
может очищать только ресурсы с собственным уникальным project и label; это не команда
обслуживания существующей базы.
