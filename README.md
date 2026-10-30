# HackAtlas

Мобильный каталог хакатонов для Android и iOS: общий клиент на Kotlin Multiplatform + Compose Multiplatform и сервер на Java 21 + Spring Boot.

**Состояние:** G0–B3 и локальные B4–B4.2 объединены в main (B4.2 — PR #13); восстановленная БД закрыта до операторского review удалений. В `fix/backend-runtime-security` обновлены официальные Java/PostgreSQL images для B4.3. Напарник завершил live-приёмку публичного B1 на Android-эмуляторе (PR #12); весь M2 ещё не завершён. Публичный каталог доступен без входа. Команды и фактические результаты — в [release runbook](infra/release/README.md) и [статусе backend](docs/STATUS_BACKEND.md). Внешнего staging URL пока нет; commit, push и PR выполняет пользователь.

**Мобильная часть:** реализованы M0/M1 и независимая от backend часть M2: постоянные гостевые закладки, офлайн-копии подробностей и календарь. Визуал M1 утверждён; Android проверен, iOS требует Mac/Xcode. В main также объединены [HTTP-клиент PR #6](https://github.com/KDvibers/HackAtlas/pull/6) и [state stores PR #7](https://github.com/KDvibers/HackAtlas/pull/7) напарника. Их наличие не подтверждает живую интеграцию backend или проверку устройств. См. [mobile README](mobile/README.md), [статус mobile](docs/STATUS_MOBILE.md) и [результаты M2](mobile/artifacts/m2/review.html).

Репозиторий: [KDvibers/HackAtlas](https://github.com/KDvibers/HackAtlas). Единственный актуальный комплект — **Starter**. Handoff больше не является спецификацией.

## Основные документы

- [Начало работы](START_HERE.md), [общий план двух разработчиков](docs/ROADMAP.md) и [правила совместной работы](docs/WORKFLOW.md).
- [Направление продукта](docs/PROJECT.md) и [выбранные технологии](docs/TECHNOLOGY_DECISIONS.md) — ориентиры перед каждым этапом.
- [Исследование технологий и брейнсторминг](docs/research/2026-10-20-technology-research.md) — источники, GitHub-подборка и альтернативы.
- [OpenAPI](api/openapi.yaml) и [семантика API](api/README.md) — общий договор сервера и клиента.
- [Решения проекта](docs/DECISIONS.md) и [аудит этапа G0](docs/SETUP_AUDIT.md).
- [Статус backend](docs/STATUS_BACKEND.md) и [статус mobile](docs/STATUS_MOBILE.md).
- [Проверка контракта](scripts/README.md).
- [Сборка и запуск backend](backend/README.md), [точные версии backend](backend/TOOLCHAIN.md) и [локальная PostgreSQL / CI](infra/README.md).
- [Семантика реализации каталога](backend/CATALOG.md), [Bruno](tools/bruno/README.md) и [проверка работающего API через Schemathesis](scripts/b1/README.md).
- [Подключение Kotlin-клиента к B1 из main](docs/B1_MOBILE_HANDOFF.md): адреса, запуск PostgreSQL/backend, DEMO и проверочный ID.
- [Админка и модерация B3](backend/MODERATION.md), [удаление данных и ограничения хранения](docs/PRIVACY.md), [скриншоты проверки](backend/artifacts/b3/README.md).

Авторизация реализована через **opaque Bearer sessions**, без JWT и refresh endpoint; правила сессий и ограничения описаны в [AUTH](backend/AUTH.md). Регистрация на хакатон проходит на официальном внешнем сайте.

| Ответственный | Область |
|---|---|
| [yergaliuly](https://github.com/yergaliuly) | `backend/`, `infra/`, серверный CI |
| Мобильный разработчик; GitHub handle пока не указан | `mobile/`, мобильные ресурсы и CI |
| Оба разработчика | `api/`, референс, общие продуктовые решения |

Текущая граница — **подготовка и локальные проверки B4**, как уточнил пользователь. Внешний HTTPS-staging, hosting/DNS, backup retention и совместная приёмка M4 требуют следующего согласованного задания; полный B4 и публичный выпуск ещё не объявляются завершёнными.

Проверка контейнеров B4.1 выявила уязвимости и старый JRE. В B4.3 runtime обновлён до Temurin 21.0.13+9; [актуальная проверка и ограничения](docs/research/2026-10-30-runtime-security.md) отделены от исторического отчёта. Успешные локальные тесты сами по себе не разрешают внешний выпуск.
