# Инструменты и версии

Обновлено: **20 октября 2026**. Принятый стек и порядок выбора кандидатов закреплены в [TECHNOLOGY_DECISIONS](TECHNOLOGY_DECISIONS.md); источники и альтернативы — в [исследовании](research/2026-10-20-technology-research.md). Этот документ не является lockfile или новой проверкой установленного окружения.

На **B0** фиксируется совместимый набор Java 21, поддерживаемой stable-линии Spring Boot 4.x, Maven Wrapper и PostgreSQL 17 с поддерживаемым minor. Spring/Jackson/Hibernate/Flyway и тестовые интеграции согласуются с Boot BOM; для PostgreSQL нужны соответствующий Flyway-модуль и JDBC driver. Точные версии закрепляются в `backend/TOOLCHAIN.md`, `pom.xml`, wrapper и контейнерной конфигурации после сборки и проверки миграций. Testcontainers требует доступного Docker; его наличие перепроверяется на B0.

На **M0** фиксируется совместимый набор Kotlin Multiplatform, Compose Multiplatform, Kotlin Compose compiler plugin, Gradle Wrapper, Android Gradle Plugin, Android SDK, JDK и Xcode. Проверить официальные таблицы совместимости, минимальные поддерживаемые ОС и доступные targets; результат — в `mobile/TOOLCHAIN.md` и version catalog. Ktor Client использует OkHttp на Android и Darwin на iOS, вместе с совместимыми kotlinx.serialization/coroutines и common date/time API. DI первоначально ручной.

Используются стабильные совместимые релизы, без EAP/alpha/beta/RC/SNAPSHOT, динамических `+` и контейнерных tags `latest`. Наблюдаемые release-версии из исследования не приняты автоматически. Navigation, offline storage, codegen, шрифты и дополнительные библиотеки выбираются по проверкам из TECHNOLOGY_DECISIONS, а не устанавливаются всем списком.

Для iOS требуется macOS + Xcode. Проверить архитектуру Mac/runner и поддерживаемый simulator target; нельзя обещать совместимость любого Intel Mac с современным набором KMP. Недоступная iOS-проверка явно отмечается, Android build её не заменяет.

Maven Wrapper backend и Gradle Wrapper mobile независимы и создаются на B0/M0; на момент фиксации направления реализации и wrappers ещё нет. Системный Maven/Gradle не должен быть единственным способом сборки. Существующая Python-проверка контракта сохраняет собственные [зависимости и команды](../scripts/README.md) и переносится в CI на B0.

Перед этапом проверить фактическое окружение. Исторические сведения — в [SETUP_AUDIT](SETUP_AUDIT.md) и [STATUS_BACKEND](STATUS_BACKEND.md); они не подтверждают текущее наличие SDK. Если нужный SDK, сеть или зависимость недоступны, записать blocker и конкретный следующий шаг. В отчёте указывать реально выполненные команды и результаты, без заявлений о непроведённой сборке.
