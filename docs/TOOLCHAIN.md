# Инструменты и версии

Проверено по официальной документации при подготовке задания: 12 октября 2026. Это не lockfile и не доказательство локальной совместимости.

Mobile: Kotlin Multiplatform + Compose Multiplatform, совместимый Kotlin Compose compiler plugin, Gradle, Android Gradle Plugin, Android SDK, JDK, Xcode. Точную комбинацию агент проверяет по официальной таблице и фиксирует в `mobile/TOOLCHAIN.md` и version catalog. Стабильные релизы, без EAP/alpha/beta/SNAPSHOT и без динамических `+`.

Сервер: Java 21; поддерживаемая стабильная линия Spring Boot 4.x; Maven wrapper; PostgreSQL 17 актуального minor. Точные версии — в `backend/TOOLCHAIN.md`, pom и контейнерной конфигурации. Не использовать tag `latest`.

Для iOS требуется доступ к macOS+Xcode; проверить архитектуру Mac/runner и наличие поддерживаемого simulator target. Не обещать, что любой Intel Mac сможет собирать любую современную конфигурацию KMP.

Ktor Client: OkHttp на Android, Darwin на iOS; kotlinx.serialization/coroutines; поддерживаемые common date/time API. Coil 3 для remote images при необходимости. Для небольших несекретных local settings/guest saved можно использовать multiplatform-settings; токен хранится отдельно через SecureTokenStore. DI сначала ручной.

Maven wrapper backend и Gradle wrapper mobile независимы. Их ещё нет в этом комплекте: создать на соответствующих B0/M0. Системный Gradle не должен быть единственным способом сборки.

Если нужный SDK/сеть/зависимость недоступны, записать blocker и точную команду установки/проверки для пользователя. Не выдумывать прошедшую сборку.
