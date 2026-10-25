# Зафиксированный мобильный toolchain

Выбран 12 октября 2026 для M0/M1. Это согласованный стабильный набор, а не выбор каждой самой новой версии независимо.

| Инструмент | Версия / цель |
|---|---|
| Kotlin Multiplatform + Compose compiler plugin | 2.2.21 |
| Compose Multiplatform | 1.9.3 |
| Android Gradle Plugin | 8.11.1 |
| Gradle wrapper | 8.13, distribution SHA-256 закреплён |
| JDK для Gradle | 21; локально Android Studio JBR 21.0.9 |
| JVM bytecode | 17 |
| Android compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Android Build Tools | 35.0.0, значение по умолчанию AGP 8.11 |
| AndroidX Activity Compose | 1.10.1 |
| kotlinx.coroutines | 1.10.2 |
| kotlinx.serialization | 1.9.0 |
| kotlinx.datetime | 0.6.2 |
| Ktor core / content-negotiation / serialization / MockEngine / OkHttp / Darwin | 3.3.3 |
| iOS deployment target | 16.0 |
| Kotlin/Native targets | iosArm64, iosSimulatorArm64 |
| Xcode для проверки на Mac | 26.0 по матрице Kotlin 2.2.21; локально отсутствует |

`dev.hackatlas.mobile` — временный development applicationId/bundle ID. Debug использует суффикс `.debug`. Перед публикацией владельцы должны утвердить постоянные идентификаторы и подписи.

## Основания совместимости

- [Матрица KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html): Kotlin 2.2.21 поддерживает Gradle 7.6.3–8.14 и AGP 7.3.1–8.11.1; указан Xcode 26.0.
- [AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes): Gradle 8.13, JDK 17 минимум, API 36 максимум, Build Tools 35.0.0.
- [Compose 1.9.3 release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.9.3) и [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html). Compose compiler закреплён той же версией, что Kotlin.
- [Gradle Java compatibility](https://docs.gradle.org/8.13/userguide/compatibility.html): Java 21 поддерживается для запуска Gradle начиная с 8.5.
- [Compose resources](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html): общий `Res`, изображения, шрифты и строки.

Wrapper scripts/JAR взяты из официального тега Gradle v8.13.0. SHA-256 wrapper JAR: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, совпадает с [официальной суммой](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256).

## M2: HTTP-слой — 25 октября 2026

Для общей фабрики клиента и MockEngine выбран Ktor **3.3.3**. Проверены [release notes](https://github.com/ktorio/ktor/releases/tag/3.3.3) и [upstream version catalog](https://raw.githubusercontent.com/ktorio/ktor/3.3.3/gradle/libs.versions.toml): Kotlin 2.2.21, coroutines 1.10.2 и serialization 1.9.0 совпадают с существующими версиями проекта. Все четыре Ktor-модуля закреплены одной версией; Kotlin, AGP, Gradle и Compose не обновлялись.

Общий код и MockEngine проверяются задачей `:shared:testDebugUnitTest` на Android/JVM. Это не проверка настоящего HTTP-сервера или Kotlin/Native. В Task 4 добавлены OkHttp в androidMain и Darwin в iosMain, оба 3.3.3; Android/common build и тесты проходят, iOS Native пока не проверен. `createApiClient` получает engine извне; владелец закрывает и клиент, и engine. Release-host запрещает `allowDebugHttp`; Android debug требует явный opt-in и config точного host. iOS ATS exceptions отсутствуют.

## Локальное окружение и ограничения

Системный PATH/JAVA_HOME на проверенном Windows-компьютере указывает Java 8. Для команд ниже требуется явно выбрать JDK 21; глобальные настройки пользователя не изменены. Существующий Android SDK используется без переустановки. Абсолютные SDK/JDK пути не закреплены в Gradle-конфигурации.

Наличие iOS targets и Xcode project не означает успешную iOS-сборку. Mac/Xcode недоступны: компиляция Kotlin/Native, Swift, линковка, упаковка ресурсов и запуск на iPhone пока не проверены. Intel iOS simulator не входит в текущие targets; для него нужен отдельно согласованный target/toolchain.

Фактические результаты сборок и UI-проверок находятся в `../docs/STATUS_MOBILE.md`. SDK 36 задаёт заявленную цель компиляции; тест на одном более новом эмуляторе не доказывает работу на всех Android 8+ устройствах.

Task 4 использует [платформенные engines Ktor](https://ktor.io/docs/client-engines.html) без обновления существующего toolchain и [точечную Android network security configuration](https://developer.android.com/privacy-and-security/security-config). У Darwin 3.3.3 [delegate запрещает встроенные redirects](https://raw.githubusercontent.com/ktorio/ktor/3.3.3/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/KtorNSURLSessionDelegate.kt); runtime-проверка этого на iOS остаётся за Mac.
