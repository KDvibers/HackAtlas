# HackAtlas M2: серверный каталог — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подключить существующий мобильный UI к трём публичным операциям B1 и сохранять полученные события для просмотра после перезапуска без сети.

  **Architecture:** Один общий асинхронный `CatalogRepository`, адаптер существующего DEMO repository и отдельная HTTP-реализация. StateFlow-состояния каталога и подробностей управляют запросами вне Compose; существующее атомарное гостевое хранилище сохраняется и получает явное происхождение данных. Платформенные hosts выбирают источник один раз на процесс и владеют HTTP-клиентом.

**Tech Stack:** Существующие Kotlin 2.2.21, Compose Multiplatform 1.9.3, AGP 8.11.1, Gradle 8.13, coroutines 1.10.2, serialization 1.9.0, datetime 0.6.2; Ktor Client с OkHttp/Darwin и MockEngine для тестов. JDK 21, Android minSdk 26 / compileSdk 36. Версию Ktor утвердить проверкой совместимости в задаче 1; версии существующего стека автоматически не обновлять.

**Spec:** [ROADMAP: M2](../../ROADMAP.md#m2--основные-сценарии), [PROJECT](../../PROJECT.md), [TECHNOLOGY_DECISIONS](../../TECHNOLOGY_DECISIONS.md), [Starter API](../../../api/README.md), [OpenAPI](../../../api/openapi.yaml), [STYLE_LOCK](../../STYLE_LOCK.md), [текущее состояние mobile](../../STATUS_MOBILE.md), [семантика B1](../../../backend/CATALOG.md). Этот план покрывает только REMOTE-интеграцию внутри M2; весь M2 не объявляется завершённым.

**Статус исполнения:** Tasks 1–3 завершены и закоммичены пользователем (`f5f025a`, `35a1168`, `0a8ee69`). По отдельному поручению 26 октября завершена Task 4: 126 unit tests, Android build/lint/release check, 6 offline и 7 финальных REMOTE mock smoke групп PASS; визуальная проверка выполнена. Единственное Important P2 независимого ревью — потеря feedback при пересоздании host — исправлено через process-owned BookmarkStore, подтверждено тестами и Android recreation smoke. Отложенных Minor нет; iOS/live B1 не проверены. Изменения Task 4 оставлены для пользовательского commit/push. Task 5 не начата и не разрешается автоматически этим планом. Ветка `feat/mobile-catalog-api` от main `b623065`.

## Что сделать пользователю сейчас

1. Получить от backend-разработчика base URL **с `/api/v1`**, доступный с машины/эмулятора/телефона, команды запуска и ID доступного опубликованного события. Уточнить, включён ли DEMO seed. Не отправлять пароли БД или файлы `.env`.
2. Выбрать место запуска API: на своём компьютере с Docker либо доступный сервер друга. `localhost` телефона/эмулятора не означает компьютер друга. Публичный deployment этим планом не разрешается.
3. Рассмотреть этот план и поручить реализацию только этого PR. Работа над HTTP-клиентом и тестами может начаться без живого сервера; финальная интеграционная приёмка требует доступного API.
4. После отчёта самостоятельно выполнить commit/push и создать новый PR. Старый PR #4 уже закрыт merge.

Текст для самостоятельной отправки другу:

> Начинаем интеграцию мобильного M2 с B1. Пришли base URL с /api/v1, который доступен с моего компьютера/телефона, точные команды запуска и ID опубликованного события. Уточни, включён ли DEMO seed и нужен ли фиксированный demo clock. Нужны рабочие GET /hackathons, /hackathons/{id}, /metadata/filters. Авторизация пока не требуется.

## Global Constraints

- «Единственная спецификация — Starter: `api/openapi.yaml` и `api/README.md`». API, fixtures, backend и backend CI в этом PR не менять.
- «Клиент: Kotlin Multiplatform + Compose Multiplatform, Android и iOS». Общие экраны и логика остаются Kotlin; Swift только host.
- «Главный визуальный источник — приложенное изображение». Сохранять pixel headings, цвета, ресурсы, четыре вкладки и принятый M1 baseline. Дополнять согласованными компонентами, не проводить редизайн.
- «Сетевая ошибка не включает DEMO». Источник выбирается явно, `isDemo` берётся из ответа; наличие HTTP не делает события реальными.
- `page=0`, `size=20`, допустимый `size=1..50`; фильтры AND, дефолтная серверная сортировка `DEADLINE_ASC`. Не переопределять серверные статусы и порядок результатов локально.
- `DATE` не получает искусственное время; decimal-string, null, `lastVerifiedAt`, `updatedAt` и `savedAt` сохраняют разные значения. Неизвестный enum даёт существующий UNSUPPORTED/warning, а не crash.
- «Release не должен содержать включённый demo-режим или dev-http». Нет TLS bypass, глобального permissive trust manager или HTTP в release. Без заданного release HTTPS URL показывать честное состояние настройки, не подключать фиктивный сервер.
- Auth, `/me`, серверные saved, JWT/refresh, guest import в аккаунт, browser/share, reminders, remote images, полный offline-каталог и новый deployment исключены. Browser/share — следующий отдельный PR M2; M3 — после M2 и B2.
- Существующие гостевые файлы не стирать, не reseed при ошибке/неизвестной версии. Успех сохранения показывать только после записи на диск.
- Commit, push, создание PR и merge выполняет пользователь. Шаги ниже готовят результат и команды, но не разрешают агенту коммитить. Из status-файлов менять только `docs/STATUS_MOBILE.md`.

## Review Focus

1. Медленный ответ старого поиска приходит после нового — он не должен заменить актуальный список (задача 2).
2. Холодный запуск без сети и сбой metadata — сохранённые детали/календарь остаются доступны, ошибка metadata не блокирует базовый каталог (задачи 2–4).
3. Одинаковые ID у встроенного DEMO, серверного DEMO, реального события и другого API — закладки не смешиваются и удаляется именно выбранная копия (задачи 3–4).
4. Сохранение с карточки требует отдельного detail-запроса; 404, timeout, смена `isDemo` или ошибка записи не создают ложную закладку (задача 4).
5. Новый запрос во время пагинации, пустая последняя страница и повторный ID — нет смешения результатов, бесконечной догрузки и дублей (задача 2).

## Карта изменений и общие интерфейсы

Все пути ниже — от корня репозитория. Существующий `domain/Repositories.kt` и синхронный `DemoRepository` сохраняются для текущих DEMO-тестов; новый адаптер переводит их в асинхронный интерфейс.

- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/domain/CatalogRepository.kt` — новый интерфейс:
  `suspend fun catalog(query: CatalogQuery): CatalogPage`, `suspend fun detail(id: String): HackathonDetail`, `suspend fun filters(): FilterMetadata`.
- `.../domain/CatalogSource.kt` — `CatalogSource.BundledDemo` и `CatalogSource.Remote(baseUrl: String, allowDebugHttp: Boolean)`; нормализованный URL с `/api/v1/`, без userinfo/query/fragment. HTTP разрешается только явно в debug для заданного dev-host.
- `.../domain/CatalogError.kt` — `CatalogError.Network`, `Timeout`, `NotFound`, `Http(status: Int, requestId: String?)`, `InvalidResponse`; `CatalogException(val error: CatalogError)`. Отмена coroutine распространяется отдельно, не превращается в ошибку UI.
- `.../data/network/ApiClient.kt` — `fun createApiClient(engine: HttpClientEngine, source: CatalogSource.Remote): HttpClient`.
- `.../data/RemoteCatalogRepository.kt` — `RemoteCatalogRepository(client: HttpClient, source: CatalogSource.Remote) : CatalogRepository`; использует существующие DTO/mappers.
- `.../data/DemoCatalogRepository.kt` — `DemoCatalogRepository(delegate: DemoRepository) : CatalogRepository`; никаких сетевых запросов.
- `.../ui/CatalogStore.kt` — `CatalogStore(repository: CatalogRepository, scope: CoroutineScope)`, `state: StateFlow<CatalogUiState>`, `setQuery(query: CatalogQuery)`, `loadNext()`, `retry()`, `retryMetadata()`.
- `.../ui/CatalogUiState.kt` — query, items, nullable metadata, `nextPage: Int?`, nullable asOf, loading/loadingMore, отдельные ошибки первого запроса/догрузки/metadata. Пустой успешный список отличается от ошибки и начальной загрузки.
- `.../ui/DetailStore.kt` — `DetailStore(repository: CatalogRepository, scope: CoroutineScope)`, `state: StateFlow<DetailUiState>`, `open(id: String)`, `retry()`, `showSnapshot(saved: SavedGuestEvent)`; состояния Loading/Content/Error. `showSnapshot` не вызывает HTTP.
- `.../domain/GuestStoreScope.kt` — `GuestStoreScope.BundledDemo`, `GuestStoreScope.Remote(canonicalBaseUrl: String)` и `SavedEventKey(id: String, isDemo: Boolean)`. В рамках одного source store ключ включает `isDemo`; source+origin задаёт сам store.
- `.../data/GuestSavedRepository.kt` — `open(storage, scope: GuestStoreScope, seed, now)`, `remove(key: SavedEventKey)`, существующие `save(detail)`/`toggle(detail)` и derived `SavedGuestEvent.key`. Данные snapshots сохраняют исходный `isDemo`.
- `.../data/StorageNamespace.kt` — `expect fun storageNamespaceKey(canonicalBaseUrl: String): String`, lowercase SHA-256 от UTF-8 нормализованного URL; android/ios actual используют платформенный digest, без нового framework.
- `.../ui/AppState.kt`, `App.kt` и platform hosts — связывают источник, repository/stores и хранилище; HTTP никогда не запускается непосредственно из тела composable.

В обозначениях `.../` выше и далее базовый путь — `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/`. Новые имена указаны явно; существующие классы не переименовывать ради косметики.

## Перед выполнением

- [x] Прочитаны AGENTS, спецификации и план, проверены status/main/SDK. Создана отдельная рабочая копия `feat/mobile-catalog-api` от main `b623065`; локальные план/статус перенесены, исходные изменения сохранены без stash.
- [ ] Зафиксировать доступный для тестового устройства API URL. Если URL ещё нет, пометить только финальный live smoke зависимым; задачи с MockEngine выполнять независимо.
- [x] Получены отдельные поручения Tasks 1–4. Task 5 остаётся планом и не начинается автоматически.

## Task 1: проверяемый HTTP repository

**Files:** создать `domain/CatalogRepository.kt`, `CatalogSource.kt`, `CatalogError.kt`, `data/network/ApiClient.kt`, `data/RemoteCatalogRepository.kt`, `data/DemoCatalogRepository.kt`; изменить `mobile/gradle/libs.versions.toml`, `mobile/shared/build.gradle.kts`, `mobile/TOOLCHAIN.md`; тест `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/RemoteCatalogRepositoryTest.kt`.

**Interfaces:** потребляет `CatalogQuery`, DTO, `ContractJson`, существующие `.toDomain()`; производит `CatalogRepository`, `CatalogSource`, `CatalogException` и фабрику клиента из карты выше.

- [x] Добавить минимальные тестовые зависимости Ktor core/content-negotiation/serialization-kotlinx-json и MockEngine, одну точную версию всех модулей. Первый кандидат совместимости — 3.3.3: его upstream catalog использует Kotlin 2.2.21/coroutines 1.10.2/serialization 1.9.0, как проект. Это не утверждение проверенной совместимости: проверить release notes/доступные исправления, затем сборку на существующем стеке и записать фактически принятую версию. Не переносить AGP/Kotlin версии из upstream Ktor автоматически.
- [x] Написать `encodesCanonicalQuery` с MockEngine: `CatalogQuery(query=" AI & ML ", format=ONLINE, countryCode="KZ", sort=DEADLINE_ASC, page=0,size=20)` создаёт ровно `/api/v1/hackathons`, query-параметры `query="AI & ML"`, `format=ONLINE`, `countryCode=KZ`, `sort=DEADLINE_ASC`, `page=0`, `size=20`; null-параметры отсутствуют, Authorization отсутствует. UUID details и metadata идут по двум другим контрактным путям; не теряется `/api/v1` при сборке URL.
- [x] Добавить `decodesCanonicalFixtures`, `mapsProblemAnd404`, `rejectsInvalidPayloadWithoutDemoFallback`, `preservesCancellation`. Проверять все поля страницы, пустую страницу, `isDemo=true/false`, неизвестный enum, nullable даты и деньги строкой; ошибки 400/404/500, timeout/transport failure, не-JSON вместо 200. Использовать неизменённые CanonicalFixtures; варианты создавать в тесте, не править API-примеры.
- [x] Из `mobile` выполнить `./gradlew.bat :shared:testDebugUnitTest --tests "*RemoteCatalogRepositoryTest" --console=plain`. Сначала ожидается FAIL из-за отсутствующей реализации, затем реализовать ровно заданные интерфейсы и повторить до PASS.
- [x] Настроить общий client с ContractJson, request timeout 15 с / connect 10 с / socket 15 с, без скрытого DEMO fallback, без бесконечных повторов. Отключить автоматические redirects API, чтобы запрос не уходил на другой host; 3xx обрабатывать как HTTP error. В задаче 4 platform factories передадут OkHttp/Darwin.
- [x] В том же наборе тестов проверить DemoCatalogRepository на существующих fixtures; 404 соответствует `CatalogError.NotFound`. Обновить TOOLCHAIN только фактически проверенными зависимостями; подготовить дифф для review, не выполнять commit.

## Task 2: каталог и подробности без гонок запросов

**Files:** создать `ui/CatalogStore.kt`, `CatalogUiState.kt`, `DetailStore.kt`, `DetailUiState.kt`; тесты `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/ui/CatalogStoreTest.kt`, `DetailStoreTest.kt`.

**Interfaces:** потребляет `CatalogRepository`/`CatalogException` задачи 1; производит stores и состояния из карты интерфейсов для App.kt. Сетевые DTO в UI не передавать.

- [x] Написать `lateSearchCannotReplaceNewResults`: fake repository задерживает A, возвращает B, затем возвращает A даже после запроса отмены; итоговые query/items относятся к B. Текст поиска debounce 300 мс; фильтр/сортировка/сброс применяются сразу и возвращают `page=0`. Использовать `runTest` и виртуальное время.
- [x] Написать `pagingIsBoundToQuery`: во время page=1 сменить фильтр, получить старую page=1; она не дописывается. Повторный вызов `loadNext` во время загрузки не создаёт второй запрос; одинаковые ID не дублируются. `hasNext=false` останавливает пагинацию; пустая страница не вызывает бесконечный цикл, даже при противоречивом `hasNext=true` — показывать InvalidResponse, сохранить уже загруженный список.
- [x] Написать `metadataFailureDoesNotHideCatalog`, `initialFailureIsNotEmptySuccess`, `appendFailureKeepsItemsAndRetriesSamePage`; metadata имеет собственный retry, базовые фильтры работают без него. Ошибка сети — доступна кнопка повторить; не заявлять «интернет выключен» только по любому IOException.
- [x] Написать `detail404IsNotDemo`, `openingNewDetailRejectsLatePreviousResponse`, `snapshotNeedsNoNetwork`. Для snapshot не вызывается repository; у сетевых details старый результат не заменяет новое событие.
- [x] Запустить `./gradlew.bat :shared:testDebugUnitTest --tests "*CatalogStoreTest" --tests "*DetailStoreTest" --console=plain`; подтвердить FAIL, реализовать stores с поколением запроса/отменой и запустить до PASS. Сохранять серверный порядок и asOf, не применять повторную локальную фильтрацию.
- [x] Подготовить проверяемый дифф задачи; commit выполняет пользователь при выбранной им группировке изменений.

## Task 3: REMOTE snapshots и происхождение данных

**Files:** создать `domain/GuestStoreScope.kt`, `data/StorageNamespace.kt`, actual-файлы `mobile/shared/src/androidMain/kotlin/dev/hackatlas/mobile/data/StorageNamespace.android.kt`, `mobile/shared/src/iosMain/kotlin/dev/hackatlas/mobile/data/StorageNamespace.ios.kt`; изменить `data/GuestSavedRepository.kt`, оба `AndroidGuestSnapshotStorage.kt` / `IosGuestSnapshotStorage.kt`; тесты `GuestSavedRepositoryTest.kt`, новый `GuestStoreScopeTest.kt` в commonTest/data и дополнить androidUnitTest/data/AtomicGuestFileStorageTest.kt при необходимости.

**Interfaces:** потребляет нормализованный base URL из задачи 1; производит `GuestStoreScope`, `SavedEventKey`, `SavedGuestEvent.key`, key-aware remove/save для задачи 4.

- [x] Написать `legacyBundledDemoStillOpens`: старый `demo-v1.json`, version=1, scope=`demo-guest` читается без переписывания при открытии. Сохранить все существующие тесты атомарности, неизвестной версии, порчи файла, идемпотентности и отмены.
- [x] Написать `serverDemoAndRealAreSeparate`: REMOTE store принимает оба `isDemo`, сохраняет одинаковый id с true/false как две разные копии, `remove(SavedEventKey(id,true))` оставляет false. Встроенный DEMO по-прежнему принимает только `isDemo=true`; прежний тест запрета любого server DEMO заменить тестами происхождения, не удалить защиту от смешения.
- [x] Написать `originsCannotReadOrOverwriteEachOther`: разные scheme/host/port/base path дают разные имена файлов и разные ожидаемые scope; несоответствие scope не перезаписывает данные. Нормализация завершающего `/` и default port даёт одну identity. Файл никогда не называется сырым URL.
- [x] Зафиксировать формат: сохранить поля GuestDocument и version=1; bundled scope=`demo-guest`, REMOTE scope=`remote-guest@<canonicalBaseUrl>`. В remote entries уникальны пары `(isDemo,id)`, не только id. Файл REMOTE: `remote-<sha256 canonicalBaseUrl>-v1.json`; старый bundled файл остаётся на месте. Незнакомые/старые иные remote-файлы не удалять и не импортировать молча.
- [x] Запустить целевые tests до реализации (FAIL), затем реализовать новые scope/key и platform filenames с прежней атомарной записью. Команда: `./gradlew.bat :shared:testDebugUnitTest --tests "*GuestSavedRepositoryTest" --tests "*GuestStoreScopeTest" --tests "*AtomicGuestFileStorageTest" --console=plain`; ожидается PASS. iOS digest/файловую часть на Windows пометить непроверенной до Mac.
- [x] REMOTE seed всегда пустой. Source фиксирован на процесс; изменение настроек источника требует нового AppState/перезапуска, не меняет scope открытого repository. Не вводить аккаунты или серверные saved endpoints.

## Task 4: связать источники, экраны и platform hosts

**Files:** изменить `ui/AppState.kt`, `App.kt`, `ui/CatalogueScreen.kt`, `DetailScreen.kt`, `ProfileScreen.kt` (там же SavedScreen), `CalendarScreen.kt`, `domain/SavedCalendar.kt` только для callback/key при необходимости, `shared/src/commonMain/composeResources/values/strings.xml`; Android `HackAtlasApplication.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `mobile/androidApp/build.gradle.kts`; создать debug manifest/network security config при использовании локального HTTP. iOS: `MainViewController.kt`, `mobile/iosApp/iosApp/HackAtlasApp.swift`, `Info.plist`, `project.pbxproj` при необходимости. Создать android/ios `data/network/PlatformHttpEngine` factories. Тесты: расширить `OfflineBootstrapTest.kt`, создать `RemoteSaveCoordinatorTest.kt`; класс `ui/RemoteSaveCoordinator.kt` для сохранения сетевой карточки.

**Interfaces:** AppState принимает явный `CatalogSource`, CatalogRepository и GuestSavedRepository factories; stores задач 1–3 живут у владельца процесса. `RemoteSaveCoordinator(repository, saved)` предоставляет `suspend fun save(summary: Hackathon): Unit`, получает полный detail, проверяет id/isDemo, затем сохраняет. Unsave использует существующий snapshot key и не требует HTTP.

- [x] Написать `coldStartSavedWorksWhileCatalogNeverReturns`: сохранённые вкладка/детали/календарь становятся доступны до завершения сетевых вызовов. Сохранить исходные OfflineBootstrapTest для отказа bundled-каталога и ошибок хранилища; unknown/corrupt file не скрывать успешным пустым состоянием.
- [x] Написать `failedDetailOrWriteDoesNotMarkSaved`, `saveUsesFullDetailAndPreservesDates`, `classificationChangeDoesNotSaveWrongEvent`. 404, transport error, несовпадение id/isDemo между карточкой и detail, ошибка записи оставляют предыдущий disk/StateFlow неизменными. Уже сохранённое удаляется офлайн; двойной save идемпотентен.
- [x] Запустить эти тесты до реализации (FAIL), связать AppState/coordinator и повторить до PASS: `./gradlew.bat :shared:testDebugUnitTest --tests "*OfflineBootstrapTest" --tests "*RemoteSaveCoordinatorTest" --console=plain`.
- [x] Hosts создают один клиент/store scope на процесс. Android — OkHttp, iOS — Darwin. `DEBUG` больше не означает обязательно DEMO и не блокирует весь REMOTE UI: debug выбирает explicit source; release разрешает только заданный HTTPS remote, иначе показывает configuration-unavailable. Существующий debug `savedOnly` сохраняется для offline smoke.
- [x] Добавить Android INTERNET. Если нужен HTTP dev URL, ограничить cleartext его host в debug config; release не получает это исключение. iOS ATS exceptions допускаются только для согласованного dev host в Debug; Release остаётся HTTPS. Local base URL вводится локальной build configuration, не захардкожен в общем UI и не коммитится как production URL.
- [x] Существующие четыре быстрых фильтра сохранить; добавить «Казахстан» как countryCode=KZ, сортировку и дополнительные metadata-фильтры в компактный лист с теми же chips/цветами, без изменения карточек и типографики. «Онлайн», страна и «Регистрация открыта» комбинируются AND. В список добавить loading/error/retry и дозагрузку; успешную пустую выдачу показывать отдельно.
- [x] REMOTE profile показывает гостевое состояние без загрузки Profile fixture. DEMO badge и текст источника зависят от source/isDemo: реальные данные не называются Starter demo, серверное DEMO явно подписано. Не менять isDemo при отображении/сохранении. Источник и lastVerifiedAt показываются отдельно от даты копии.
- [x] Провести keys через saved/calendar/detail navigation: источник + origin + isDemo + id + local/network view. Для `SaveableStateProvider` использовать стабильную String, поддерживаемую Android saved-state, а не произвольный Kotlin data class. Это также исправляет перенос scroll position между разными detail-экранами. Возврат к каталогу сохраняет поиск/фильтры/позицию; календарь — выбранный месяц/день. Открытая копия остаётся читаемой после удаления закладки.
- [x] Browser/share не реализовывать в этой задаче. Сохранить место CTA/иконок, честно объяснять недоступность в текущем этапе; не использовать для настоящего серверного события сообщение «сервера пока нет». Не создавать application mark по клику.
- [x] Выполнить `./gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --console=plain`; ожидается exit 0. Проверить merged release manifest: нет разрешённого cleartext, demo source и fixture assets. При доступном Mac выполнить существующую xcodebuild-команду из mobile README; иначе явно сохранить ограничение.

## Task 5: приёмка с B1 и передача PR

**Files:** создать `mobile/scripts/android_remote_smoke.py`, `mobile/artifacts/m2-remote/review.html` и `screenshots/` после реальных проверок; обновить `mobile/README.md`, `mobile/TOOLCHAIN.md`, `docs/STATUS_MOBILE.md`. M1 и прежние M2 artifacts не перезаписывать.

**Interfaces:** потребляет debug REMOTE build с явным API URL и готовые сценарии задач 1–4. Smoke принимает `--adb`, `--serial`, `--api-base-url`, `--event-id`; отсутствие live API — блокер live smoke, а не повод объявить MockEngine-проверку интеграционной.

- [ ] По переданным другом командам проверить три GET. При server DEMO включить seed явно; для воспроизводимых старых fixtures использовать предусмотренный backend параметр `--hackatlas.demo.as-of=2026-10-12T10:00:00Z`. Обычный пустой каталог не считать неисправностью клиента и не подменять данными из APK.
- [ ] На Android проверить каталог → поиск/AND-фильтры → следующая страница → подробности → сохранить → force-stop → запуск без сети → saved/detail/calendar → удалить → повторный запуск. Проверить ошибки первого запроса, догрузки и detail отдельно; данные тестового эмулятора и настройки сети восстанавливать.
- [ ] Добавить сценарий detail A → прокрутка → назад → detail B: B начинается сверху, а возврат к каталогу/календарю сохраняет их собственное состояние. Сделать реальные screenshots REMOTE и offline, в том числе 360 dp и text scale 130%; сравнить структуру с утверждённым M1.
- [ ] Выполнить общие build/test/lint/release-fixture задачи из задачи 4 и `scripts/validate_contract.py` в окружении с `scripts/requirements-contract.txt`. Проверить API blob IDs относительно базы ветки и `git diff --check`; несовпадения API не «чинить» на одной стороне.
- [ ] В STATUS_MOBILE записать точные команды/результаты, source/API URL тип окружения без секретов, известные ограничения, screenshot paths и сообщение напарнику. Не обещать iOS, CI или backend verify без выполненного запуска. Блокирующие результаты review исправить до передачи.
- [ ] Дать пользователю команды `git add` только реально изменённых mobile/документов, `git commit -m "feat(mobile): connect public catalog API"`, `git push -u origin feat/mobile-catalog-api` и ссылку сравнения для нового PR. Выполняет пользователь. Не включать auth или следующий PR browser/share в это изменение.

## Граница готовности

Приёмка этого PR: серверный каталог/поиск/фильтры/пагинация и details работают в утверждённом UI; сохранённые серверные копии доступны после перезапуска без сети; источники и DEMO классификация не смешиваются; проверки и ограничения задокументированы. Browser/share ещё остаются отдельной частью M2. M3 не начинается до принятого M2 и проверенного B2.

## Источники технических решений

- [Ktor engines](https://ktor.io/docs/client-engines.html): отдельные OkHttp и Darwin engines; здесь используются уже выбранные проектом engines, без автоматической смены стека.
- [Ktor MockEngine](https://ktor.io/docs/client-testing.html): один client configuration для тестового и реального engine.
- [Ktor JSON content negotiation](https://ktor.io/docs/client-serialization.html): общий JSON serializer; DTO остаются Starter DTO.
- [Upstream catalog Ktor 3.3.3](https://raw.githubusercontent.com/ktorio/ktor/3.3.3/gradle/libs.versions.toml): основание выбрать первый кандидат совместимости, а не доказательство Android/iOS сборки. Проверено при подготовке плана 25 октября 2026.

## Порядок исполнения и отчёт планирования

Рекомендуется последовательная реализация одним исполнителем с независимым итоговым review: задачи 2–4 зависят от общих интерфейсов, поэтому одновременное редактирование AppState/App.kt разными агентами не ускорит интеграцию. Альтернатива — отдельный агент и review на каждую задачу после фиксации интерфейсов. Пользователь выбирает способ перед началом реализации.

На этапе планирования прочитаны текущие исходники/контракт/дорожная карта, отдельно исследованы platform и provenance ограничения. Выполнена самопроверка покрытия требований, согласованности интерфейсов и пяти Review Focus сценариев. Код и зависимости не изменялись; новые тесты/сборки этого плана ещё не выполнялись. Commit/push не выполнялись.
