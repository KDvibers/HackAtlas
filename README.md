# HackAtlas

HackAtlas — мобильный каталог хакатонов для Android и iOS. В приложении можно искать события, сравнивать условия, сохранять интересные хакатоны и планировать участие. Регистрация на событие открывает официальный сайт организатора.

## Возможности

- Публичный каталог с поиском, фильтрами, пагинацией и подробностями события. В карточке есть сроки, формат, условия участия и ссылка на источник.
- Гостевые закладки, локальные копии сохранённых карточек и календарь. Вход нужен для синхронизации закладок, профиля и личных отметок об участии.
- Предложения событий и жалобы проходят модерацию. Администратор проверяет источник, публикует или архивирует карточки и отмечает переносы и отмены.
- Подтверждение почты и сброс пароля проверяются в закрытом N2 с Mailpit. Матрица N2 описывает доступ до подтверждения и правила работы с личными данными офлайн.

## Команда

| GitHub | Область |
| --- | --- |
| [@yergaliuly](https://github.com/yergaliuly) | Backend, PostgreSQL, модерация, инфраструктура и серверный CI |
| [@kasym0055](https://github.com/kasym0055) | Android и iOS, общий Kotlin-клиент, мобильный интерфейс и проверки |
| Оба разработчика | API, продуктовые решения и сквозная приёмка |

## Состояние проекта

| Область | Состояние |
| --- | --- |
| Backend | Публичный каталог, аккаунты, личные данные и модерация входят в `main`. Актуальные проверки и ограничения — в [статусе backend](docs/STATUS_BACKEND.md). |
| Mobile | Kotlin-клиент подключён к каталогу. Android-сценарии проверялись на эмуляторе. Для закрытого N2 остаются нативная проверка удаления аккаунта на Android и приёмка iOS на Mac. См. [мобильные инструкции](mobile/README.md) и [статус mobile](docs/STATUS_MOBILE.md). |
| API | Канонический контракт Starter — **0.1.1**. Клиент использует opaque Bearer с фиксированным сроком сессии. **0.2.0-test.1** предназначен для закрытых проверок N2. См. [OpenAPI](api/openapi.yaml), [семантику API](api/README.md) и [стенд N2](infra/lifecycle-test/README.md). |
| B4 | Подготовка staging описана в [инструкции staging](infra/release/STAGING.md). HTTPS, восстановление и Trivy прошли на [релизном кандидате](https://github.com/KDvibers/HackAtlas/actions/runs/43088840175). Для актуального результата на `main` нужен прогон после интеграции [restic в PR #62](https://github.com/KDvibers/HackAtlas/pull/62). Внешний staging URL пока отсутствует. |

## Стек и структура

| Каталог | Что внутри |
| --- | --- |
| [`backend/`](backend/README.md) | Java 21, Spring Boot, Maven Wrapper, PostgreSQL 17, Flyway. REST API, проверка сессий, Thymeleaf-админка и интеграционные тесты с Testcontainers. |
| [`mobile/`](mobile/README.md) | Kotlin Multiplatform и Compose Multiplatform. Общий код в `shared/`, Android-приложение в `androidApp/`, Xcode-проект в `iosApp/`. |
| [`api/`](api/README.md) | OpenAPI, семантика ошибок и JSON-примеры для обеих сторон. |
| [`infra/`](infra/README.md) | Dev PostgreSQL, закрытый N2 и релизный Compose с Caddy, отдельными ролями БД и file secrets. |
| [`scripts/`](scripts/README.md) | Проверка контракта, HTTP-проверки, релизная репетиция и аудит зависимостей. |
| [`tools/bruno/`](tools/bruno/README.md) | Коллекция запросов к публичному каталогу. |
| [`docs/`](docs/ROADMAP.md) | Общий план, история решений, статусы и инструкции передачи между разработчиками. |

## Локальный запуск

Для backend установите JDK 21 и Docker с Linux-контейнерами и Compose v2. Maven Wrapper лежит в `backend/`. Для Android откройте папку `mobile/` в Android Studio и выберите JDK 21 для Gradle. Для сборки и запуска iOS нужны Mac и совместимый Xcode. Точные версии описаны в инструкциях [backend](backend/TOOLCHAIN.md) и [mobile](mobile/TOOLCHAIN.md).

### 1. Запустите PostgreSQL

Из корня клона в PowerShell создайте локальный файл настроек, если его ещё нет.

```powershell
if (-not (Test-Path -LiteralPath .\infra\.env)) {
    Copy-Item -LiteralPath .\infra\.env.example -Destination .\infra\.env
}
```

Задайте в `infra/.env` собственный случайный `HACKATLAS_DB_PASSWORD`. Файл игнорируется Git. Для Bash используйте `cp infra/.env.example infra/.env`, если `infra/.env` ещё отсутствует. Затем из корня клона запустите Compose в окружении, которому доступен Docker.

```text
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml up --detach --wait --wait-timeout 120 postgres
```

PostgreSQL публикуется на `127.0.0.1:5432`. Если порт занят, проверьте его владельца и согласуйте JDBC URL с выбранным dev-окружением. Детали настроек и остановки БД — в [инструкции dev Compose](infra/README.md).

### 2. Соберите и запустите backend

Из корня клона в PowerShell. Dev-профиль запускает Flyway и читает настройки из `../infra/.env` относительно `backend/`.

```powershell
Set-Location backend
.\mvnw.cmd --batch-mode --no-transfer-progress verify
java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --hackatlas.demo.enabled=true --hackatlas.demo.as-of=2026-10-12T10:00:00Z
```

В Linux/WSL используйте `sh ./mvnw --batch-mode --no-transfer-progress verify` из `backend/`, затем ту же команду `java`. Seed создаёт вымышленные опубликованные события с `isDemo=true`. Фиксированная дата позволяет повторить поиск и фильтрацию на тех же событиях. Для обычного dev-запуска уберите оба аргумента `--hackatlas.demo.*`. Свежая БД без seed может вернуть пустой каталог.

В другом терминале проверьте readiness и три публичные операции. На readiness ожидается `UP`, на запросах каталога — HTTP 200.

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:8080/api/v1/hackathons
Invoke-RestMethod http://127.0.0.1:8080/api/v1/hackathons/39287368-5259-5235-a1c4-25b4e1e8421e
Invoke-RestMethod http://127.0.0.1:8080/api/v1/metadata/filters
```

В Bash используйте `curl --fail` с теми же URL. Для деталей по указанному ID нужен DEMO seed. См. [интеграцию каталога](docs/B1_MOBILE_HANDOFF.md) и [семантику каталога](backend/CATALOG.md).

### 3. Соберите мобильный клиент

Из `mobile/` в PowerShell можно собрать Debug, запустить тесты и проверить Android lint. Обычный Debug по умолчанию показывает встроенный DEMO.

```powershell
.\gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest
.\gradlew.bat :androidApp:lintDebug
```

Для подключения Debug к запущенному backend на компьютере-хосте Android Emulator используйте REMOTE.

```powershell
.\gradlew.bat :androidApp:assembleDebug '-Phackatlas.debugSource=REMOTE' `
  '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8080/api/v1/' `
  '-Phackatlas.allowDebugHttp=true'
```

APK находится в `mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Для JVM внутри WSL сначала проверьте, что порт доступен из Windows. Для физического устройства нужен адрес, до которого оно может дойти. Release Android и REMOTE iOS используют HTTPS. Настройки сборки, установка APK и запуск Xcode описаны в [mobile README](mobile/README.md).

### 4. Проверьте контракт и окружение выпуска

- [Валидатор OpenAPI и fixtures](scripts/README.md) проверяет схемы и примеры без запуска сервера. [Bruno и Schemathesis](scripts/b1/README.md) проверяют ответы запущенного dev API.
- [Локальная репетиция релиза](infra/release/README.md) использует отдельные контейнеры и HTTPS на loopback. Она проверяет миграции, разделение ролей, резервную копию и восстановление в карантин.
- [External release readiness](https://github.com/KDvibers/HackAtlas/actions/workflows/release-readiness.yml) запускается вручную. GitHub Actions собирает кандидата, репетирует релиз и проверяет Trivy для образов и restic. Отчёт относится к SHA запуска.
- [Инструкция staging](infra/release/STAGING.md) задаёт порядок запуска, smoke, восстановления и передачи стенда на M4. Публичное открытие требует отдельной приёмки и согласования по [дорожной карте](docs/ROADMAP.md#условия-перед-публичным-запуском).

## Документация

- [Начало работы](START_HERE.md) · [направление продукта](docs/PROJECT.md) · [дорожная карта](docs/ROADMAP.md) · [совместная работа](docs/WORKFLOW.md).
- [Выбор технологий](docs/TECHNOLOGY_DECISIONS.md) · [принятые решения](docs/DECISIONS.md) · [исследование технологий](docs/research/2026-10-20-technology-research.md).
- [Сессии](backend/AUTH.md) · [модерация](backend/MODERATION.md) · [удаление данных и хранение](docs/PRIVACY.md) · [шифрованные архивы](infra/release/RECOVERY_ARCHIVE.md).
