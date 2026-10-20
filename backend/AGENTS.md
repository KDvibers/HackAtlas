# Backend ownership

Прочитать корневой `AGENTS.md`. Ты отвечаешь за `backend/`, `infra/`, серверный CI и `docs/STATUS_BACKEND.md`.

Java 21, поддерживаемый стабильный Spring Boot 4.x, PostgreSQL 17 с актуальным minor, Flyway, Spring Security, Spring MVC, Spring Data JPA, Maven wrapper внутри `backend/`. Проверить точные версии на старте. Не переносить сервер на Kotlin/Node, не вводить микросервисы, Redis, Kafka или Kubernetes в MVP.

API contract-first. Границу реализации определяют текущий запрос пользователя и `docs/STATUS_BACKEND.md`. Стартовый промпт B0+B1 не разрешает автоматически перейти к B1, если поручен только B0. Не генерировать мобильное приложение и не менять его дизайн. `api/` — совместная зона; вопросы/изменения согласовать с другом до изменения контракта.

Public catalogue без входа. Аккаунты и личные данные требуют авторизации. В будущем opaque bearer sessions по `api/README.md`; не переключать на JWT/refresh-token протокол молча.

Flyway — источник схемы, Hibernate `ddl-auto=validate`. Интеграционные тесты с PostgreSQL, не считать H2 заменой. Docker/Testcontainers недоступны — честно сообщить блокер.

Не выдавать действия пользователя за подтверждение организатора. Не заполнять неизвестное время значением 23:59. DEMO seed только в явно включённом dev/test профиле, отдельно от production миграций.
