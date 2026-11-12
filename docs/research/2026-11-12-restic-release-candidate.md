# Локальная проверка restic 0.19.1 с исправленными Go-зависимостями

12 ноября 2026. База проверки — коммит `390bfd1` [PR #60](https://github.com/KDvibers/HackAtlas/pull/60).
[External release readiness](https://github.com/KDvibers/HackAtlas/actions/runs/43082811582)
на этом коммите прошёл 12 проверок репетиции и аудит точных контейнерных
образов. У Caddy, PostgreSQL и backend нет `CRITICAL`, `HIGH` и `UNKNOWN`.
Общий запуск остановился на отдельном аудите официального restic.

## Почему проверена локальная сборка

Последний стабильный официальный релиз — `v0.19.1`. Его бинарник
Linux amd64 собран на Go `1.26.4`. В отчёте GitHub 13 из 23 `HIGH`
относятся к стандартной библиотеке Go, остальные — к пяти
Go-зависимостям. Две записи имеют `UNKNOWN`. Trivy распознаёт
`github.com/restic/restic`, при этом версия модуля отсутствует.
Подписанный репозиторий Alpine 3.24 предлагает более старый пакет
`0.18.1-r9`. [Официальный релиз restic](https://github.com/restic/restic/releases/tag/v0.19.1)

Проверен тот же опубликованный модуль `github.com/restic/restic v0.19.1`
с обновлением зависимостей. Команда собирает `cmd/restic` из отдельного
Go-модуля, поэтому исходный restic остаётся версионированным модулем в
`go version -m`. Закреплённый builder использует Go `1.27.4`,
Linux amd64 и проверку сумм через `sum.golang.org`.
[История релизов Go](https://go.dev/doc/devel/release#go1.27.minor)

| Модуль | Версия в кандидате |
| --- | --- |
| `github.com/restic/restic` | `v0.19.1` |
| `github.com/klauspost/compress` | `v1.20.3` |
| `golang.org/x/crypto` | `v0.59.0` |
| `golang.org/x/net` | `v0.62.0` |
| `golang.org/x/text` | `v0.44.0` |
| `google.golang.org/grpc` | `v1.83.2` |
| `github.com/go-jose/go-jose/v4` | `v4.1.9` |

Первый прогон сборки с обновлёнными зависимостями оставил один `HIGH`
в `go-jose/v4 v4.1.4`. После закрепления `v4.1.9` эта запись ушла.
[Advisory go-jose](https://github.com/go-jose/go-jose/security/advisories/GHSA-mgcc-cww5-fp2q)

## Сборка и аудит точного бинарника

Использован builder
`golang:1.27.4-alpine3.24@sha256:0a5f7fe796599ff487605987432e7619ae45beeb2fba4c2ce0b907879932aebf`.
В новом каталоге внутри builder команды были следующими.

```bash
export CGO_ENABLED=0 GOTOOLCHAIN=local GOWORK=off
export GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org
go mod init hackatlas.local/restic-build
go get github.com/restic/restic/cmd/restic@v0.19.1 \
  github.com/klauspost/compress@v1.20.3 golang.org/x/crypto@v0.59.0 \
  golang.org/x/net@v0.62.0 golang.org/x/text@v0.44.0 google.golang.org/grpc@v1.83.2
go get github.com/go-jose/go-jose/v4@v4.1.9
go mod verify
go build -mod=readonly -trimpath -buildvcs=false -ldflags="-w -s" \
  -o restic github.com/restic/restic/cmd/restic
go version -m restic
```

Повторная сборка в другом контейнере из тех же `go.mod` и `go.sum`
дала побайтовое совпадение и SHA-256
`bb5ed103331524cf8a9f2a735456f250a5373be1635e7fcc5ac5c77125253ce6`.
Trivy 0.76.1 проверил официальный бинарник и кандидата на одной базе
от `2026-11-11T13:00:47Z`, в режиме `rootfs` без сети. Для оценки
использована существующая функция `evaluate` из аудита recovery tool.

| Бинарник | Версия модуля | Пакеты | CRITICAL | HIGH | UNKNOWN | LOW |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Официальный | — | 81 | 0 | 23 | 2 | 2 |
| Локальный кандидат | `v0.19.1` | 87 | 0 | 0 | 0 | 1 |

Оставшийся `LOW` — `CVE-2026-81870` в `go.opentelemetry.io/otel/sdk v1.44.0`.
Инвентарь кандидата полон, проверка зависимостей прошла с действующим порогом.

## Совместимость архивов и следующий шаг

Для репозиториев формата 2 проверены обе пары writer/reader.
Официальный бинарник записал архив, который кандидат проверил через
`check --read-data` и восстановил. Архив, записанный кандидатом,
проверен и восстановлен официальным бинарником. В обеих проверках
совпали SHA-256 синтетических файлов. Временная копия операторских
инструментов HackAtlas с pin кандидата прошла все 17 тестов
`test_recovery_archive.py` под UID 1000.

Рабочий установщик и `External release readiness` пока используют
официальный бинарник. Следующий шаг — подключить воспроизводимую
сборку с закреплёнными `go.mod`, `go.sum` и SHA-256 к операторским
командам и CI, затем прогнать полную релизную репетицию и ручной workflow
на этом бинарнике. Эксперимент использовал только синтетические данные.
Локальные артефакты находятся в `backend/target/restic-go-1274-final-*`.
