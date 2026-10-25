# HackAtlas

Мобильный каталог хакатонов для Android и iOS: общий клиент на Kotlin Multiplatform + Compose Multiplatform и сервер на Java 21 + Spring Boot.

**Состояние:** G0, B0 и B1 объединены в main (B1 — PR #3). В `feat/backend-auth` подготовлен B2: регистрация/вход/выход, профиль, личные закладки и отметки заявок. Публичный каталог остаётся доступен без входа. Команды запуска и результаты проверок — в [backend README](backend/README.md) и [статусе backend](docs/STATUS_BACKEND.md). Внешнего staging URL пока нет; commit, push и PR выполняет пользователь.

**Мобильная часть:** реализованы M0/M1 и независимая от backend часть M2: постоянные гостевые закладки, офлайн-копии подробностей и календарь. Визуал M1 утверждён; Android проверен, iOS требует Mac/Xcode. Подключение REMOTE-каталога остаётся следующим отдельно согласованным шагом. См. [mobile README](mobile/README.md), [статус mobile](docs/STATUS_MOBILE.md) и [результаты M2](mobile/artifacts/m2/review.html).

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

Авторизация реализована через **opaque Bearer sessions**, без JWT и refresh endpoint; правила сессий и ограничения описаны в [AUTH](backend/AUTH.md). Регистрация на хакатон проходит на официальном внешнем сайте.

| Ответственный | Область |
|---|---|
| [yergaliuly](https://github.com/yergaliuly) | `backend/`, `infra/`, серверный CI |
| Мобильный разработчик; GitHub handle пока не указан | `mobile/`, мобильные ресурсы и CI |
| Оба разработчика | `api/`, референс, общие продуктовые решения |

Следующий отдельный этап backend — **B3**, модерация и удаление аккаунта. Он не начат и требует отдельного поручения после проверки B2.
