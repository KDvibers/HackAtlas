# HackAtlas — Technology Research & Product Brainstorm

Дата исследования: **20 октября 2026**.

Это предложения для следующих этапов, а не новые обязательные решения и не отчёт о внедрении. Источники — документация разработчиков, upstream GitHub, файлы лицензий и публичные метаданные репозиториев. Совместимость конкретного набора зависимостей сборкой в этом исследовании не проверялась.

После исследования пользователь поручил закрепить выбранное направление. Его действующая фиксация — [PROJECT](../PROJECT.md), [TECHNOLOGY_DECISIONS](../TECHNOLOGY_DECISIONS.md) и [общий ROADMAP](../ROADMAP.md). Сам отчёт сохраняется как справочник источников и альтернатив; принятие отдельных выводов не превращает весь список кандидатов в обязательные зависимости.

## 1. Главный вывод

У HackAtlas уже подходящий стек. Наибольшую пользу сейчас дадут воспроизводимая сборка, тесты настоящей PostgreSQL, проверка API и единые сценарии для двух разработчиков. Для продукта важнее всего достоверные события, понятные сроки и удобный путь «нашёл → сохранил → открыл регистрацию».

Рекомендую сохранить **Java 21 + Spring Boot 4.x + PostgreSQL 17** и **Kotlin Multiplatform + Compose Multiplatform**. Начать с небольшого числа библиотек. Новые зависимости добавлять, когда можно назвать сценарий, который они упрощают, и проверить его на обеих нужных платформах.

| Приоритет | Что сделать | Практический результат |
|---|---|---|
| B0 | Maven Wrapper, Boot BOM, Flyway, PostgreSQL 17, Testcontainers, минимальный CI | Сервер одинаково собирается локально и в CI; миграции проверяются на реальной БД |
| B1 | Явные DTO, проверки сериализации, каталог и запросы по Starter | Клиент получает ровно согласованные поля, даты, ошибки и сортировки |
| M0/M1, у напарника | Совместимый stable toolchain, образец шрифтов, три экрана на fixtures | Раннее подтверждение сборки и совпадения с утверждённым стилем |
| Совместная работа | Существующий contract validator, затем oasdiff и небольшая Bruno-коллекция | Изменения API и воспроизводимые запросы видны обоим разработчикам |
| M2/M3 | Ktor, локальные сохранения, платформенные адаптеры, защищённый token store | Рабочий пользовательский сценарий, включая offline и истёкшую сессию |
| B3 | Редакторский процесс и очередь проверки источников | Каталог остаётся полезным после первого наполнения |
| B4 | HTTPS, health, аккуратные логи, проверенное восстановление БД | Staging можно обслуживать и восстанавливать |

## 2. Что учтено в проекте

Просмотрены корневые и вложенные инструкции, PROJECT, ROADMAP, WORKFLOW, TOOLCHAIN, STYLE_LOCK, API и статусы. После `git fetch origin` основа исследования — `origin/main`, merge commit `0128f7b`; содержимое совпало с подготовленным G0. Рабочая ветка исследования — `chore/technology-research`.

На момент проверки backend и mobile ещё не содержат реализации приложения. G0 подготовил спецификацию и её валидатор; B0 остаётся следующим отдельным этапом. Сведения об SDK из старого setup-аудита не считаются новой проверкой окружения.

Исследование сохраняет следующие ограничения:

- Единственный контракт — [api/openapi.yaml](../../api/openapi.yaml) вместе с [api/README.md](../../api/README.md). Handoff не используется.
- Авторизация — opaque Bearer sessions; JWT, refresh endpoint и автоматическое продление не предлагаются.
- Общий Android/iOS-клиент остаётся у напарника. Мобильные рекомендации передаются ему.
- Black/magenta/cyan, пиксельные заголовки и wireframe-графика сохраняются. Чужое приложение служит примером реализации отдельных задач.
- В MVP нет команд, чата, оплаты, LLM-рекомендаций и массового сборщика событий.
- Push, PR и публикацию выполняет пользователь. Исследование не запускает B0/B1 автоматически.

## 3. Backend: что действительно понадобится

### Рекомендуемая основа

Spring MVC, Spring Data JPA, PostgreSQL и Flyway достаточны для каталога, аккаунтов, сохранений и простой админки. Рекомендуемая организация — монолит с feature-пакетами, DTO на границе API и транзакциями в сервисах. Первоначально ручное преобразование нескольких DTO проще дополнительного генератора mappings.

