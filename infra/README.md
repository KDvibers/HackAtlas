# Локальная инфраструктура B0/B1

Этот файл описывает dev PostgreSQL для HackAtlas; backend запускается отдельно. Подготовка B4 отделена в [release/README](release/README.md): Docker Compose, Caddy, file secrets и восстановление в новую БД. Внешний staging/production не развёрнут; dev `.env` и volume не используются release-проверками.

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
- `Backend verify`: Temurin Java 21, Maven Wrapper `verify`, настоящий Docker для Testcontainers; затем JSON Schema проверка реальных HTTP-ответов, Bruno и Schemathesis на собранном JAR с отдельной временной PostgreSQL. [Команды B1](../scripts/b1/README.md). Локальный dev volume и `.env` для тестов не используются.
- `Gitleaks`: CLI 8.30.1, проверка SHA-256 до распаковки и scan всей загруженной Git-истории с `--redact=100`. Это scan истории на CI, не полный аудит секретов.

Все Actions закреплены полными commit SHA. Workflow имеет только `contents: read`, не сохраняет checkout credentials и не получает production-секреты. Нет публикации, push, создания PR или отключения tests. Настройка required checks в GitHub выполняется отдельно после подтверждённого запуска workflow.

## Проверенные источники фиксации

Исходные версии и SHA проверены 20 октября 2026 по первичным источникам; B4 обновил PostgreSQL digest и добавил upload-artifact 26 октября:

| Компонент | Фиксация | Источник |
|---|---|---|
| PostgreSQL | `17.11-bookworm`, multiarch digest `sha256:858bb5e98eed02eb8cafbcdc007f9a65d1b613972028944a8ec2133ccaf80d27` (пересборка 21.10, закреплена B4) | [Поддерживаемая линия](https://www.postgresql.org/support/versioning/), [Docker Official Image](https://hub.docker.com/_/postgres), [metadata тега](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm) |
| checkout | `v7.0.1` → `3d3c42e5aac5ba805825da76410c181273ba90b1` | [Официальный release](https://github.com/actions/checkout/releases/tag/v7.0.1) |
| setup-java | `v6.1.0` → `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` | [Официальный release](https://github.com/actions/setup-java/releases/tag/v6.1.0) |
| setup-python | `v7.0.0` → `5fda3b95a4ea91299a34e894583c3862153e4b97` | [Официальный release](https://github.com/actions/setup-python/releases/tag/v7.0.0) |
| setup-node | `v7.1.0` → `949feb2413d6458794dcd2491c4babbbce0c15c1` | [Официальный release](https://github.com/actions/setup-node/releases/tag/v7.1.0) |
| upload-artifact | `v7.0.2` → `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | [Официальный release](https://github.com/actions/upload-artifact/releases/tag/v7.0.2), [tag → commit](https://api.github.com/repos/actions/upload-artifact/git/ref/tags/v7.0.2) |
| Gitleaks CLI | `8.30.1`, Linux x64 SHA-256 `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb` | [Release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1), [checksums](https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_checksums.txt) |

Исходные Action SHA сверены через `git ls-remote --tags` официальных репозиториев; B4 upload-artifact — через официальный GitHub tag ref и action.yml по commit SHA. Он сохраняет на 7 дней только dependency-audit.json и report.json/commands.log B4, включая неудачный запуск; весь target, SQL, secrets и auth responses не загружаются. Архив Gitleaks Linux x64 скачан для проверки в память, его SHA-256 совпал с upstream checksum. При обновлении версии заново проверить связанные SHA/digest и выполнить соответствующие проверки; не заменять их плавающими tags.
