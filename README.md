# HackAtlas

Мобильный каталог хакатонов для Android и iOS: общий клиент на Kotlin Multiplatform + Compose Multiplatform и сервер на Java 21 + Spring Boot.

**Состояние:** G0–B2 объединены в main (B2 — PR #5). В `feat/backend-moderation` подготовлен B3: админка каталога, очередь предложений/жалоб и удаление аккаунта. Публичный каталог остаётся доступен без входа. Команды запуска и результаты проверок — в [backend README](backend/README.md) и [статусе backend](docs/STATUS_BACKEND.md). Внешнего staging URL пока нет; commit, push и PR выполняет пользователь.

**Мобильная часть:** реализованы M0/M1 и независимая от backend часть M2: постоянные гостевые закладки, офлайн-копии подробностей и календарь. Визуал M1 утверждён; Android проверен, iOS требует Mac/Xcode. В main также объединён [PR #6](https://github.com/KDvibers/HackAtlas/pull/6) напарника с HTTP-слоем каталога и MockEngine tests; подключение живого backend к экранам остаётся отдельным шагом. Эта серверная ветка создана до PR #6 и не включает его мобильные файлы до объединения с main. См. [mobile README](mobile/README.md), [статус mobile](docs/STATUS_MOBILE.md) и [результаты M2](mobile/artifacts/m2/review.html).

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

Следующий отдельный этап backend — **B4**, эксплуатационная подготовка и согласованный deployment. Он не начат; текущая граница — проверенный локально B3.
