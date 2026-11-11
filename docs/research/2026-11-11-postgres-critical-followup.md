# Три критические находки в PostgreSQL после PR #52

11 ноября 2026. [External release readiness на main `ba262f0`](https://github.com/KDvibers/HackAtlas/actions/runs/43064745401)
включает [PR #52](https://github.com/KDvibers/HackAtlas/pull/52). Maven
`verify`, тесты политики аудита и все 12 проверок релизной репетиции прошли,
включая HTTPS и восстановление в карантин. OSV проверил 111 зависимостей
без находок. В backend-образе оба пакета APT имеют версию `2.8.4ubuntu0.1`.
Две записи `CVE-2026-109518` исчезли из отчёта этого образа.

SHA-256 скачанного ZIP совпал с метаданными GitHub. Повторная проверка
сырых отчётов через `scripts/security/container_policy.py` дала те же
результаты. Аудит принял доказательство удаления `gosu`, привязанное к
PostgreSQL `sha256:d73562ac3a20d634c3af88435ad9a831c261fd36b4bd291fe3f7cd30c944ffa3`.
ID совпал в отчёте репетиции, манифесте Trivy и доказательстве.
Контейнерный аудит полон (`evidenceComplete=true`), выпуск заблокирован.

| Компонент | CRITICAL | HIGH | UNKNOWN |
| --- | ---: | ---: | ---: |
| Backend | 0 | 0 | 0 |
| PostgreSQL без gosu | 3 | 108 | 11 |
| Caddy | 0 | 2 | 2 |
| restic 0.19.1 | 0 | 23 | 2 |

У restic также отсутствует версия собственного Go-модуля
`github.com/restic/restic` в инвентаре Trivy.

## Три записи PostgreSQL

| Находка Trivy | Что установлено |
| --- | --- |
| `CVE-2025-7458`, `libsqlite3-0` | В свежем Bookworm та же уязвимая версия `3.40.1-2+deb12u2`. В проверенном Trixie стоит исправленная `3.46.1-7+deb13u2`. В Alpine пакет SQLite отсутствует в инвентаре образа. [Debian](https://security-tracker.debian.org/tracker/CVE-2025-7458) |
| `CVE-2026-6653`, `libxml2` | Уязвимы пакеты Bookworm и Trixie. Пакет Trixie с префиксом версии `2.12.7` основан на исходниках `2.9.14`. В Alpine установлен `2.13.9-r2`, выше версии upstream с исправлением, указанной Debian. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653), [Alpine](https://pkgs.alpinelinux.org/packages?arch=x86_64&branch=v3.24&name=libxml2) |
| `CVE-2023-45853`, `zlib1g` | Запись относится к MiniZip. Debian указывает, что исходный пакет `zlib` в Bookworm не собирает MiniZip. [Проверка содержимого `zlib1g`](2026-11-10-postgres-critical-review.md) подтвердила отсутствие этих файлов. Trivy продолжает учитывать запись для Bookworm. В Trixie и Alpine она отсутствует. [Debian](https://security-tracker.debian.org/tracker/CVE-2023-45853) |

## Локальное сравнение системных пакетов

Docker проверил `linux/amd64`, Trivy 0.76.1 использовал одну базу от
`2026-11-11T13:00:47Z`. Кандидаты зафиксированы по digest. Таблица
покрывает системные пакеты. Полный релизный аудит кандидатов ещё впереди.

| Образ | Digest манифеста или ID локальной сборки | CRITICAL | HIGH | UNKNOWN |
| --- | --- | ---: | ---: | ---: |
| Релизный Bookworm без gosu, локальная сборка | `sha256:d5acf397834eb815c19d17b0ca3c5c1720eeaf53372bb22b9da3371fa56366ba` | 3 | 108 | 11 |
| Официальный 17.11 Bookworm от 5 ноября | `sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994` | 3 | 106 | 10 |
| Официальный 17.11 Trixie | `sha256:ff87138949ad9a46f32ff23bd6852b52ce024a13dffe9b4087be98292cdd3b4f` | 1 | 122 | 15 |
| Официальный 17.11 Alpine 3.24 | `sha256:e5d2edd28b1f8d40a0a6c0c9cdc545b19b56a0d5d88f94bc11a97d970d4700d0` | 0 | 18 | 1 |

Метаданные тегов сверены в Docker Hub.
[Bookworm](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm),
[Trixie](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-trixie),
[Alpine](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-alpine3.24).

## Совместимость Alpine и следующий шаг

В изолированных контейнерах `pg_dump -Fc` из Bookworm восстановлен
через `pg_restore` в новый кластер Alpine. Совпали шесть синтетических
строк, включая Unicode и JSONB, и подтвердились два валидных индекса.
Оба кластера сохранили UTF-8 и обработали проверочный XML. При одинаковом
имени локали базы `en_US.utf8` изменился порядок сортировки текста.
Именованная collation `en_US.utf8` доступна в каталоге Bookworm и отсутствует
в каталоге Alpine. Эксперимент запускал Alpine от штатного UID/GID
`70:70`, а релизный Compose и инструменты восстановления используют
`999:999`.

Следующим кандидатом предлагается Alpine. До выбора базы нужно проверить
миграции, поведение поиска и сортировки, привязку UID к томам и утилитам,
полный HTTPS-прогон, восстановление в карантин и откат на Bookworm.
Для точного нового образа понадобится совместимое доказательство
покрытия Trivy. Остаются 18 `HIGH` и 1 `UNKNOWN` в системных пакетах
кандидата.

Локальные отчёты сохранены в `backend/target/b4-pg-critical-followup/`.
ZIP GitHub сохранён в `backend/target/github-release-readiness-43064745401.zip`.
