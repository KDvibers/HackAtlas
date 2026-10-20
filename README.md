# HackAtlas

Мобильный каталог хакатонов для Android и iOS: общий клиент на Kotlin Multiplatform + Compose Multiplatform и сервер на Java 21 + Spring Boot.

**Состояние:** G0 объединён в main. Локально завершён B0: каркас Java backend, PostgreSQL/Flyway, health и проверенная сборка с интеграционными тестами. Каталог и авторизация ещё не реализованы; готового API для mobile пока нет. Публикация B0 и предшествующего дополнения с общим планом остаётся за пользователем.

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

Авторизация запланирована через **opaque Bearer sessions**, без JWT и refresh endpoint. Регистрация на хакатон проходит на официальном внешнем сайте.

| Ответственный | Область |
|---|---|
| [yergaliuly](https://github.com/yergaliuly) | `backend/`, `infra/`, серверный CI |
| Мобильный разработчик; GitHub handle пока не указан | `mobile/`, мобильные ресурсы и CI |
| Оба разработчика | `api/`, референс, общие продуктовые решения |

Следующий отдельный этап backend — **B1**, три публичных GET каталога. Он ещё не начат и требует отдельного поручения. Push и создание PR выполняет пользователь.
