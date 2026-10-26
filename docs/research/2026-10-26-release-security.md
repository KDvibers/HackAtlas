# B4: проверка зависимостей и эксплуатационных ограничений

Дата: 26 октября 2026. Scope пользователя — подготовка и локальные проверки; внешнего deployment нет. Проверка версий не является доказательством достижимости каждой уязвимости через HackAtlas или полным security audit.

## Продолжение B4.1 — окончательный результат 26 октября

В `fix/backend-release-security` закреплены signed Alpine packages `brotli-libs=1.2.1-r0`, `curl=8.23.0-r0` и `libcurl=8.23.0-r0` поверх прежнего официального Caddy image. Источники: [Brotli package](https://pkgs.alpinelinux.org/package/v3.23/main/x86_64/brotli-libs), [curl package](https://pkgs.alpinelinux.org/package/v3.23/main/x86_64/curl), [Alpine security DB](https://secdb.alpinelinux.org/v3.23/main.json). Исправлены Brotli CVE-2026-111497 и curl advisories, включая CVE-2026-92392. Caddy binary/Go, PostgreSQL и Temurin base pins прежние: исправленных совместимых official images при повторной проверке не появилось.

Новая Trivy DB: **26.10.2026 14:38 UTC**; Java index: **26.10.2026 00:59 UTC**. Просканированы exact images из успешного окончательного HTTPS/restore rehearsal. Scanner **0.76.1** закреплён прежним digest. В сканер не передавался Docker socket; image archives read-only, scan с `network none`, без suppressions, фильтра severity и `ignore-unfixed`.

| Окончательный image | Scope | Package/advisory matches |
|---|---|---|
| App | 136 Ubuntu packages + 94 JAR packages | **32: 28 MEDIUM, 4 LOW**; JAR findings 0 |
| Proxy | 32 Alpine packages + 149 Go packages | **21: 5 HIGH, 14 MEDIUM, 1 LOW, 1 UNKNOWN**; Alpine findings **0** |
| PostgreSQL | 144 Debian packages + 4 Go packages в gosu | **474: 4 CRITICAL, 96 HIGH, 195 MEDIUM, 173 LOW, 6 UNKNOWN** |

Свежая DB сначала обнаружила дополнительные curl advisories, отсутствовавшие в исходном B4 snapshot ниже. После signed package updates эти совпадения отсутствуют. Сравнивать общие числа разных версий DB как прямое улучшение/ухудшение нельзя; package versions и остаточные findings сохранены полностью. Совпадения PostgreSQL по-прежнему требуют описанного ниже контекстного triage; ни одно не скрыто новым gate.

Trivy перечисляет build wrapper `caddy` без версии, **но canonical `github.com/caddyserver/caddy/v2` присутствует с `v2.11.7`**. Это подтверждённое покрытие опубликованного модуля, а не неизвестная версия самого Caddy. Политика сопоставляет только точные имя/PURL wrapper и версионированный canonical module; запись и объяснение остаются в результате. Другие непроверенные версии блокируют gate. Это правило не исключает vulnerability findings.

`run_container_audit.py` завершил все три сканирования; `container_policy.py` повторно проверил сохранённые immutable reports с уточнённым сопоставлением wrapper. **Exit 1, `evidenceComplete=true`, `containerGatePassed=false`**: остаются proxy HIGH/UNKNOWN, PostgreSQL findings и старый Temurin 21.0.12.1+1 вместо минимального 21.0.13+9. Неполные evidence дают exit 2. Даже нулевой container gate не заменяет privacy recovery/M4/разрешение deployment.

Evidence: `backend/target/b41-security-release/{audit-manifest.json,app-trivy.json,proxy-trivy.json,postgres-trivy.json,release-readiness.json}`. Manifest связывает реальные container/image IDs репетиции, SHA-256 архивов, config digest и SHA-256 raw reports. Docker index ID может отличаться от config digest; проверяются оба уровня. Код/команды/лимиты — [security tools](../../scripts/security/README.md), [ручной release workflow](../../.github/workflows/release-readiness.yml). Этот workflow на GitHub ещё не запускался, локальный результат не является CI-run.

Ниже сохранён исходный аудит B4 до этих изменений и более свежей DB; это историческая база, не текущий итог proxy.

## Maven runtime graph

`mvnw dependency:list -DincludeScope=runtime` разрешил 106 JAR dependencies. [OSV querybatch](https://google.github.io/osv.dev/post-v1-querybatch/) для исходного Boot 4.1.1 вернул 10 advisory в трёх packages:

| Package | Прежняя версия | Advisory IDs |
|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 |
| `tools.jackson.core:jackson-core` | 3.1.5 | GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 |
| `tools.jackson.core:jackson-databind` | 3.1.5 | GHSA-cxp5-3px4-pw24, GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54 |

Первичные источники: [Apache Tomcat security](https://tomcat.apache.org/security-11.html), [Jackson core advisory](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf), [Jackson databind advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24). Исходные диапазоны исправлений дополнительно сверены через записи OSV. Advisory не подавлялись на основании предположения, что отдельный parser/authentication mode не используется приложением.

Выбран совместимый stable patch **Spring Boot 4.1.2**. Его [официальный BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom) управляет Tomcat 11.0.27, Jackson 3.1.7, Spring Security 7.1.2, Hibernate 7.4.13.Final и PostgreSQL JDBC 42.7.14. RC 4.2.0 не выбран. Новые отдельные overrides Tomcat/Jackson не вводились.

Повторный запрос по фактически разрешённым 106 runtime JAR versions: **0 совпадений OSV на момент проверки**. Maven verify: 279 тестов без failures/errors/skips; контракт и все 586 экспортированных HTTP-ответов проходят. [Скрипт и ограничения](../../scripts/security/README.md) описывают scope, pagination и отказ при неполном результате/недоступном API. OSV не проверяет в этом режиме ОС, JRE и base images; их результаты фиксируются отдельно в [статусе backend](../STATUS_BACKEND.md).

## Контейнеры: security gate внешнего выпуска не пройден

Использован **Trivy 0.76.1**, официальный image `aquasec/trivy@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c` (amd64 manifest `2e6c00d711ab4ae41d8a30c2c1fd3bd3b62704c177bb120f95012e939edfb3df`). Основная vulnerability DB обновлена 25.10.2026 19:10 UTC, Java index — 25.10.2026 00:59 UTC. После загрузки DB сканирование выполнялось offline в контейнере с `network=none` по read-only архивам `docker save`, без Docker socket. Не использовались severity filtering, `ignore-unfixed` или собственные suppressions. Exit 0 сканера означает завершение анализа, а не отсутствие findings.

| Образ | Найденные пакеты | Package/advisory matches по severity |
|---|---|---|
| App, Temurin 21.0.12.1 JRE / Ubuntu 24.04 + окончательный JAR | 136 OS + 94 распознанных Maven packages | **32: 28 MEDIUM, 4 LOW**; в распознанных JAR — 0 |
| Proxy, официальный Caddy 2.11.7 / Alpine 3.23.7 | 32 OS + 149 Go packages | **22: 6 HIGH, 14 MEDIUM, 1 LOW, 1 UNKNOWN** |
| PostgreSQL 17.11 Bookworm, обновлённый digest `858bb5e98eed02eb8cafbcdc007f9a65d1b613972028944a8ec2133ccaf80d27` | 144 OS + 4 Go packages в gosu | **474: 4 CRITICAL, 96 HIGH, 195 MEDIUM, 173 LOW, 6 UNKNOWN** |

Это количество пар package/advisory, **не число уникальных CVE** и не доказательство сетевой достижимости. Для PostgreSQL 413 совпадений относятся к Debian packages, 61 — к Go binary gosu. Официальная пересборка image от 21 октября обновила закреплённый digest, но повторный scan показал те же findings. Новая фиксация едина для dev/release/test; это не объявляется устранением CVE. Источники: [PostgreSQL tag metadata](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm), [поддерживаемые версии](https://www.postgresql.org/support/versioning/).

HIGH findings proxy:

| Пакет | Установлено → исправлено по advisory | Источник |
|---|---|---|
| brotli-libs | 1.2.0-r0 → 1.2.1-r0 | [CVE-2026-111497](https://nvd.nist.gov/vuln/detail/CVE-2026-111497) |
| golang.org/x/net | v0.59.0 → v0.60.0 | [HTTP/2 SETTINGS DoS, GO-2026-6611](https://pkg.go.dev/vuln/GO-2026-6611) |
| Go stdlib | v1.26.8 → 1.26.10 / 1.27.3 | [x509 trust handling, GO-2026-6809](https://pkg.go.dev/vuln/GO-2026-6809) |
| Go stdlib | v1.26.8 → 1.26.9 / 1.27.2 | [Range header DoS, GO-2026-6609](https://pkg.go.dev/vuln/GO-2026-6609) |
| Go stdlib | v1.26.8 → 1.26.9 / 1.27.2 | [HTTP/2 SETTINGS DoS, GO-2026-6611](https://pkg.go.dev/vuln/GO-2026-6611) |
| Go stdlib | v1.26.8 → 1.26.9 / 1.27.2 | [TLS ECH DoS, GO-2026-6607](https://pkg.go.dev/vuln/GO-2026-6607) |

У x509 advisory есть конкретные условия изменения/недоверия промежуточным сертификатам; из номера версии нельзя заключить, что любой TLS-запрос уязвим. При этом используемый сетевой proxy требует исправленного/проверенного бинарника перед внешним выпуском. На дату проверки [последний официальный stable Caddy](https://api.github.com/repos/caddyserver/caddy/releases/latest) — v2.11.7; [Docker tag](https://hub.docker.com/v2/repositories/library/caddy/tags/2.11.7-alpine) указывает на закреплённый digest. Простое обновление Alpine packages не заменит встроенные Go/x/net. Собственная Go/xcaddy supply chain в B4 не добавлялась.

Разбор четырёх CRITICAL findings PostgreSQL без удаления их из raw report:

- **CVE-2023-45853, zlib1g:** [Debian tracker](https://security-tracker.debian.org/tracker/CVE-2023-45853) указывает, что уязвимый contrib/minizip не собирается в рассматриваемые Bookworm binary packages. Это документированное несоответствие package-level match, а не основание игнорировать остальные находки.
- **CVE-2025-7458, libsqlite3-0:** [Debian tracker](https://security-tracker.debian.org/tracker/CVE-2025-7458) отмечает affected/minor/no-dsa; в scan нет исправленной Bookworm-версии. Требуется выполнение SQLite SQL. HackAtlas использует PostgreSQL, экспонированный SQLite путь не установлен; формального доказательства недостижимости нет.
- **CVE-2026-6653, libxml2:** [Debian tracker](https://security-tracker.debian.org/tracker/CVE-2026-6653) отмечает postponed/minor. API не принимает XML, но PostgreSQL XML-функции могут использовать libxml2; остаётся проверка достижимости/исправления.
- **CVE-2025-68121, Go stdlib в gosu:** [GO-2026-4337](https://pkg.go.dev/vuln/GO-2026-4337) касается TLS session resumption после изменения конфигурации сертификатов. [gosu](https://github.com/tianon/gosu/blob/1.19/main.go) — локальный switch-user/exec helper, не TLS-сервер PostgreSQL; сетевой путь не установлен. Ожидается upstream rebuild или формализованный review, suppression не добавлен.

**Отдельное ограничение JRE:** Trivy не идентифицировал установленный из tar Temurin runtime как пакет. Нулевые Java-library findings не проверяют сам JRE. Реальный `java -version` в app image — 21.0.12.1+1; [Temurin 21.0.13+9](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9) опубликован 22 октября. Но официальный Docker tag `21.0.13_9-jre-noble` возвращает 404, поиск этой версии в [Docker Hub metadata](https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?page_size=100&name=21.0.13) пуст, [официальный Dockerfile](https://raw.githubusercontent.com/adoptium/containers/main/21/jre/ubuntu/noble/Dockerfile) ещё закрепляет 21.0.12.1+1. Перед внешним выпуском нужен исправленный официальный image или отдельно проверенный вариант runtime и повторный аудит. Host SDK не обновлялся.

Raw JSON, scanner metadata и image inspect сохранены локально в игнорируемом `backend/target/b4-security/`: `app-final-trivy.json`, `proxy-final-trivy.json`, `postgres-final-trivy.json`, `container-audit-summary.json`. Это выполненная локальная проверка, не уже запущенный container-scanning CI job. Trivy не включён как обходящий findings «зелёный» CI step; Maven OSV и функциональный B4 rehearsal в CI не заменяют данный внешний release gate.

Фактически использованные команды внутри закреплённого Trivy image:

```bash
trivy image --cache-dir /cache --timeout 3m --no-progress --download-db-only
TMPDIR=/cache/tmp trivy image --cache-dir /cache --timeout 5m --no-progress --download-java-db-only
trivy image --cache-dir /cache --timeout 3m --skip-db-update --skip-java-db-update --offline-scan --no-progress --scanners vuln --format json --input /input/image.tar
```

`/cache` — собственный Linux volume, `/cache/tmp` создан до загрузки Java index; обычного tmpfs 1 GiB для распаковки недостаточно. До последней команды каждый локальный image экспортирован через `docker image save --output <archive> <exact-image-ref>` и смонтирован read-only как `/input/image.tar`. Только загрузка scanner/DB использовала сеть; scan-контейнер — `--network none --read-only --cap-drop ALL --security-opt no-new-privileges`. Полные выполненные флаги и локальные image refs сохранены в `backend/target/b4-security/reproduce-trivy-audit.sh`; синтаксис проверен `bash -n`. После окончательного scan собственные Linux volume и scanner image удалены; частичный Windows-кэш оставлен после отказа автоматической проверки разрешений.

## Защита локального release-кандидата

- Runtime не выполняет DDL/Flyway, не читает migration history и не меняет `admin_access`. `admin_audit` доступен только для INSERT. Мигратор и оператор backup используют отдельные credentials.
- File secrets монтируются только нужным контейнерам, не передаются через Docker environment и не попадают в image. Пароли для прогонов синтетические.
- Caddy и backend не доверяют клиентским Forwarded-заголовкам. Relative redirect сохраняет внешний HTTPS origin; admin cookie остаётся Secure. В этом простом варианте IP-лимит общий за proxy, account-лимит сохраняется.
- Runtime контейнеры работают без root, с read-only filesystem, без capabilities, с ограничениями ресурсов. Локальный override заменяет публичные bindings на loopback. Внешний ACME не используется: тестовый CA доверяется явно только клиентом проверки.
- Restore не перезаписывает исходную БД и отзывает восстановленные сессии. Позднейшие удаления аккаунтов требуют отдельной сверки до открытия сервиса — [PRIVACY](../PRIVACY.md).

Перед внешним staging нужны конкретные инфраструктура/домен, повторная проверка актуальных advisory и images, измерение восстановления на целевых данных, политика backup/retention и отдельное разрешение пользователя. Публичная регистрация остаётся закрытой; email verification/password reset требуют согласованного расширения контракта, а мобильная приёмка M4 — реальных устройств.
