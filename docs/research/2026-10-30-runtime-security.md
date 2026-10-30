# B4.3: обновление официальных runtime images

Дата: 30 октября 2026. Граница — локальная подготовка и проверки, без deployment.
База ветки `fix/backend-runtime-security`: main `a4394f50aba09936df5e49234f276976d98e5ff4`.
[B4.2 PR #13](https://github.com/KDvibers/HackAtlas/pull/13) и [mobile PR #12](https://github.com/KDvibers/HackAtlas/pull/12)
объединены; [Backend CI main](https://github.com/KDvibers/HackAtlas/actions/runs/40370128829) прошёл.

## Проверка источников и выбор

Использованы официальные Docker Hub metadata и registry manifests с проверкой SHA-256,
публикации Docker Official Images и Dockerfile Adoptium. Теги не считаются immutable:
конфигурация закрепляет полный index digest.

| Компонент | Проверенное изменение | Решение |
|---|---|---|
| Temurin 21 JRE / Ubuntu Noble | `21.0.13_9-jre-noble`, публикация 29.10; index `sha256:03fedab2c0d7355868b5ba5330d64088b956a0bfac48f904e5c57207e261e2c9`, amd64 `sha256:db724162e4ff5f71fa0a2815026314d9e5e0e9a139f49be1d4717e6930e93341` | Обновить прежний 21.0.12.1+1; прямой запуск подтвердил `Temurin-21.0.13+9` |
| PostgreSQL 17.11 / Debian Bookworm | Пересборка 28.10; index `sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5`, amd64 `sha256:2cd2c0f809604aaeff6f0ec8f40aecbd025613107561015c69369a1a7f1d3416` | Обновить один pin в dev/release/tests/CLI; `postgres --version` подтвердил 17.11, смены major/схемы нет |
| Caddy 2.11.7 Alpine | Новый index `sha256:e70b164da81ef698b3ad19af799ea6086ce6d9e48ed4079eb9fe26f6fbf14ee5`, но старый и новый index ссылаются на одинаковый amd64 manifest `sha256:04913b1da43339a40ad1ea87827d70a576ac5aa6cf674c6e24fdca675887533a` | Сохранить прежний pin: добавление riscv64 не обновляет бинарник на проверяемой платформе |

Источники: [Temurin metadata](https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/21.0.13_9-jre-noble),
[Adoptium Dockerfile](https://github.com/adoptium/containers/blob/main/21/jre/ubuntu/noble/Dockerfile),
[official Temurin tags](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin),
[PostgreSQL metadata](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm),
[official PostgreSQL tags](https://github.com/docker-library/official-images/blob/master/library/postgres),
[Caddy metadata](https://hub.docker.com/v2/repositories/library/caddy/tags/2.11.7-alpine),
[official Caddy tags](https://github.com/docker-library/official-images/blob/master/library/caddy).
Снимки metadata и проверенных manifest hashes сохранены в ignored `backend/target/b43-upstream.json`
и `b43-manifests.json`; публичные pull/runtime logs — рядом.

Сравнение `dpkg-query` старого и нового PostgreSQL image подтвердило три изменения:
`libssl3`/`openssl` — `3.0.22-1~deb12u1` → `3.0.22-1~deb12u2`,
`tzdata` — `2026c-0+deb12u1` → `2026e-0+deb12u1`.
Это наблюдаемые версии пакетов, не доказательство устранения всех CVE.
Снимок — ignored `backend/target/b43-package-changes.json`.

## Проверки

| Проверка | Результат |
|---|---|
| Maven `verify dependency:list` на host Java 21.0.12.1 и новой PostgreSQL | **279 тестов: 21 unit + 258 integration, 0 failures/errors/skipped, BUILD SUCCESS** |
| Python B4/security unit suites | **42 PASS**, 0 skips |
| Starter validator и свежие HTTP exports | **586 ответов PASS**, fixtures/контракт PASS |
| OSV по фактическому runtime graph | **106 packages, 0 matches** на момент проверки |
| Полная локальная HTTPS/restore репетиция на новом release JRE | **PASS, 184.533 с**, собственные ресурсы очищены |
| Gitleaks по истории и изменённым/новым файлам; scope/links/pins/XML/`git diff --check` | **PASS, no leaks**; API/mobile/design не затронуты |

Логи/данные: ignored `backend/target/b43-verify.log`, `b43-python.log`,
`b43-runtime-dependencies.txt`, `b43-dependency-audit.json`, `b43-rehearsal/`,
`b43-container-audit/`, `b43-final-checks.log`. Точные команды — [STATUS_BACKEND](../STATUS_BACKEND.md).
Дополнительные Bruno/Schemathesis runs не повторялись: HTTP/Java production код и
зависимости не менялись; новая PostgreSQL проверена полной integration suite,
свежими экспортами ответов и реальным HTTPS/restore сценарием.

## Свежий Trivy: внешний выпуск по-прежнему BLOCKED

Pinned Trivy 0.76.1 проверил архивы трёх реально испытанных images, без Docker socket
и с offline scan после скачивания баз. Image/container/config IDs, SHA-256 архивов
и JSON проверены существующей политикой. DB updated **30.10 13:06 UTC**, Java index
**30.10 00:58 UTC**; итог checkedAt **30.10 18:21 UTC**.

| Image | Покрытие | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN |
|---|---|---:|---:|---:|---:|---:|
| App | 136 Ubuntu + 94 JAR packages; в JAR 0 findings | 0 | 0 | 27 | 4 | 0 |
| Proxy | 32 Alpine + 149 Go packages; в Alpine 0 findings | 0 | 7 | 16 | 1 | 3 |
| PostgreSQL | 144 Debian + 4 gosu Go packages | 4 | 94 | 187 | 171 | 6 |

Числа — совпадения package/advisory, не уникальные CVE и не доказательство доступного
пути эксплуатации. Между отчётами 26 и 30 октября обновилась база advisories;
разницу counts нельзя целиком приписывать обновлению image.

**Exit 1, evidenceComplete=true, containerGatePassed=false, publicReleaseReady=false.**
Это завершённый аудит с блокерами, а не сбой скачивания или пропущенный scan.
Отдельный blocker старого JRE снят: фактический runtime в app `21.0.13+9` проходит
существующий минимальный порог, политика не ослаблялась. Нулевые JAR findings
по-прежнему не считаются сканированием самого tar-installed JRE.

Caddy Go inventory остаётся `v1.26.8` / `x/net v0.59.0`. Свежая DB также содержит
HIGH для `go-jose/v3 v3.0.5` и `go-jose/v4 v4.1.5`; для v4 указано исправление
`4.1.6`. Это состав стороннего Caddy binary, а не добавление JWT в HackAtlas.
`apk upgrade` не пересобирает Go binary; самостоятельная сборка Caddy/Go и смена
дистрибутива PostgreSQL не вводятся в эту работу. Версионированный canonical
`github.com/caddyserver/caddy/v2 v2.11.7` сохранён в inventory; wrapper coverage
не исключает его findings. Suppressions, ignore-unfixed и risk acceptances не добавлялись.

Собственный scanner cache и временные image archives удалены штатным cleanup.
Полные JSON/findings сохранены локально; внешняя CI/deployment проверка не запускалась.

## Границы и последующие решения

Это обычное обновление images внутри принятого стека. API, opaque sessions, production
Java-логика, Flyway migrations и mobile не меняются. Новые сервисы, журнал удалений,
автоматический backup, VPS и внешние workflow не запускаются.

Независимая история удалений остаётся отдельным решением до реальных пользователей:
карантин B4.2 требует достоверного приватного списка, а `admin_audit` таким источником
не является. Источник, защита, доставка и срок хранения этого списка ещё не выбраны.
Локальные проверки Linux amd64 не подтверждают другие архитектуры, внешний TLS/M4
или безопасность всех возможных deployment-конфигураций.
