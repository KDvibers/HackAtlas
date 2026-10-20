# Этапы: не выполнять всё одним заходом

## G0 — общий старт
Владелец backend создаёт/подготавливает общий репозиторий, добавляет этот комплект и утверждённое фото, согласует API с другом. Оба клонируют одну и ту же main. Никакого отдельного несогласованного API на каждой машине.

## Backend
**B0 — воспроизводимая основа.** Проверка JDK/Docker, выбор stable-версий, Maven wrapper, Spring Boot skeleton, отдельная PostgreSQL, Flyway, конфигурация dev/test/prod, health, базовый CI. Зафиксировать команды.

**B1 — настоящий каталог.** DTO и схема из OpenAPI, dev seed, list/detail/filter-metadata, поиск/фильтры/пагинация/сортировка, общие ошибки, unit + PostgreSQL integration tests. В конце дать другу base URL, доступные endpoints, curl, перечень ограничений. После B0+B1 остановиться.

**B2 — аккаунты и личные данные.** Register/login/logout, opaque bearer sessions, GET/PATCH me, saved PUT/DELETE, личная отметка заявки, приватность, лимиты auth, guest-import semantics, security/integration tests.

**B3 — обслуживание каталога.** Admin-only HTML интерфейс, draft/publish/archive, изменение и сверка дат, предложения/жалобы и их модерация, account deletion. До публичного открытия — email verification/reset password и их согласованное расширение OpenAPI, privacy/security review.

**B4 — staging/release.** HTTPS, отдельный staging, backup+restore test, readiness/health, логи без PII, секреты, миграции, smoke. Только по явному разрешению, никаких покупок/публикаций самостоятельно.

## Mobile
**M0 — собираемый cross-platform каркас.** Проверить окружение, зафиксировать совместимый stable toolchain, Android/iOS hosts, shared UI и data interfaces, build commands. Если Mac отсутствует, iOS честно пометить непроверенным; не удалять цель и не обещать сборку.

**M1 — визуальное ядро.** Theme, fonts/wordmarks, icons, cards, tab bar, catalogue/detail/profile на fixtures. Общий DemoRepository. Реальные screenshots и визуальная приёмка по фото. В первой сессии завершить M0+M1 и остановиться на этой границе.

**M2 — основные сценарии.** Search/filter, remote catalogue adapter по API, local guest saved, календарь сохранённого, browser registration, share, empty/error/offline states. DEMO не должен заменять сетевую ошибку REMOTE.

**M3 — аккаунты и удобство.** Интеграция auth/profile, защищённое хранение токена, saved sync и подтверждаемый импорт гостевых сохранений, личные отметки, локальные reminders, event-report/suggest forms, системный календарь по необходимости. После реализации сервером не держать имитацию успешной отправки.

**M4 — качество и устройства.** Android+iOS реальные проверки, safe areas, клавиатура, large text, screen reader, slow network, logout, expired session, release configuration. Скриншоты approved baseline, debug APK; iOS simulator/device/TestFlight только при доступном окружении и авторизации.

## Совместные контрольные точки
G0 → M0/M1 параллельно B0/B1 → визуальное утверждение → remote catalogue → auth/saved → quality → staging. Друг не ждёт полный backend, потому что fixtures уже общие. Backend не ждёт готовый UI, потому что структура ответов зафиксирована.