**Spring Boot 4 требует актуальных примеров.** Он использует Jackson 3; основные Java packages переехали в `tools.jackson`, а annotations остаются в `com.fasterxml.jackson`. Изменилась модульная организация starters и тестовых зависимостей. Версии Spring, Jackson, Hibernate и интеграций выбирать согласованно через Boot BOM. Отдельный «самый свежий» Flyway или Spring Security не обязательно подходит выбранному Boot. [Migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide), [JSON в Boot](https://docs.spring.io/spring-boot/reference/features/json.html).

Для PostgreSQL Flyway требует отдельный модуль `flyway-database-postgresql` и JDBC driver. Схемой управляют миграции, Hibernate проверяет её через `ddl-auto=validate`. DEMO seed включается только для dev/test и не становится production-миграцией. [Flyway PostgreSQL](https://documentation.red-gate.com/fd/postgresql-database-277579325.html).

Testcontainers позволяет проверять реальные запросы и миграции PostgreSQL. В версии 2 изменились координаты модулей и packages; например, PostgreSQL artifact называется `testcontainers-postgresql`. В Boot полезна интеграция `@ServiceConnection`; lifecycle контейнера должен соответствовать lifecycle использующего его Spring context. Доступный Docker — обязательное условие таких тестов. [Testcontainers 2](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0), [PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/), [Boot integration](https://docs.spring.io/spring-boot/4.0/reference/testing/testcontainers.html).

### Авторизация: полезная библиотека, прежний протокол

Spring Security подходит для password hashing, security chains и проверки доступа. Его типовой пример `opaqueToken()` относится к OAuth introspection и сам по себе не реализует наши sessions. Для HackAtlas остаётся локальная проверка hash токена, срока, отзыва и аккаунта в PostgreSQL. Возможны собственный `AuthenticationProvider` или осознанно настроенный локальный introspector; отдельный OAuth-сервер не нужен. [Authentication architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html), [Opaque token support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/opaque-token.html).

На B2 отдельно проверить Problem-ответы 401/403, абсолютный expiry, немедленный logout, изоляцию двух пользователей и отсутствие токенов в логах. Для будущей cookie-based HTML-админки нужна отдельная настройка защиты, включая CSRF; правила API нельзя переносить на неё механически.

### Поиск: сначала возможности PostgreSQL

Для B1 достаточно параметризованного поиска по согласованным полям и AND-фильтров. `pg_trgm` может ускорять `ILIKE` и помогать с похожими строками; запросы вроде `AI` могут не давать пригодных триграмм. FTS полезен для токенизации и ранжирования, но его качество следует проверить на реальном RU/KZ/EN-корпусе. Нельзя незаметно заменить контрактную сортировку на relevance. [pg_trgm PostgreSQL 17](https://www.postgresql.org/docs/17/pgtrgm.html), [FTS PostgreSQL 17](https://www.postgresql.org/docs/17/textsearch-controls.html).

Практический эксперимент: одинаковая выборка и запросы `AI`, `C++`, `Java`, «Астана», «Алматы», казахские буквы, латиница и опечатки; затем `EXPLAIN ANALYZE`, число запросов и результат поиска. Elasticsearch/Meilisearch вводить только после измеренного ограничения PostgreSQL и отдельного решения.

### GitHub-подборка для backend

«Сейчас» означает ближайший соответствующий этап, а не установку в рамках исследования. Все 12 репозиториев ниже при проверке GitHub API были не архивированы; наблюдалась активность в сентябре–октябре 2026. Это не гарантия совместимости каждого release.

| Проект | Лицензия | Как использовать в HackAtlas | Когда / ограничение |
|---|---|---|---|
| [spring-projects/spring-boot](https://github.com/spring-projects/spring-boot) | Apache-2.0 | Каркас, BOM, конфигурация, тестовые интеграции | B0; stable 4.x, точный набор закрепить после сборки |
| [spring-projects/spring-security](https://github.com/spring-projects/spring-security) | Apache-2.0 | PasswordEncoder, authentication и authorization | B2; не копировать JWT/refresh tutorial |
| [flyway/flyway](https://github.com/flyway/flyway) | Apache-2.0 для OSS | Версионирование схемы | B0; коммерческие функции не считать бесплатной частью |
| [postgres/postgres](https://github.com/postgres/postgres) | PostgreSQL License | БД, индексы, официальный исходный код | PostgreSQL 17 с поддерживаемым minor; GitHub — зеркало |
| [spring-projects/spring-data-jpa](https://github.com/spring-projects/spring-data-jpa) | Apache-2.0 | Репозитории, DTO projections, транзакции | B1; проверить SQL, N+1 и пагинацию |
| [testcontainers/testcontainers-java](https://github.com/testcontainers/testcontainers-java) | MIT | PostgreSQL integration tests | B0/B1; нужен Docker |
| [TNG/ArchUnit](https://github.com/TNG/ArchUnit) | Apache-2.0 | Несколько правил зависимостей и отсутствия циклов | B1 при появлении слоёв; не строить большой набор формальных запретов |
| [bucket4j/bucket4j](https://github.com/bucket4j/bucket4j) | Apache-2.0 | Лимиты login/register, позже suggestions/reports | B2/B3; local buckets ограничивают только один instance |
| [mapstruct/mapstruct](https://github.com/mapstruct/mapstruct) | Apache-2.0 | Генерация повторяющихся DTO mappings | Позже, когда повторение станет заметным |
| [jOOQ/jOOQ](https://github.com/jOOQ/jOOQ) | Apache-2.0 для OSS; есть другие editions | Сложные SQL-запросы с типизацией | Запасной вариант; второй persistence-подход пока не нужен |
| [spring-projects/spring-modulith](https://github.com/spring-projects/spring-modulith) | Apache-2.0 | Проверка границ модулей, документация | После роста feature-модулей; не добавлять event infrastructure заранее |
| [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) | Apache-2.0 | Образцы Maven, validation, tests, будущего HTML admin | Справочник; не готовый backend HackAtlas |

Три ограничения, которые легко пропустить:

1. Вложенные JPA projections могут материализовать join. Для страницы карточек с темами нужно измерять SQL; иногда лучше запрос страницы плюс пакетная загрузка тем. [JPA projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html).
2. MapStruct `NullValuePropertyMappingStrategy.IGNORE` не реализует наш PATCH: явный null должен очищать допустимое nullable-поле, отсутствующее — сохранять. Нужен учёт присутствия поля и allowlist изменяемых свойств. [MapStruct reference](https://mapstruct.org/documentation/stable/reference/html/).
3. Java-совместимость jOOQ зависит от линии и edition: в таблице OSS 3.21 соответствует Java 21, OSS 3.22 — Java 25. Обновление «до последнего» способно нарушить выбранный стек. [JDK matrix](https://www.jooq.org/download/support-matrix-jdk).

Для Bucket4j первоначально достаточно core и ограниченного хранения local buckets. За reverse proxy нельзя доверять произвольному `X-Forwarded-For`. Общие лимиты для нескольких instances потребуют отдельного решения; Redis заранее не добавляется.

## 4. Mobile: общий UI без лишней архитектуры

### Основа и навигация

KMP и Compose Multiplatform для Android/iOS имеют stable-статус. Точную комбинацию Kotlin, Compose compiler, Gradle, AGP, JDK и Xcode необходимо выбирать по официальным матрицам и фиксировать после smoke-build. Поддерживаемые диапазоны не равны проверенному набору. Для iOS нужен доступ к подходящему macOS/Xcode; Windows-проверка не подтверждает iOS. [Platform status](https://kotlinlang.org/docs/multiplatform/supported-platforms.html), [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html), [Kotlin matrix](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html).

Для четырёх вкладок рекомендую сначала проверить стабильный first-party Navigation 3: сохранение back stack вкладок, открытие события по ID и восстановление состояния на iOS. Decompose — альтернатива, если действительно нужна его модель lifecycle-aware компонентов. Одновременно два navigation framework не нужны. Ручной DI уже предусмотрен PROJECT; Koin/Hilt не требуется вводить для небольшого графа объектов. [Navigation 3](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html), [Decompose](https://arkivanov.github.io/Decompose/).

### Сеть, хранение, изображения

- **Ktor + kotlinx.serialization + coroutines/StateFlow:** shared DTO/repositories/state; OkHttp engine на Android, Darwin на iOS. Bearer отправляется только своему API. Refresh-примеры из документации Ktor не соответствуют Starter. [Engines](https://ktor.io/docs/client-engines.html), [Serialization](https://ktor.io/docs/client-serialization.html), [Bearer authentication](https://ktor.io/docs/client-bearer-auth.html).
- **multiplatform-settings:** настройки и небольшой набор guest saved IDs. Для полноценного offline-каталога выбрать одну SQLite-библиотеку: Room KMP либо SQLDelight. Room 3 — отдельная линия с `androidx.room3`; примеры документации могут содержать alpha-версии. Room 2.8 повысил minSDK до 23, поэтому минимумы зависимостей проверяются отдельно. [Settings](https://github.com/russhwolf/multiplatform-settings), [Room 3](https://developer.android.com/jetpack/androidx/releases/room3), [Room 2](https://developer.android.com/jetpack/androidx/releases/room), [SQLDelight](https://github.com/sqldelight/sqldelight).
- **Coil 3:** загрузка и кеширование реальных remote images, когда они появятся. Для M1 согласованные локальные иллюстрации достаточны. Сетевой клиент изображений не получает Bearer-токен нашего API. [Coil](https://coil-kt.github.io/coil/getting_started/).

Offline-кеш — отдельное состояние с отметкой свежести. Сетевая ошибка не включает DEMO. Данные аккаунтов разделяются; logout очищает соответствующее приватное состояние. Импорт гостевых сохранений после входа выполняется только после явного согласия.

### Платформенные адаптеры

`SecureTokenStore`: iOS Keychain; Android — шифрование токена с ключом в Keystore и отдельное хранение ciphertext. Keystore хранит ключи, а не произвольный token string. `EncryptedSharedPreferences` deprecated, а `KeychainSettings` библиотеки settings отмечен experimental, поэтому их не следует автоматически выбирать для нового stable-only проекта. [Keychain](https://developer.apple.com/documentation/security/keychain-services), [Keystore](https://developer.android.com/privacy-and-security/keystore), [EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences).

`BrowserLauncher`, `ShareService`, `ReminderScheduler`, при необходимости `CalendarExporter` остаются небольшими платформенными интерфейсами. Для напоминаний — системные разрешения, отмена и перепланирование при изменении события. Обычное предварительное напоминание не требует exact alarm. DATE-дедлайн не превращается в выдуманное время организатора: пользователь выбирает время собственного напоминания. [Android alarms](https://developer.android.com/develop/background-work/services/alarms), [Apple local notifications](https://developer.apple.com/documentation/usernotifications/scheduling-a-notification-locally-from-your-app).

### GitHub-подборка для клиента

Перечисленные репозитории при проверке не были архивированы. Отдельно отмечены более старые релизы и ограниченная применимость.

| Проект | Лицензия | Что полезно | Когда / ограничение |
|---|---|---|---|
| [Kotlin/KMP-App-Template](https://github.com/Kotlin/KMP-App-Template) | Apache-2.0 | Актуальная структура KMP-проекта и hosts | M0; не переносить все targets и зависимости автоматически |
| [JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) | Apache-2.0 | Framework, upstream examples/issues | M0/M1; инструмент реализации выбранного дизайна |
| [JetBrains/kotlinconf-app](https://github.com/JetBrains/kotlinconf-app) | Apache-2.0 | Расписание, общий UI, Android/iOS-интеграции | Один из наиболее близких образцов по задачам; его Ktor backend нам не нужен |
| [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | StateFlow, cancellation, structured concurrency | M0/M1 |
| [Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 | DTO и fixtures | M0/M1; проверить compiler compatibility |
| [ktorio/ktor](https://github.com/ktorio/ktor) | Apache-2.0 | HTTP client, engines, test utilities | M2; opaque token без refresh |
| [russhwolf/multiplatform-settings](https://github.com/russhwolf/multiplatform-settings) | Apache-2.0 | Несекретные settings и небольшие guest saved | M2; latest release при проверке — 1.3.0 от 2024-11-29 |
| [coil-kt/coil](https://github.com/coil-kt/coil) | Apache-2.0 | Remote images и image cache | M2 при необходимости |
| [sqldelight/sqldelight](https://github.com/sqldelight/sqldelight) | Apache-2.0 | SQL-first offline storage | Когда snapshots/выборки перерастут простые settings |
| [arkivanov/Decompose](https://github.com/arkivanov/Decompose) | Apache-2.0 | Навигация и lifecycle компонентов | Альтернатива Nav3, а не обязательная добавка |
| [adrielcafe/voyager](https://github.com/adrielcafe/voyager) | MIT | Более простой navigation API | Не default: latest stable при проверке — 1.0.1 от 2024-12-19; нужна проверка совместимости |
| [android/nowinandroid](https://github.com/android/nowinandroid) | Apache-2.0 | Android offline/testing/build patterns | Изучать отдельные решения; это не KMP/iOS-каркас |

Старый [JetBrains/compose-multiplatform-template](https://github.com/JetBrains/compose-multiplatform-template) архивирован; основой нового проекта его выбирать не стоит. Старый release у sample не доказывает отсутствие разработки: например, Now in Android имеет свежую активность, несмотря на старый GitHub release.

### Как сохранить выбранный визуальный стиль

Для пиксельных заголовков найдены **Press Start 2P** и **Tiny5**, оба OFL 1.1. При исследовании таблиц glyphs в памяти проверены символы «Хакатоны», «Профиль», «Сохранённое», «Й», «Ё», `AI Sprint 2026`, `₽ ₸ $ €`. Файлы шрифтов в проект не добавлялись. Наличие символов не подтверждает совпадение с изображением: следующий шаг — небольшой TypographySpecimen и визуальное сравнение на Android/iOS. [Press Start 2P](https://github.com/google/fonts/blob/main/ofl/pressstart2p/METADATA.pb), [Tiny5](https://github.com/google/fonts/blob/main/ofl/tiny5/METADATA.pb), [OFL](https://github.com/google/fonts/blob/main/ofl/tiny5/OFL.txt).

Для контурных иконок полезны [Lucide](https://github.com/lucide-icons/lucide) с ISC-лицензией и [Material Symbols](https://github.com/google/material-design-icons) с Apache-2.0. Выбор отдельных символов не требует изменения внешнего вида интерфейса. Предпочтительны проверенные vector paths/ImageVector и accessibility labels; сложные wireframe/glow-ассеты можно поставлять отдельными PNG/WebP. SVG в Compose resources имеет платформенные ограничения, особенно на Android. [Compose resources](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html).

Не стоит начинать с тяжёлой animation-библиотеки: локальное свечение и короткие переходы можно сделать средствами Compose. Визуальный критерий — реальные screenshots трёх экранов; доступность — крупный текст, TalkBack/VoiceOver, контраст, размеры нажатия и reduced motion.

Общие Compose UI test API пока помечены Experimental; Android Preview Screenshot Testing — alpha и не проверяет iOS. Для stable-only M1 они не могут быть единственной обязательной системой приёмки. [Common tests](https://kotlinlang.org/docs/multiplatform/compose-test.html), [Screenshot limitations](https://developer.android.com/studio/preview/compose-screenshot-testing).

## 5. API и инструменты совместной разработки

### Сохранить работающий contract-check

В G0 уже есть [validate_contract.py](../../scripts/validate_contract.py): проверка OpenAPI 3.1, локальных refs, fixtures, форматов и проектных правил. Новый инструмент должен закрывать другую задачу.

Рекомендуемая последовательность:

1. Перенести существующую проверку в CI.
2. На первом изменении API добавить **oasdiff** для сравнения с main.
3. На B1 сохранить небольшую **Bruno**-коллекцию: каталог, поиск, пустой результат, детали, not found, metadata.
4. После появления сервера проверить три публичные операции через **Schemathesis** на disposable test DB.
5. Redocly/Spectral подключать только при необходимости документации или дополнительных правил.

### Почему генерацию клиента пока нельзя принять на веру

Наш `EventTime` использует `anyOf` с `null` и `allOf/if/then/const`. Поддержка «OpenAPI 3» генератором не гарантирует сохранение этих условных правил и различия absent/null. У Kotlin OpenAPI Generator существуют разные targets `jvm-ktor` и `multiplatform`; опция wrappers для oneOf/anyOf ограничена определённой JVM/Retrofit-конфигурацией. [Kotlin generator](https://openapi-generator.tech/docs/generators/kotlin/).

Предлагаю небольшой изолированный эксперимент: сгенерировать EventTime, Prize, Detail и Profile; собрать common/Android и доступный iOS target; прогнать fixtures и отрицательные случаи. Если generated code слишком сложен или теряет семантику, вручную описать небольшую модель DTO и оставить контрактные тесты. В Starter 24 schemas, но это не означает ровно 24 Kotlin-класса.

oasdiff документирует поддержку условных схем OAS 3.1, но его результат также следует проверить на изменениях нашего EventTime. Он помогает найти несовместимость структуры; изменение смысла `OPEN` или `lastVerifiedAt` всё равно требует человеческого review. [oasdiff OAS 3.1](https://github.com/oasdiff/oasdiff/blob/main/docs/OPENAPI-31.md).

### Полезные репозитории и альтернативы

| Проект | Лицензия | Для чего | Решение |
|---|---|---|---|
| [oasdiff/oasdiff](https://github.com/oasdiff/oasdiff) | Apache-2.0 | Найти потенциально breaking API changes | Добавить при первом изменении контракта |
| [usebruno/bruno](https://github.com/usebruno/bruno) | MIT core | Запросы и окружения в файлах, общий review через Git | B1; реальные credentials вне Git |
| [schemathesis/schemathesis](https://github.com/schemathesis/schemathesis) | MIT | Генерация API test cases из схемы | B1, только тестовый сервер; не заменяет бизнес-тесты |
| [OpenAPITools/openapi-generator](https://github.com/OpenAPITools/openapi-generator) | Apache-2.0 | Генерация DTO/client | Только эксперимент до решения об использовании |
| [Redocly/redocly-cli](https://github.com/Redocly/redocly-cli) | MIT | Lint, bundle, HTML API docs | Опционально; учитывать telemetry |
| [stoplightio/spectral](https://github.com/stoplightio/spectral) | Apache-2.0 | Собственные правила оформления API | Альтернатива lint-функциям Redocly |
| [wiremock/wiremock](https://github.com/wiremock/wiremock) | Apache-2.0 | Управляемые задержки, ошибки и HTTP faults | Когда нужны network-failure scenarios |
| [stoplightio/prism](https://github.com/stoplightio/prism) | Apache-2.0 | Временный HTTP mock по OpenAPI | Если DemoRepository недостаточно; не использовать как доказательство auth/sync |
| [gitleaks/gitleaks](https://github.com/gitleaks/gitleaks) | MIT CLI | Поиск распространённых секретов в файлах и истории | B0; вывод с redaction |
| [renovatebot/renovate](https://github.com/renovatebot/renovate) | AGPL-3.0 | Управляемые dependency updates | Альтернатива Dependabot, если потребуются сложные правила |
| [caddyserver/caddy](https://github.com/caddyserver/caddy) | Apache-2.0 | Reverse proxy и автоматическое HTTPS | B4 |
| [micrometer-metrics/micrometer](https://github.com/micrometer-metrics/micrometer) | Apache-2.0 | Метрики через Spring Boot | Совместимая версия из BOM |
| [getsentry/sentry](https://github.com/getsentry/sentry) | FSL-1.1-Apache-2.0 | Error reporting | Позже; сервер не обычный permissive OSS |

Для oasdiff, Redocly, OpenAPI Generator, Schemathesis, Spectral и WireMock проверены доступность upstream и отсутствие архивирования через API. Для остальных использовались README, лицензии и доступные release feeds. Полноценный аудит кода каждого проекта не проводился.

На M0/M1 общий DemoRepository уже позволяет работать независимо от backend. Сразу держать Prism, WireMock и ещё один mock service не нужно. HTTP mocks полезны для задержек/ошибок, но не воспроизводят автоматически правила импорта saved и отзыва сессии. [Prism](https://github.com/stoplightio/prism), [WireMock faults](https://wiremock.org/docs/simulating-faults/).

### Небольшой CI вместо большого набора сервисов

На B0 достаточно двух направлений проверки: контракт и backend build/tests. Для PostgreSQL предпочтителен одинаковый Testcontainers-подход локально и в Linux CI. PostgreSQL service container — альтернатива, когда она обоснована окружением. Mobile/common/Android job принадлежит напарнику; iOS job добавляется с проверенным Mac runner и понятной стоимостью.

Actions фиксируются на проверенных commit SHA, permissions минимальны, secrets не передаются недоверенному PR-коду. Обязательная итоговая проверка запускается для каждого PR; фильтрация путей внутри workflow не должна оставлять required check незапущенным. Не запускать код PR с привилегиями через `pull_request_target`. [GitHub secure use](https://docs.github.com/en/actions/reference/security/secure-use), [Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage).

После появления dependency files рекомендую сначала **Dependabot** с еженедельными сгруппированными обновлениями и без automerge. Kotlin/Compose/AGP и Spring/BOM обновлять согласованно. Renovate полезен, если встроенных правил не хватит. Оба инструмента одновременно не нужны. **Боты создают PR: включение потребует отдельного решения пользователя и не входит в это исследование.** [Dependabot](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependabot-version-updates), [Supported ecosystems](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories).

Gitleaks CLI удобнее дополнительной Action-обвязки: у `gitleaks-action` отдельные условия, включая бесплатный license key для organization repositories. Успешный scan не является полной гарантией отсутствия секретов. GitHub Secret Protection для приватного organization repo нельзя считать автоматически бесплатным; тариф и настройки HackAtlas не проверялись. [Gitleaks Action](https://github.com/gitleaks/gitleaks-action), [GitHub secret scanning availability](https://docs.github.com/en/enterprise-cloud%40latest/code-security/how-tos/secure-your-secrets/detect-secret-leaks/enable-secret-scanning).

## 6. События: где брать данные и чему доверять

Мой главный продуктовый вывод: источник преимуществ HackAtlas — качественный отбор для Казахстана и подходящих международных online-событий. Большая таблица непроверенных хакатонов ухудшит опыт, даже если технически быстро загружается.

| Источник / инструмент | Что подтверждено | Рекомендация |
|---|---|---|
| [Astana Hub Events](https://astanahub.com/en/event/) | Региональные объявления и события, включая хакатоны; есть RU/KZ/EN | Начальный список кандидатов; сверять конкретную страницу организатора и условия |
| [Devpost](https://help.devpost.com/article/307-how-hackathons-on-devpost-work) | Платформа проведения; за многие события отвечают независимые организаторы | Проверять Rules/eligibility/deadline каждого события; не считать aggregator-поле окончательной истиной |
| [MLH](https://www.mlh.com/) | Официальный каталог и сообщество хакатонов | Дополнительный источник обнаружения; географию и допуск проверять отдельно |
| [Luma API](https://docs.luma.com/reference/getting-started-with-your-api) | Требует Luma Plus; ключ ограничен календарём | Возможный канал партнёра с собственным календарём, не универсальная бесплатная лента |
| [Kaggle CLI](https://github.com/Kaggle/kaggle-cli) | Официальный CLI, Apache-2.0, умеет перечислять competitions | Позднее, только если отдельно решим включать ML competitions в продукт |
| [Hackalist](https://github.com/Hackalist/Hackalist.github.io) | JSON по году/месяцу и community contributions; MIT указан для site code | Изучить модель коллективного наполнения; актуальность покрытия 2026 не подтверждена |
| [open-hackathons-api](https://github.com/WebDevHarsha/open-hackathons-api) | Сторонний JSON-каталог; README заявляет MIT и ежедневное обновление | Не делать production-зависимостью без отдельной проверки данных и лицензирования |

В проверенных официальных материалах не подтверждён универсальный публичный API Devpost/MLH, закрывающий все наши поля и правила. Это ограничение исследования, а не доказательство отсутствия любых партнёрских интеграций.

У `open-hackathons-api` пример содержит строковые сроки вроде `Nov 09 - 20, 2025` и `11 days left`; это не наш EventTime с precision/timezone. README описывает выгрузку из собственной MongoDB, а не гарантированный процесс первичной проверки организатора. Заявление «daily» само по себе не подтверждает актуальность каждой записи. Упоминание MIT в README не заменяет проверку конкретного LICENSE и прав на исходный контент перед использованием.

### Рекомендуемый процесс наполнения

1. Собрать небольшой пилотный набор — ориентировочно 20–30 подходящих событий, если столько найдётся. Это рабочая гипотеза объёма, не подтверждённый размер рынка.
2. Для каждого сохранить официальный источник; различать обнаружение, редактирование и последнюю фактическую проверку.
3. Проверить registration deadline, event dates, timezone/precision, eligibility, формат финала, individual application и условия команды.
4. Неизвестные суммы/даты/ограничения оставлять unknown/null по контракту.
5. Публиковать после проверки; предложения пользователей поступают в PENDING.
6. Приоритет повторной сверки: близкий дедлайн, открытые reports, старый `lastVerifiedAt`.

Обновление `updatedAt` не означает проверку источника. Online не означает worldwide eligibility. Нажатие внешней ссылки не доказывает регистрацию или подачу заявки. Это уже требования Starter; редакторский процесс должен обеспечивать их.

Для будущих импортеров пригодится [jsoup](https://github.com/jhy/jsoup), MIT: разбор предоставленного HTML или заранее согласованных источников. Он не решает разрешение на использование данных, их достоверность или SSRF. По текущему API сервер не должен автоматически скачивать произвольную ссылку из suggestions.

Для возможного серверного экспорта `.ics` пригодится [iCal4j](https://github.com/ical4j/ical4j), BSD-3-Clause. Это Java-библиотека; она не является зависимостью KMP commonMain. В текущем MVP календарь сохранённых событий строится на клиенте, дополнительный серверный календарь не требуется.

## 7. Развёртывание и обслуживание

### Два разумных варианта для B4

| Вариант | Преимущество | Цена в работе команды | Когда выбрать |
|---|---|---|---|
| Один VPS: Caddy + Spring Boot + PostgreSQL в Compose | Понятная схема, полный контроль | Обновления ОС/БД, disk space, backups и восстановление на нас | Если есть готовность регулярно обслуживать сервер |
| Managed application + managed PostgreSQL | Меньше задач обслуживания, возможны managed backups | Регулярный тариф, ограничения провайдера, регион/retention/egress | Если время разработчиков важнее минимального счёта |

Конкретный провайдер, регион, бюджет, RPO/RTO и политика хранения пока не выбраны. Публикация и покупки не выполнялись. Один VPS имеет общий риск отказа; Compose не превращает его в отказоустойчивый кластер.

Caddy автоматизирует HTTPS при выполнении требований DNS и сетевой доступности. Actuator может предоставлять минимальный health, а подробности окружения и БД остаются закрытыми. На первых этапах достаточно HTTP errors/latency, JVM memory, connection pool и логов без токенов/PII. OpenTelemetry и отдельный observability stack добавляются при конкретной потребности. [Caddy HTTPS](https://caddyserver.com/docs/automatic-https), [Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [Spring observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html).

Backup считается рабочим после восстановления в новую БД и smoke-проверки. `pg_dump -Fc` даёт snapshot одной базы; роли и прочие global objects требуют отдельного учёта. При требованиях к меньшей потере данных нужен managed backup/PITR или соответствующая эксплуатационная схема. [PostgreSQL 17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html).

### Что обнаружено про бесплатные сервисы

- **Render Free** подходит для временной демонстрации: web service засыпает после 15 минут без активности; free PostgreSQL истекает через 30 дней и не предоставляет backups. Его нельзя планировать как постоянную production-БД. [Render Free](https://render.com/docs/free).
- **Sentry self-hosted** требует отдельного обслуживания и значительных ресурсов: документация указывает минимум 4 CPU, 16 GB RAM и 16 GB swap. Cloud free рассчитан на одного пользователя. Для двух разработчиков сначала оценить тариф/объём данных; серверная лицензия FSL отличается от MIT/Apache. Подключение crash/error SDK требует фильтрации отправляемых данных. [Self-hosted](https://develop.sentry.dev/self-hosted/), [Pricing](https://sentry.io/pricing/), [Server license](https://github.com/getsentry/sentry/blob/master/LICENSE.md).
- **Docker Desktop** на Windows обычно используется с WSL2; нужны подходящая ОС и виртуализация. У Desktop отдельные условия бесплатного использования, которые не следует смешивать с лицензией Engine/Compose. Новая проверка установленного Docker/WSL в исследовании не выполнялась. [Windows setup](https://docs.docker.com/desktop/setup/install/windows-install/), [Desktop license](https://docs.docker.com/subscription-billing/desktop-license/).

## 8. Брейнсторминг продукта

Ниже мои гипотезы, а не подтверждённые пользовательским исследованием преимущества. Оценка усилий относительная: малая — один ограниченный сценарий, средняя — несколько состояний/платформ, высокая — новые данные или интеграции. Проверять идеи на реальных пользователях, а не принимать всю таблицу как roadmap.

| Идея | Польза | Усилие / этап | Связь с API |
|---|---|---|---|
| «Проверено по источнику…» + ссылка | Пользователь видит происхождение и свежесть данных | Малое, B1/M2 | Использует существующий `lastVerifiedAt` и source |
| Отдельные «Заявки до» и «Начало» | Уменьшает риск пропустить регистрацию | Малое, M1/M2 | Уже предусмотренные даты/precision |
| Пресеты «Онлайн», «Казахстан», «Открыта регистрация» | Быстрее получить полезную выдачу | Малое, M2 | Комбинации существующих фильтров; online не означает допуск для всех |
| Компактный блок «Кто может участвовать» | Видны ограничения возраста/страны/команды | Среднее, M2 | Существующие eligibility/team rules; неизвестное показывать словами |
| Чек-лист требований | Перед переходом пользователь понимает, что подготовить | Среднее, M2 | Отображение существующих submission requirements/stages; персональные галочки локально |
| Сохранённые карточки и детали offline | Можно вернуться к событию без сети | Среднее, M2/M3 | Локальные snapshots со свежестью, контракт менять не требуется |
| Два типа отметок в календаре: дедлайн и событие | Срок заявки не теряется среди дат проведения | Среднее, M2 | Локальное представление уже известных дат |
| Выбор времени собственного напоминания | Полезно даже при DATE без времени | Среднее, M3 | Локальные системные notifications |
| После браузера: добровольное «Я подал(а) заявку» | Помогает помнить собственные действия | Малое, M3 | Существующая личная отметка; без автоматического подтверждения |
| «Данные события изменились» для saved | Пользователь замечает отмену/перенос | Среднее, после M3 | Простое сравнение локального snapshot возможно; надёжная история/push потребуют отдельного контракта |
| Приоритетная очередь сверки в админке | Модератор проверяет самые важные записи первыми | Среднее, B3 | Внутренняя логика по датам/reports/lastVerifiedAt |
| Предложение от организатора с подтверждающими ссылками | Появляется управляемый канал наполнения | Среднее, B3 | Текущий suggestions подходит для простого случая; верификация организатора — отдельная будущая задача |
| RU/KZ/EN интерфейс | Более широкий местный охват | Среднее, после русского MVP | Строковые ресурсы готовить сейчас; локализация контента/API обсуждается отдельно |
| Объяснимое соответствие интересам | Понятно, почему событие может быть полезно | Среднее, после MVP | Совпадение tags можно показать локально; новое ранжирование/endpoint требует согласования |
| Помощник редактора для черновика из предоставленного текста | Меньше ручного переноса описаний | Высокое, только после работающей модерации | Будущее отдельное исследование; человек подтверждает факты, никакой автоматической публикации |

Для первой беты я бы выбрал пять акцентов: **достоверность источника, две разные даты, быстрые фильтры, сохранённое offline и честное возвращение с сайта организатора**. Они поддерживают текущий продукт и дизайн.

### Что можно измерять

Сначала короткие наблюдения за пользователями: нашли ли подходящее событие, поняли ли условия, различили ли дедлайн и начало. Затем минимальные продуктовые показатели: время до полезной карточки, доля пустых поисков, переходы к официальному сайту, возвращение к saved, доля свежепроверенных событий. Переход по ссылке не считать поданной заявкой.

Отдельную analytics-платформу пока не предлагаю. До сбора телеметрии определить необходимые события, объём и сроки хранения; не отправлять email, токены, полные профили и поисковые строки без обоснования.

## 9. Что облегчит работу двух разработчиков

**Один набор сценариев на обе стороны.** Помимо шести Starter fixtures нужны test cases: DATE/MINUTE, неизвестный приз, отменённое событие, закрытая регистрация, длинное русское название, пустой результат, 401, 429, offline, повторный saved PUT/DELETE, logout и смена аккаунта. Новые общие fixtures — отдельная согласованная правка; локальные тесты не должны менять канонические примеры молча.

**Чёткие адаптеры вместо переписывания экранов.** Один repository interface, отдельные DEMO и REMOTE реализации; одинаковые UI states. Переключение режима явно задаётся конфигурацией. Так напарник может завершить M1, пока сервер проходит B0/B1.

**Ограниченные задачи агентам.** Backend-агент — сервисы/DTO; инфраструктурный — wrapper/CI/окружение; QA — отдельные tests и review контракта. Координатор назначает непересекающиеся файлы, контролирует общие миграции и проверяет итог. Исследовательские подзадачи хорошо параллелятся; одновременная правка одного `pom.xml` или API замедлит команду.

**Короткая передача этапа.** Ветка/commit, что работает, точная команда, endpoints/fixtures, известные ограничения, реальные результаты тестов. Статусы backend/mobile раздельные. Общие ошибки и вопросы по контракту обсуждать до реализации несовместимого поведения.

**Небольшое число независимых инструментов.** Один механизм тестовой PostgreSQL; один основной API client; один dependency bot при согласовании; один navigation framework; одна offline DB при необходимости.

## 10. Что отложить

| Технология / направление | Почему сейчас не помогает достаточно | Сигнал вернуться к рассмотрению |
|---|---|---|
| Kubernetes, микросервисы, service mesh | Нет независимых сервисов и эксплуатационной команды | Измеренные потребности масштабирования/изоляции |
| Kafka/RabbitMQ и outbox | Основные операции синхронные, объём ещё неизвестен | Реальные фоновые процессы с требованиями к доставке |
| Redis | PostgreSQL и один instance покрывают первоначальные задачи | Несколько instances, распределённые лимиты или измеренная нагрузка |
| Elasticsearch/Meilisearch | Добавляют индекс, синхронизацию и обслуживание | PostgreSQL не даёт нужного поиска после измерений |
| GraphQL | REST-контракт уже согласован и небольшой | Доказанная неудобная модель запросов и совместный пересмотр API |
| Firebase/Supabase как замена backend/auth | Меняют утверждённую архитектуру и session semantics | Только отдельное решение о смене архитектуры |
| Полный crash/metrics/logs/traces stack | На раннем этапе некому обслуживать весь набор | Реальные вопросы диагностики, которые не решаются базовыми средствами |
| FCM/APNs/OneSignal push pipeline | Для MVP предусмотрены локальные напоминания | Серверные уведомления об изменениях после согласования |
| Универсальный scraper и внешний агрегатор как единственный источник | Недостаточно гарантии полноты, актуальности, условий участия | Проверенное партнёрство/разрешённые источники и процесс контроля |
| LLM-рекомендации, команды, чат, платежи | Явно вне текущего MVP | Отдельное исследование спроса и решение о расширении продукта |

Эта таблица — оценка применимости сейчас, а не универсальная оценка качества технологий.

## 11. Короткие эксперименты перед внедрением

Это предложенные следующие работы; в исследовании они не запускались.

| Эксперимент | Критерий успеха | Решение по результату |
|---|---|---|
| B0: Java/Boot/PostgreSQL | Wrapper build на Java 21; Flyway на чистой БД и повторно; health; реальные tests | Закрепить версии и команды |
| B1: сериализация контракта | Fixtures и отрицательные случаи DATE/MINUTE/null/decimal-string проходят через реальный MVC mapper | Принять DTO/serializer; исправлять реализацию, не упрощать API |
| M0/M1: три экрана и TypographySpecimen | Реальная сборка доступных targets; нужные glyphs; screenshots по референсу | Выбрать display font и утвердить baseline |
| Общий: OpenAPI codegen | common/Android/iOS compilation плюс сохранение семантики контракта | Принять generator или оставить ручные DTO |
| B1: поиск | Корректная выдача и измеренные query plans на репрезентативных данных | Решить, нужен ли pg_trgm/FTS |
| B2/M3: sessions | Два аккаунта изолированы; logout возвращает 204, последующий защищённый запрос с отозванным токеном — 401; expiry также даёт 401; Bearer не уходит на сторонние origins | Принять security/session реализацию |
| B4: restore drill | Backup восстановлен в новую БД, данные и smoke-сценарии проверены | Зафиксировать процедуру и достижимые RPO/RTO |

Ближайший отдельный шаг по roadmap остаётся **B0**; у напарника — **M0/M1**. Этот отчёт не является разрешением выполнить все эксперименты одним заходом.

## 12. Версии, лицензии и пределы исследования

Исследование смотрит на состояние источников 20.10.2026. Примеры наблюдаемых upstream releases: Spring Boot 4.1.1, Testcontainers 2.0.5, Compose Multiplatform 1.12.2, Kotlin 2.4.21, Room 3.0.3. Это **не утверждённый lockfile**. У репозиториев и документации могут различаться `latest`, stable-линии и примеры с RC/alpha. Например, актуальная документация Room KMP содержит alpha-пример, а release feed Micrometer — milestone. Такие версии не принимаются автоматически.

Прочитанные первичные источники для этих наблюдений: [Boot releases](https://github.com/spring-projects/spring-boot/releases), [Testcontainers releases](https://github.com/testcontainers/testcontainers-java/releases), [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html), [Kotlin compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [Room 3 releases](https://developer.android.com/jetpack/androidx/releases/room3).

Лицензии в таблицах относятся к обозначенным upstream-проектам/редакциям. Перед заимствованием кода нужно закрепить release/commit и сохранить обязательные notices; зависимости, шрифты, изображения и SaaS-условия проверяются отдельно. Публичный GitHub без лицензии не даёт неограниченного права повторного использования. MIT для site code не означает автоматически такую же лицензию всех исходных объявлений. Лицензия самого HackAtlas этим отчётом не выбирается. [GitHub licensing](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository).

Что выполнено: чтение проекта, сравнение с актуальным main, параллельное исследование backend/mobile/tooling, проверка официальных источников и подготовка рекомендаций. Для шрифтов выполнена проверка glyph mappings в памяти; это не визуальная приёмка.

Что не выполнено: установка кандидатов, compilation приложения, device/UI tests, performance benchmarks, codegen spike, оценка уязвимостей каждой версии, нагрузочные проверки, проверка текущих тарифов аккаунта/runner, создание платных ресурсов. Внешние ссылки проверены в ходе исследования выборочно через документацию и upstream; не проводился автоматический аудит доступности каждого URL. Старое окружение из G0 не переаттестовано.

## 13. Передача напарнику

> Исследование готово. Стек, Starter API, opaque sessions и дизайн сохраняются. Для M0/M1 полезны Kotlin/KMP-App-Template и KotlinConf app; сначала stable toolchain, typography specimen и три экрана на общих fixtures. Навигацию, codegen и offline DB выбирать после маленьких проверок. Backend остаётся до B0, работающего base URL пока нет. Новые зависимости и API этим исследованием не внедрены.
