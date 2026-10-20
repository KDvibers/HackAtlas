# Локальная инфраструктура B0

Только dev PostgreSQL для HackAtlas. Backend запускается отдельно; production deployment здесь не настроен.

## Конфигурация

Один локальный `infra/.env` используется Docker Compose и профилем `dev` backend. Он исключён из Git; `.env.example` содержит только пример URL, имени пользователя и пустое место для пароля.

| Параметр | Backend | Контейнер PostgreSQL |
|---|---|---|
| `HACKATLAS_DB_URL` | JDBC URL | Не используется; БД фиксирована как `hackatlas` |
| `HACKATLAS_DB_USERNAME` | Username | Передаётся как `POSTGRES_USER` |
| `HACKATLAS_DB_PASSWORD` | Password | Передаётся как `POSTGRES_PASSWORD` |

`POSTGRES_DB=hackatlas` задан в Compose. Пустые username/password не допускаются; готового пароля в репозитории нет. Для однозначного чтения Compose и Spring используйте простой `KEY=value` без кавычек, подстановок и inline-комментариев. Для пароля подходит локально сгенерированное случайное hex-значение.

При запуске из `backend/` профиль `dev` импортирует `../infra/.env` как properties. Переменные окружения могут переопределять значения и в Spring, и в Compose; если используете переопределения, они должны совпадать у обоих процессов. Сам параметр Compose `--env-file` не экспортирует переменные в оболочку. Профиль `prod` получает настройки из окружения и не загружает dev-файл.

## Запуск

Нужны Docker Engine с Linux containers и Docker Compose v2. Все команды ниже выполняются из корня репозитория. Если Docker работает в WSL, команды Docker выполняются в этой WSL-среде.

На текущей машине Docker socket доступен WSL root. Открыть такую оболочку из PowerShell и перейти в проект:

```powershell
wsl -d Ubuntu -u root
```

В открывшейся Linux-оболочке:

```bash
cd /mnt/c/Daniyar/MyProjects/HackAtlas
```

Далее выполнять bash/Compose-команды ниже. На другой машине нужен её путь к клону и пользователь с доступом к Docker. Права socket и группы пользователей эти инструкции не меняют.

Первичная подготовка в PowerShell:

```powershell
if (-not (Test-Path -LiteralPath .\infra\.env)) {
    Copy-Item -LiteralPath .\infra\.env.example -Destination .\infra\.env
}
```

Либо в bash:

```bash
if [ ! -f infra/.env ]; then
  cp infra/.env.example infra/.env
fi
```

Затем заполнить `HACKATLAS_DB_PASSWORD` в локальном файле. Не выводить содержимое `.env` в логи и не коммитить его.

Одинаковые команды для PowerShell и bash:

```text
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml up --detach --wait --wait-timeout 120 postgres
docker compose --env-file infra/.env -f infra/compose.yaml ps
```

`config --quiet` проверяет конфигурацию, не печатая подставленный пароль. PostgreSQL доступен только через `127.0.0.1:5432`; порт не публикуется на всех сетевых интерфейсах. Если порт занят, сначала определить владельца порта, а не останавливать чужие контейнеры.

Healthcheck проверяет готовность PostgreSQL принимать подключения; миграции и фактическую работу приложения проверяют backend tests. Изменение `POSTGRES_USER`/`POSTGRES_PASSWORD` влияет на инициализацию пустого volume и не меняет автоматически роль/пароль в существующей БД. Настройки локального контейнера не являются production-моделью разделения ролей.

Для обычной остановки с сохранением БД:

```text
docker compose --env-file infra/.env -f infra/compose.yaml stop postgres
```

Данные хранятся в named volume `hackatlas-dev_postgres-data`, смонтированном в `/var/lib/postgresql/data` для PostgreSQL 17. Повторный запуск использует те же данные.

## CI

[backend-ci.yml](../.github/workflows/backend-ci.yml) запускает три проверки на каждом push и pull request без фильтрации путей:

- `Contract check`: Python 3.12, существующие закреплённые dependencies и `scripts/validate_contract.py`.
- `Backend verify`: Temurin Java 21, Maven Wrapper `verify`, настоящий Docker для Testcontainers. Локальный dev volume и `.env` для тестов не используются.
- `Gitleaks`: CLI 8.30.1, проверка SHA-256 до распаковки и scan всей загруженной Git-истории с `--redact=100`. Это scan истории на CI, не полный аудит секретов.

Все Actions закреплены полными commit SHA. Workflow имеет только `contents: read`, не сохраняет checkout credentials и не получает production-секреты. Нет публикации, push, создания PR или отключения tests. Настройка required checks в GitHub выполняется отдельно после подтверждённого запуска workflow.

## Проверенные источники фиксации

Версии и SHA проверены 20 октября 2026 по первичным источникам:

| Компонент | Фиксация | Источник |
|---|---|---|
| PostgreSQL | `17.11-bookworm`, multiarch digest `sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826` | [Поддерживаемая линия](https://www.postgresql.org/support/versioning/), [Docker Official Image](https://hub.docker.com/_/postgres), [metadata тега](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm) |
| checkout | `v7.0.1` → `3d3c42e5aac5ba805825da76410c181273ba90b1` | [Официальный release](https://github.com/actions/checkout/releases/tag/v7.0.1) |
| setup-java | `v6.1.0` → `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` | [Официальный release](https://github.com/actions/setup-java/releases/tag/v6.1.0) |
| setup-python | `v7.0.0` → `5fda3b95a4ea91299a34e894583c3862153e4b97` | [Официальный release](https://github.com/actions/setup-python/releases/tag/v7.0.0) |
| Gitleaks CLI | `8.30.1`, Linux x64 SHA-256 `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb` | [Release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1), [checksums](https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_checksums.txt) |

Action SHA сверены через `git ls-remote --tags` официальных репозиториев. Архив Gitleaks Linux x64 скачан для проверки в память, его SHA-256 совпал с upstream checksum. При обновлении версии заново проверить связанные SHA/digest и выполнить соответствующие проверки; не заменять их плавающими tags.
