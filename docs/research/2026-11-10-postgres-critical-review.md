# Четыре критические находки в образе PostgreSQL 17.11

10 ноября 2026. База проверки `main` `19ad561`. PR #43 с обновлением Ubuntu
APT проходит отдельно. Проверка PostgreSQL использует закреплённые официальные
образы Linux amd64 и одну свежую базу Trivy. Данные для восстановления были
синтетическими.

## Что нашли в текущем Bookworm

| Находка Trivy | Что проверили | Что остаётся |
| --- | --- | --- |
| `CVE-2025-7458`, `libsqlite3-0` | В Bookworm установлен SQLite `3.40.1-2+deb12u2`. Debian отмечает Bookworm как уязвимый, а SQLite в Trixie как исправленный. В отчёте Trixie запись исчезла. [Debian](https://security-tracker.debian.org/tracker/CVE-2025-7458) | Официальная пересборка Bookworm содержит ту же версию. |
| `CVE-2026-6653`, `libxml2` | Debian отмечает Bookworm и Trixie как уязвимые. Версия `2.12.7+dfsg+really2.9.14-2.1+deb13u3` в Trixie основана на 2.9.14. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653) | Оба образа сохраняют `CRITICAL`, официальный пакет с исправлением для этих двух веток не найден. |
| `CVE-2023-45853`, `zlib1g` | Advisory относится к MiniZip. Debian указывает, что Bookworm не собирает `contrib/minizip` из этого исходного пакета. В установленном `zlib1g` обнаружены `libz` и документация, без файлов MiniZip. В отчёте Trixie запись исчезла. [Debian](https://security-tracker.debian.org/tracker/CVE-2023-45853) | Trivy продолжает учитывать совпадение по `zlib1g` в Bookworm. Политика выпуска его сохраняет. |
| `CVE-2025-68121`, Go stdlib | Бинарник `gosu 1.19` во всех трёх образах собран на Go `1.24.6`. Для линии 1.24 исправление появилось в `1.24.13`. Upstream `gosu 1.20` тоже собран на `1.24.6`. [Go](https://pkg.go.dev/vuln/GO-2026-4337), [gosu](https://github.com/tianon/gosu/releases/tag/1.20) | Замена номера версии `gosu` сама по себе не устраняет совпадение. |

Trivy сопоставляет установленные версии пакетов и advisories. Эти данные не
устанавливают доступность уязвимого пути из внешнего трафика HackAtlas.

## Сравнение образов

| Официальный образ | CRITICAL | HIGH | UNKNOWN | Версия collation в новом кластере |
| --- | ---: | ---: | ---: | --- |
| Текущий `17.11-bookworm` (`839fbb5…`) | 4 | 117 | 26 | `2.36` |
| Свежий `17.11-bookworm` (`665dc7b…`) | 4 | 115 | 25 | Та же glibc `2.36` по инвентарю |
| Кандидат `17.11-trixie` (`ff87138…`) | 2 | 131 | 25 | `2.41` |

Теги и digests сверены с [официальным Bookworm](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm)
и [официальным Trixie](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-trixie).
Оба кандидата сохранили PostgreSQL `17.11` и пользователя `999`. Пересборка
Bookworm уменьшила число `HIGH` и `UNKNOWN`, но не устранила ни одну из четырёх
рассматриваемых записей. Trixie снял две `CRITICAL` ценой 14 дополнительных
`HIGH` относительно текущего образа. Все значения относятся к одной базе
Trivy и парам «пакет — advisory».

## Совместимость восстановления

Логический backup `pg_dump -Fc` из старого Bookworm восстановлен через
`pg_restore --exit-on-error --single-transaction` в пустой кластер Trixie.
Четыре строки с Unicode и JSONB совпали, три индекса прошли проверку `indisvalid`.
В обоих кластерах `UTF8` и `en_US.utf8`, но фактические версии collation
разные (`2.36` и `2.41`). Это проверка переноса через логический backup. Для
переиспользования существующего тома понадобится отдельно проверить и
переиндексировать затронутые объекты перед обновлением версии collation.
[PostgreSQL ALTER COLLATION](https://www.postgresql.org/docs/17/sql-altercollation.html).

## Сборка и репетиция кандидата

- В отдельной копии `main` три тестовых pin Testcontainers временно заменены на
  digest Trixie. `bash ./mvnw --batch-mode --no-transfer-progress verify` собрал
  backend и прошёл **47 unit + 311 integration**. В исходном checkout тестовые
  pins остались Bookworm.
- Во второй временной копии две ссылки на PostgreSQL в release Compose заменены
  на тот же digest Trixie. `scripts/b4/run_rehearsal.py` собрал образы приложения
  и прокси и завершил **12 проверок за 152,269 с**. Проверены HTTPS с доверием к
  тестовому CA, backup, restore в карантин, учёт удалений и V6–V9. Репетиция
  использовала ранее проверенный JAR `a890e4b…` из того же Java source.
- Штатный `scripts/security/run_container_audit.py` просканировал три точных
  образа репетиции. `evidenceComplete=true`. Для PostgreSQL повторились
  **2 CRITICAL, 131 HIGH, 25 UNKNOWN** и совпали критические записи из таблицы.
  Контейнерный допуск остался закрыт. App образ в этом эксперименте собран из
  `main` на момент проверки, обновление APT из открытого PR #43 проверяется отдельно.

Локальные артефакты находятся в ignored `backend/target/`.
Сравнение образов и перенос — `b4-pg-critical-audit/`, полная репетиция —
`b4-pg-trixie-rehearsal/`, полный Trivy — `b4-pg-trixie-full-audit/`, Maven —
`b4-pg-trixie-maven.log`.

## Решение по текущему этапу

Оставляем закреплённый Bookworm в релизной конфигурации. Из проверенных
официальных образов Trixie частично снижает критические совпадения, но требует
отдельного решения по миграции и разбору новых `HIGH`. Подмена системных
библиотек пакетами из другой ветки Debian и исключения Trivy здесь не применялись.

Следующий предметный шаг — проверить вариант для `gosu` на текущем Bookworm.
В релизном Compose PostgreSQL запускается от `999:999`, а root-процесс подготовки
томов использует собственный `/bin/sh`. Можно отдельно испытать образ без
неиспользуемого `gosu` или с воспроизводимой сборкой на исправленном Go.
Для `libxml2` остаётся искать поддерживаемое обновление или отдельный обоснованный
вариант образа с проверкой XML-зависимостей PostgreSQL.
