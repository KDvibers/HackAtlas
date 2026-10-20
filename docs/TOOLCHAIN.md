# Инструменты и версии

Обновлено: **20 октября 2026**. Принятый стек и порядок выбора кандидатов закреплены в [TECHNOLOGY_DECISIONS](TECHNOLOGY_DECISIONS.md); источники и альтернативы — в [исследовании](research/2026-10-20-technology-research.md). Этот документ не является lockfile или новой проверкой установленного окружения.

На **B0** закреплён и проверен набор Java 21, Spring Boot 4.1.1/BOM, Maven 3.9.16/Wrapper 3.3.4 и PostgreSQL 17.11. Spring/Jackson/Hibernate/Flyway и тестовые интеграции согласованы с Boot BOM; подключены PostgreSQL-модуль Flyway и JDBC driver. Точные версии и проверки — в [backend/TOOLCHAIN](../backend/TOOLCHAIN.md), `pom.xml`, wrapper и контейнерной конфигурации. Testcontainers требует доступного процессу JVM Docker; локально tests выполнены в Ubuntu WSL2.

На **M0** фиксируется совместимый набор Kotlin Multiplatform, Compose Multiplatform, Kotlin Compose compiler plugin, Gradle Wrapper, Android Gradle Plugin, Android SDK, JDK и Xcode. Проверить официальные таблицы совместимости, минимальные поддерживаемые ОС и доступные targets; результат — в `mobile/TOOLCHAIN.md` и version catalog. Ktor Client использует OkHttp на Android и Darwin на iOS, вместе с совместимыми kotlinx.serialization/coroutines и common date/time API. DI первоначально ручной.

Используются стабильные совместимые релизы, без EAP/alpha/beta/RC/SNAPSHOT, динамических `+` и контейнерных tags `latest`. Наблюдаемые release-версии из исследования не приняты автоматически. Navigation, offline storage, codegen, шрифты и дополнительные библиотеки выбираются по проверкам из TECHNOLOGY_DECISIONS, а не устанавливаются всем списком.

Для iOS требуется macOS + Xcode. Проверить архитектуру Mac/runner и поддерживаемый simulator target; нельзя обещать совместимость любого Intel Mac с современным набором KMP. Недоступная iOS-проверка явно отмечается, Android build её не заменяет.

Maven Wrapper backend и Gradle Wrapper mobile независимы: серверный добавлен на B0, мобильный создаётся на M0. Системный Maven/Gradle не должен быть единственным способом сборки. Существующая Python-проверка контракта сохраняет собственные [зависимости и команды](../scripts/README.md); на B0 она включена в CI вместе с backend verify и Gitleaks. Удалённый запуск workflow ждёт пользовательского push.

Перед этапом проверить фактическое окружение. Исторические сведения — в [SETUP_AUDIT](SETUP_AUDIT.md) и [STATUS_BACKEND](STATUS_BACKEND.md); они не подтверждают текущее наличие SDK. Если нужный SDK, сеть или зависимость недоступны, записать blocker и конкретный следующий шаг. В отчёте указывать реально выполненные команды и результаты, без заявлений о непроведённой сборке.
