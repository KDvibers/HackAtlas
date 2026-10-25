# MOBILE: M0 / M1 и независимая часть M2

## M2 Task 4: stores и источники в Android/iOS — 26 октября 2026

Пользователь поручил Task 4 после своего commit `0a8ee69f75fe9a5f3087917f529a09e47a8e89de` (Task 3). Реализация последовательная в прежней ветке `feat/mobile-catalog-api`; commit, push и PR остаются пользователю. Task 5 с живым B1 этим поручением не запущена.

Изменено:

- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/AppState.kt`, `CatalogRuntime.kt`, `RemoteSaveCoordinator.kt`, `BookmarkStore.kt`: один process owner, scoped storage открывается до запуска сетевых запросов, независимые catalog/detail stores. REMOTE не читает fixtures. Полный detail и ID/isDemo проверяются до записи; ошибки HTTP/записи не меняют сохранённое. Удаление не требует сети. Pending keys и очередь неподтверждённых ошибок живут в process StateFlow, поэтому пересоздание host не теряет обратную связь; каждая ошибка снимается явным подтверждением.
- `.../domain/CatalogConfiguration.kt`, `.../ui/ScreenIdentity.kt`: явный источник из build settings, запрет DEMO/HTTP в release, строковые state keys с source/origin/classification/id/view. Android saved-state не получает custom data class. Старые гостевые файлы и схема Task 3 сохранены.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/App.kt`, `ui/CatalogueScreen.kt`, `DetailScreen.kt`, `ProfileScreen.kt`, `CalendarScreen.kt`, `Components.kt`, `NetworkStates.kt` и `composeResources/values/strings.xml`: состояния загрузки/ошибки/пустой выдачи, retry/дозагрузка, четыре прежних пресета плюс Казахстан, компактный лист сортировки и metadata-фильтров. Пресеты комбинируются AND. Гостевой REMOTE-профиль без mock user; DEMO подпись только по источнику/событию. Источник/проверка/сохранение различаются. Browser/share пока объясняют недоступность, заявок не создают. Шрифты, изображения, палитра и основные карточки сохранены.
- `mobile/shared/src/androidMain/kotlin/dev/hackatlas/mobile/data/network/PlatformHttpEngine.android.kt`, соответствующий iosMain и common expect: OkHttp/Darwin 3.3.3. `mobile/gradle/libs.versions.toml`, `shared/build.gradle.kts` — только эти два engine dependency, существующий стек не обновлялся.
- Android: `mobile/androidApp/src/main/kotlin/dev/hackatlas/mobile/{HackAtlasApplication,MainActivity}.kt`, manifests main/debug, `build.gradle.kts`: INTERNET, immutable source на процесс, отдельные debug/release URL; debug HTTP требует explicit opt-in и XML только для точного host. Release manifest `usesCleartextTraffic=false`, без debug network-security XML.
- iOS: `mobile/shared/src/iosMain/kotlin/dev/hackatlas/mobile/MainViewController.kt`, Swift host, Info.plist, project.pbxproj — build settings `HACKATLAS_SOURCE`/`HACKATLAS_API_BASE_URL`, один runtime и Darwin. ATS исключений нет, REMOTE только HTTPS. Это подготовленный исходный код, не подтверждение сборки iOS.
- Тесты: `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/OfflineBootstrapTest.kt`, `ui/RemoteSaveCoordinatorTest.kt`, `SourceNavigationTest.kt`, `BookmarkStoreTest.kt`; новый emulator-only `mobile/scripts/android_catalog_smoke.py`. Настройка и команды — `mobile/README.md`, версии — `mobile/TOOLCHAIN.md`.

Проверки на Windows/JBR 21/SDK 36:

1. TDD: bootstrap/save tests и config/filter/navigation tests сначала FAIL из-за отсутствующих API, затем PASS: **12 + 4 целевых теста**. При исправлении замечания ревью добавлены ещё 3 RED→GREEN теста смены наблюдателя во время сохранения, успешной записи/офлайн удаления/восстановления и независимого подтверждения нескольких ошибок. Новых тестов относительно Task 3 — 16, исходные offline tests сохранены.
2. Из `mobile`: `./gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --console=plain --offline` — BUILD SUCCESSFUL; **126 tests, 0 failures/errors/skipped**, lint 0 issues, release без fixture JSON. XML/BuildConfig release проверены: REMOTE, пустой URL до локальной настройки, `ALLOW_DEBUG_HTTP=false`, cleartext=false. После исправления ревью повторный полный прогон прошёл (`task-4-review-verification.log`).
3. `scripts/validate_contract.py` — PASS OpenAPI/правила/6 fixtures; `git diff --check` — PASS. API, backend, CI и дизайн-ассеты не изменены.
4. Существующий `android_m2_smoke.py` с отдельным каталогом результатов: **6 PASS**, включая перезапуск, авиарежим/savedOnly, календарь, неизвестную версию без сброса, пустое хранилище без reseed, 360dp/130% text. Файл debug-приложения и настройки эмулятора восстановлены в finally. Первая попытка до установки завершённой сборки не получила UI hierarchy; после установки актуального APK сценарий прошёл.

Финальный REMOTE mock smoke после исправления ревью прошёл **7 групп**: полная копия из detail; AND/metadata retry; пагинация; pending/error при пересоздании Activity; 404 и одинаковый ID с двумя isDemo; холодное сохранённое/детали/календарь без HTTP; отсутствие auth и неизменность bundled файла. Тестовый HTTP/1.0 стенд сначала дал EOF; переведён на HTTP/1.1 с явным Connection: close. После одного непостоянного сетевого сбоя повторный полный прогон прошёл без ослабления проверок. Диагностический println удалён, RemoteCatalogRepository остался без diff. Отдельно добавлен RED→GREEN тест сохранения исходной NEWEST-сортировки DEMO; REMOTE сохраняет DEADLINE_ASC. [Галерея](../mobile/artifacts/m2-catalog/review.html) содержит реальные Android screenshots: три основных экрана, типографику, состояния REMOTE, 360dp/130% text и 430dp. Проверены все 24 ссылки галереи; визуально проверены каталог, подробности и профиль, включая узкий экран. Результат итогового ревью и исправление указаны ниже. Логи — ignored `.superpowers/sdd/2026-10-25-mobile-catalog-api/task-4-*.log`.

Финальный DEMO APK: `mobile/build/task4/HackAtlas-demo-debug.apk`, SHA-256 `8C9D592ADCD1BC1C7D9EF9946D55770A8FCFA908516360E7B3742AF07B7C212B`. APK находится в ignored build-каталоге и не добавляется в Git. [Заголовок и описание PR](../mobile/artifacts/m2-catalog/PR_DESCRIPTION.md) подготовлены для ручной публикации.

Итоговое независимое read-only ревью: 1 Important P2 — потеря pending/error feedback при пересоздании Activity во время сохранения; Critical/Minor нет. Замечание подтверждено по владению состоянием и исправлено в одном проходе через BookmarkStore; новые тесты и полная проверка 126 тестов прошли. Второе независимое ревью не выполнялось. Отложенных мелких замечаний нет. Ограничения reviewer по Native/live B1, исключённым функциям, автообновлению каталога, расширенной эквивалентности URL и полной валидации схемы приняты и зафиксированы в ledger; они не считаются проверенными сценариями.

Происхождение проверок и SHA-256 обоих APK записаны в `mobile/artifacts/m2-catalog/verification.json`: исходные 6 offline smoke групп выполнены до исправления lifecycle, финальные 7 REMOTE групп — после него и повторно покрывают затронутые общие/offline сценарии. В первом прогоне после исправления однократное чтение UI после холодного запуска не увидело карточку; причина не установлена. Ограниченное ожидание рендера без сетевого retry и сбор UI при сбое добавлены в smoke-скрипт; полный повтор прошёл. Android event log подтверждает два пересоздания Activity в том же процессе во время ожидания/ошибки. Основные DEMO screenshots обновлены с финального APK.

Ограничения: Mac/Xcode отсутствуют, поэтому Native/Swift/Darwin compile/link и iPhone не проверены. Реальный B1/HTTPS сервер и физический Android не проверены; локальный synthetic HTTP smoke не заменяет Task 5. Auth, серверные saved, guest import, browser/share, reminders и remote images не входят. `.agents/` и `.interface-design/` пользователя не менялись.

Сообщение напарнику для ручной передачи: «Подключили shared stores и REMOTE к мобильным экранам по Starter без изменения API. Для следующей приёмки нужны доступный base URL с /api/v1, команды запуска и ID опубликованного события; уточни DEMO seed. Вход и приватные endpoints пока не подключаем».

Ниже — исторические отчёты Task 1–3 и M0/M1; их прежние ограничения по отсутствию UI-интеграции относятся к тем этапам.


## M2 Task 3: источники REMOTE snapshots — 26 октября 2026

По отдельному поручению пользователя реализована Task 3 [плана M2](superpowers/plans/2026-10-25-mobile-catalog-api.md), последовательно, поверх пользовательского commit `35a11683dc212a8ffdb0802be66bb62b8425aadd` (Task 2), в ветке `feat/mobile-catalog-api`. Commit/push остаются пользователю.

Изменения:

- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/domain/GuestStoreScope.kt`: immutable BundledDemo/Remote, нормализованный API origin и составной SavedEventKey(id, isDemo).
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/StorageNamespace.kt` и actual-файлы `mobile/shared/src/androidMain/kotlin/dev/hackatlas/mobile/data/StorageNamespace.android.kt`, `mobile/shared/src/iosMain/kotlin/dev/hackatlas/mobile/data/StorageNamespace.ios.kt`: имя REMOTE-файла `remote-<sha256 UTF-8 canonicalBaseUrl>-v1.json`. SHA-256 реализован платформенными API, новых зависимостей нет.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/GuestSavedRepository.kt`: document version=1 и поля сохранены; bundled scope=`demo-guest`, REMOTE scope=`remote-guest@<canonicalBaseUrl>`. Сохранение, toggle и удаление различают одинаковые ID с разным isDemo. REMOTE принимает серверное DEMO и реальные события; BundledDemo по-прежнему принимает только isDemo=true. REMOTE seed обязан быть пустым.
- `mobile/shared/src/androidMain/kotlin/dev/hackatlas/mobile/data/AndroidGuestSnapshotStorage.kt`, `mobile/shared/src/iosMain/kotlin/dev/hackatlas/mobile/data/IosGuestSnapshotStorage.kt`: добавлены конструкторы с явным scope; пути прежних Boolean-конструкторов сохранены для текущих hosts. Атомарная запись не менялась. Старые/неизвестные remote-файлы не удаляются и не импортируются в новый origin.
- Тесты `GuestSavedRepositoryTest.kt`, `GuestStoreScopeTest.kt`, `LegacyGuestSnapshot.kt` в `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/` и `mobile/shared/src/androidUnitTest/kotlin/dev/hackatlas/mobile/data/AtomicGuestFileStorageTest.kt`. Legacy JSON получен из прежней реализации до изменения и зафиксирован; тест не генерирует его новым serializer.

Проверки на Windows/JDK 21:

1. До реализации целевые тесты завершились FAIL из-за отсутствующих scope/key/namespacing API. После реализации из `mobile/`: `./gradlew.bat :shared:testDebugUnitTest --tests "*GuestSavedRepositoryTest" --tests "*GuestStoreScopeTest" --tests "*AtomicGuestFileStorageTest" --console=plain --offline` — **31/31 PASS**, exit 0 (21 repository, 6 scope/digest, 4 filesystem).
2. `./gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --console=plain --offline` — **BUILD SUCCESSFUL**, exit 0; **110 тестов, 0 failures/errors/skipped**, lint 0 issues, release fixture check PASS. Число тестов выросло на 17; прежний запрет server DEMO заменён проверкой происхождения и защиты bundled.
3. Из корня: `C:/Users/user/Desktop/HackAtlas/mobile/build/pr4-contract-venv/Scripts/python.exe -X utf8 scripts/validate_contract.py` — PASS: OpenAPI/правила/6 fixtures; `git diff --check` — PASS.

Тесты проверили старый `demo-v1.json` без перезаписи/reseed, пару DEMO/real с одинаковым ID, восстановление после создания нового repository, изоляцию scheme/host/port/base path, одинаковую identity default port/trailing slash, несовпадение document scope без перезаписи, ошибку записи без потери StateFlow/диска и сохранение старых неизвестных файлов. Прежние тесты cancellation, corruption/version и атомарной замены сохранены. Digest проверен на известных пустом/abc/UTF-8 векторах и заданном API URL.

Логи: ignored `.superpowers/sdd/2026-10-25-mobile-catalog-api/task-3-*.log`. APK: `mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk`, SHA-256 `32EF3FD052E150335472C331D03046BE564FDAF614D9D4E99BB74FBEB6028F23`.

Итоговый независимый read-only reviewer проверил рабочий diff относительно `35a1168`, новые файлы, совместимость, происхождение данных, точное удаление и исходники Native digest. Critical/Important/Minor замечаний нет; Task 3 готова к пользовательскому коммиту. Проверены логи/XML 110 тестов и `git diff --check`. Код после общей проверки не менялся, повторная сборка не требовалась. Рабочая копия и логи сохранены; чужие `.agents/` и `.interface-design/` не менялись.

Совместимость: `remove(id)` оставлен только для старого bundled UI; REMOTE требует `SavedEventKey`. Boolean-конструкторы storage остаются для прежних hosts до Task 4. Nonempty REMOTE seed отклоняется до I/O. Scope неизменен в открытом repository; выбор источника на процесс и согласованное связывание storage/repository/экранов — Task 4. HTTP URL здесь допустим как identity dev-хранилища, сетевую политику это не меняет; release HTTPS проверяется при подключении hosts.

iOS код опирается на [binding Kotlin 2.2.21](https://raw.githubusercontent.com/JetBrains/kotlin/v2.2.21/kotlin-native/platformLibs/src/platform/ios/CommonCrypto.def), [CommonDigest Apple](https://raw.githubusercontent.com/apple-oss-distributions/CommonCrypto/main/include/CommonDigest.h) и [правила C interop](https://kotlinlang.org/docs/native-c-interop.html). Mac/Xcode недоступны: Native compile/link, iOS digest/файловое поведение и UIKit host не проверены. Файловые тесты выполнены на JVM во временных папках; UI smoke, live backend и физические устройства не запускались. Реальные пользовательские файлы не мигрировались и не удалялись.

API, backend, зависимости, текущие экраны и дизайн не менялись. Новых screenshots нет. Task 4 не начата; REMOTE пока не включён в приложении.

Сообщение напарнику для ручной передачи: «Готова изоляция локальных копий по адресу API и isDemo. Старое гостевое DEMO читается без перезаписи, контракт не меняли. Дальше подключаем stores/источники к экранам; для живой проверки нужны base URL, запуск и ID опубликованного события».

Границы итогового ревью: Task 4 должен связать один выбранный source, один repository owner, scoped storage и callbacks с SavedEventKey. Холодный REMOTE startup, detail-before-save и успешная запись с точки зрения UI пока не проверены; auth/account/import не входят в этот этап. Одновременная запись несколькими repository/process owners не поддерживается текущей архитектурой. Дополнительные URL-алиасы сверх правил CatalogSource могут создавать отдельные хранилища. Следующий отдельный этап — Task 4, подключение источников/stores/экранов; автоматически он не начинается.

Рекомендуемый заголовок PR: `feat(mobile): isolate guest snapshots by API source`.

Описание для PR: «Реализована Task 3 M2: REMOTE snapshots разделены по нормализованному API URL, ключ записи учитывает id/isDemo. Старый demo-v1.json и формат version=1 совместимы; чужие/неизвестные файлы сохраняются без импорта или сброса. Добавлены scoped storage adapters и SHA-256 namespaces для Android/iOS. 31 целевой / 110 всего тестов прошли, Android build/lint/release fixture check и контракт — PASS; независимое ревью без замечаний. UI/API/зависимости не менялись. Native/iOS и live-интеграция требуют отдельной проверки».

## M2 Task 2: состояния каталога и подробностей — 26 октября 2026

По поручению пользователя реализована только Task 2 [плана M2](superpowers/plans/2026-10-25-mobile-catalog-api.md). Работа последовательно выполнена в существующей ветке `feat/mobile-catalog-api`, поверх пользовательского commit `f5f025ad185dc47a8186d2f1a035676ec42422f6` (Task 1). Commit/push остаются пользователю.

Новые файлы:

- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/CatalogStore.kt`, `CatalogUiState.kt`: состояния загрузки, успешной пустой выдачи, результата и отдельных ошибок первой страницы/догрузки/metadata. Поиск задерживается на 300 мс; фильтры, сортировка и очистка поиска применяются сразу с page=0. Сохраняются порядок сервера и asOf.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/DetailStore.kt`, `DetailUiState.kt`: Idle/Loading/Content/Error, повтор запроса и открытие готового SavedGuestEvent со временем сохранения без HTTP.
- `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/ui/CatalogStoreTest.kt`, `DetailStoreTest.kt`, `StoreTestFixtures.kt`: 25 новых тестов на виртуальном времени с fake CatalogRepository и каноническими fixtures.

Каждый store последовательно обрабатывает команды и результаты через Channel. При смене запроса/деталей увеличивается поколение и отменяется предыдущая работа; даже ответ источника, проигнорировавшего отмену, не заменит новое состояние. Пагинация допускает один запрос, удаляет повторные ID, сохраняет список при ошибке и повторяет ту же страницу только по явному retry. Пустая продолжающаяся страница и страница без новых ID при hasNext=true не вызывают бесконечной дозагрузки. Metadata загружается и повторяется независимо. Scope создаёт и отменяет будущий host; публичные методы ставят команды в очередь.

Проверки на Windows/JDK 21:

1. Целевые тесты сначала не компилировались из-за отсутствующих stores/states. После реализации `./gradlew.bat :shared:testDebugUnitTest --tests "*CatalogStoreTest" --tests "*DetailStoreTest" --console=plain --offline` — **25/25 PASS**, exit 0.
2. Из `mobile/`: `./gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --console=plain --offline` — **BUILD SUCCESSFUL**, exit 0; **93 теста, 0 failures/errors/skipped**, lint 0 issues, release fixture check PASS.
3. Из корня: `C:/Users/user/Desktop/HackAtlas/mobile/build/pr4-contract-venv/Scripts/python.exe -X utf8 scripts/validate_contract.py` — PASS: OpenAPI, проектные правила и 6 fixtures. `git diff --check` — PASS.

Логи — ignored-каталог `.superpowers/sdd/2026-10-25-mobile-catalog-api/`, файлы `task-2-red.log`, `task-2-green.log`, `task-2-verification.log`. APK: `mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk`, SHA-256 `10F10636754811D663AB7A301696F77CC7717B8EA67F0A63E72235BE9E17DD7D`.

Итоговый независимый read-only reviewer проверил рабочий дифф относительно `f5f025a`, все новые файлы, требования и результаты тестов. Critical/Important/Minor замечаний не обнаружено; Task 2 готова к пользовательскому коммиту. Повторный прогон после ревью не требовался: код не менялся.

Границы ревью: подключение Compose/owner scope, lifecycle HTTP-клиента и холодный REMOTE-запуск остаются Task 4; происхождение snapshots и коллизии ID/isDemo — Task 3; detail-before-save и подтверждение записи — Task 4. Stores получают корректные CatalogQuery/ID от будущего UI; они не добавляют полноценный валидатор ввода или серверной схемы и не гарантируют единый серверный snapshot между страницами. Эти ограничения нужно учитывать при интеграции, не считать текущие unit tests приёмкой всего M2. Рабочая копия и ignored-логи сохранены; посторонние `.agents/` и `.interface-design/` не менялись.

API, зависимости, backend, существующие Compose-экраны, ресурсы и гостевые файлы не менялись. Stores ещё не подключены к AppState/экранам: интеграция — Task 4. Поэтому новых screenshots и UI smoke нет. Live B1, iOS/Kotlin Native и поведение на устройствах этой задачей не проверены. Дизайн сохраняется. Task 3 (происхождение REMOTE snapshots) и следующие задачи не начаты.

Сообщение напарнику для ручной передачи: «На мобильной стороне готовы состояния каталога/подробностей, поиск и пагинация с защитой от запоздалых ответов. Контракт не меняли; для живой проверки всё ещё нужны base URL, запуск и ID опубликованного события».

Следующий отдельный этап — Task 3: разделение REMOTE snapshots по происхождению и сохранение совместимости существующих гостевых данных.

Рекомендуемый заголовок PR: `feat(mobile): add race-safe catalog and detail state stores`.

Описание для PR: «Реализована Task 2 M2: StateFlow-состояния каталога/подробностей, debounce поиска 300 мс, немедленные фильтры/сортировка, пагинация и retry с защитой от запоздалых ответов. Metadata загружается независимо, сохранённые подробности открываются без HTTP. Добавлены 25 тестов; всего 93 PASS, Android build/lint/release fixture check и проверка контракта прошли. Независимое ревью без замечаний. UI/API/зависимости не менялись; подключение экранов, REMOTE-хранилище, live HTTP и iOS остаются следующими задачами».

## M2 Task 1: HTTP-клиент и MockEngine — 25 октября 2026

По поручению пользователя выполнена **только задача 1** [плана сетевой части M2](superpowers/plans/2026-10-25-mobile-catalog-api.md). Ветка `feat/mobile-catalog-api` создана от main `b62306583814d6634bad8dddf826529ba850e872` в отдельной рабочей копии `C:/Users/user/.codex/worktrees/mobile-catalog-api/HackAtlas`. Исходная рабочая копия и её изменения сохранены; commit/push/PR выполняет пользователь.

Изменения:

- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/domain/CatalogRepository.kt`, `CatalogSource.kt`, `CatalogError.kt`: общий suspend-интерфейс, явный источник, нормализация base URL и типизированные ошибки.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/RemoteCatalogRepository.kt`, `DemoCatalogRepository.kt`, `data/network/ApiClient.kt`: три публичных GET, Starter DTO/mappers, отдельный DEMO-адаптер; таймауты request/connect/socket 15/10/15 с, автоматические redirects отключены. Ошибка сети не включает DEMO; отмена coroutine распространяется вызывающему коду.
- `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/RemoteCatalogRepositoryTest.kt`, `CatalogSourceTest.kt`, `DemoCatalogRepositoryTest.kt`: 25 новых тестов; маршруты/параметры, JSON/Problem, 400/404/500/redirect, timeout/network/cancellation, nullable значения и точные даты, неизвестные enum в ответах, запрет UNSUPPORTED в запросах, URL и явный DEMO.
- `mobile/gradle/libs.versions.toml`, `mobile/shared/build.gradle.kts`, `mobile/TOOLCHAIN.md`: Ktor 3.3.3; версии существующего стека сохранены.

Проверки с JDK 21 и имеющимся Android SDK:

1. До реализации: целевые тесты не компилировались из-за отсутствующих новых классов. После реализации та же команда `./gradlew.bat :shared:testDebugUnitTest --tests "*RemoteCatalogRepositoryTest" --tests "*CatalogSourceTest" --tests "*DemoCatalogRepositoryTest" --console=plain` — **23/23 PASS**, exit 0.
2. После исправления по ревью из `mobile/`: `./gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --console=plain --offline` — **BUILD SUCCESSFUL**, exit 0; **68 тестов, 0 failures/errors/skipped**. Lint: **0 ошибок и предупреждений**. Release-проверка отсутствия DEMO fixtures — PASS.
3. Из корня: существующий Python venv `C:/Users/user/Desktop/HackAtlas/mobile/build/pr4-contract-venv/Scripts/python.exe -X utf8 scripts/validate_contract.py` — PASS, OpenAPI/правила/6 fixtures. Diff API/backend/infra/design/UI относительно базы пустой.

Логи red/green/общей проверки и ledger находятся в ignored-каталоге `.superpowers/sdd/2026-10-25-mobile-catalog-api/`; итоговый прогон — `final-verification.log`. Debug APK: `mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk`, SHA-256 `3879588143AFE17E90BBE65D5F126C1D1C3442CB7F528BABB718C95B015D7A3F`.

Независимый агент провёл read-only ревью рабочего диффа относительно базы (новых коммитов нет). Найден один P2: локальные enum `UNSUPPORTED` могли отправляться как API-фильтры. Два новых теста сначала упали из-за отсутствия отказа, затем прошли после проверки параметров до HTTP-запроса; каждый подтверждает ноль запросов. Общий набор после исправления — 68/68. Других критических, важных или отложенных мелких замечаний не выявлено. Повторное ревью не запускалось; исправление проверено тестами.

Решения по границам ревью: гонки поиска/пагинации и независимость metadata/offline startup относятся к stores; разделение происхождения snapshots и detail-before-save — к хранилищу/coordinator; engines, lifecycle и запрет dev HTTP в release — к hosts. Эти сценарии ещё нельзя считать проверенными для REMOTE. Использованы существующие DTO/mappers, без полной runtime-валидации каждой JSON-schema; нормализация URL ограничена согласованными host/default-port/trailing-slash и проверкой пути. При дальнейшей интеграции нужны тесты изоляции источников, чтобы адреса-алиасы и одинаковые ID не давали неверные копии. Сохранены рабочая копия и ignored-логи для пользовательского коммита. Посторонние untracked `.agents/` и `.interface-design/` не менялись и не входят в рекомендуемый commit.

**Граница результата:** приложение пока использует прежний DEMO-источник. HTTP-клиент не связан с UI; OkHttp/Darwin factories, stores, REMOTE snapshots и живой B1 smoke относятся к следующим задачам. iOS/Kotlin Native, реальная сеть/TLS, CI, UI smoke этой версии APK и подписанный release не проверялись. Визуал, шрифты и ресурсы не менялись; новых screenshots нет. API не менялся, auth не добавлялся.

Сообщение напарнику для ручной передачи: «Готов HTTP-слой трёх публичных операций Starter с MockEngine-тестами. Авторизация не требуется, API не меняли. Для живой проверки по-прежнему нужны доступный base URL с /api/v1, команды запуска и ID опубликованного события; укажи, включён ли DEMO seed».

Следующий отдельный шаг после этого задания — Task 2: состояния каталога и подробностей, поиск/пагинация без гонок запросов. Он не начат; весь M2 не объявляется завершённым.

## После merge PR #4: план сетевой части M2 — 25 октября 2026

GitHub подтвердил merge PR #4 в main: `b62306583814d6634bad8dddf826529ba850e872`; локальный пользовательский merge-коммит — `4201df8`. Сообщения о незавершённом merge в отчётах ниже относятся к предыдущим проверкам и больше не описывают состояние PR.

По запросу пользователя с применением `superpowers:writing-plans` подготовлен [план следующего PR `feat/mobile-catalog-api`](superpowers/plans/2026-10-25-mobile-catalog-api.md): HTTP repository, асинхронные состояния каталога/подробностей, snapshots с происхождением данных, подключение существующего UI и проверка с B1. Учтено, что серверное DEMO приходит по сети и не должно смешиваться со встроенным DEMO или реальными данными.

На момент планирования реализация ещё не была начата: изменены были только этот статус и файл плана. Исходники, API, дизайн и зависимости на этапе планирования не менялись; сборки и тесты не запускались, ветка не переключалась, commit/push не выполнялись. Позже пользователь поручил Task 1 — её результат приведён выше. Для живой интеграции ещё нужен доступный API base URL и команды запуска от backend-разработчика; MockEngine-часть не зависит от доступности сервера.

Следующий отдельный этап после принятия плана — только сетевой каталог M2. Browser/share остаются следующим PR; M3 зависит от готового B2. Сообщение напарнику для ручной передачи приведено в плане; автоматически не отправлялось.

## Повторная проверка PR #4 — 25 октября 2026

**Причина сохраняющегося конфликта на GitHub:** подготовленное 21 октября разрешение ещё не закоммичено и не отправлено. GitHub API подтверждает открытый PR #4 с одним коммитом `5bc21123543c5e6cd966243420c1aaa052a7478e`, `mergeable=false`, `mergeable_state=dirty`. Ветка main осталась на `959e2f4db19a87ec13b60646d969d39a3e509859`; новых удалённых изменений относительно подготовленного merge нет.

Первоначальные 14 конфликтов возникли при независимом добавлении общих файлов Starter в две ветки от исходного README-only коммита `7824a4b`, с последующим обновлением документов и validator в backend-ветке. Локально все они уже разрешены; `git status` сообщает `All conflicts fixed but you are still merging`. `HEAD=5bc2112`, `MERGE_HEAD=959e2f4`, неразрешённых записей в индексе нет. Повторный merge, reset или перезапись мобильного кода не нужны.

Проверены удалённые описание PR, refs, review submissions, discussion/inline comments, check-runs, commit statuses и Actions runs. Комментариев и ревью нет; у текущего head **нет результатов CI** (пустые check-runs/statuses/Actions runs). Это не подтверждение успешного CI. Снимки ответов без авторизационных данных находятся в ignored-каталоге `mobile/build/pr4-review/audit-2026-10-25/`.

Повторная проверка объединённого дерева подтвердила 236 файлов, сохранность всех файлов обоих родителей, точное соответствие backend/infra/scripts/tools/CI версии main, а mobile/дизайна — мобильному HEAD (кроме согласованного README ресурсов). Все 10 файлов API идентичны обоим родителям. `git diff --cached --check` и `git diff --check` проходят.

Свежая команда из `mobile/` с JDK 21 и установленным Android SDK:

```powershell
.\gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain
```

**BUILD SUCCESSFUL**, exit 0; тестовая задача выполнена заново: **43 теста, 0 failures/errors/skipped**. Lint: 0 ошибок, одно прежнее предупреждение `ObsoleteSdkInt`. Проверка отсутствия DEMO fixtures в release прошла. Лог: `mobile/build/reports/pr4-audit-2026-10-25.log`. APK SHA-256 остался `A81262703280484F67501337726DBBED2C2994C2F0748329F3F6453BC7C6B955`.

Из корня повторно выполнены `mobile/build/pr4-contract-venv/Scripts/python.exe -X utf8 scripts/validate_contract.py` и `mobile/build/pr4-contract-venv/Scripts/python.exe -m pip check`: OpenAPI, проектные правила, все 6 fixtures и согласованность зависимостей — PASS, exit 0. Проверки не отключались. Для Gradle потребовался доступ к уже существующему кешу вне песочницы; повторный запуск с этим доступом успешен.

Независимое ревью мобильной логики не обнаружило блокирующих проблем в сохранении, восстановлении и календаре. Зафиксировано небольшое замечание по коду: `App.kt:95` использует один `SaveableStateProvider(route)` для всех detail-экранов, поэтому `DetailScreen.kt:33` может переносить scroll position между разными событиями. Это не причина Git-конфликта; сценарий ещё не воспроизведён на устройстве и в этой проверке объединения не исправлялся. Требуется отдельная проверка последовательности «прокрутить событие A → назад → открыть B».

Backend verify заново не запускался: Docker на этой машине по-прежнему не найден; предыдущая попытка завершилась ошибкой Testcontainers (подробности ниже). Полная серверная интеграция и iOS здесь не подтверждены. UI, шрифты, APK и существующие screenshots не изменены; новый UI smoke не выполнялся. Новые этапы и изменения API не начинались.

**Оставшееся действие:** пользователь завершает уже подготовленный merge командой `git commit` и отправляет `feat/mobile-bootstrap` обычным `git push`. После этого PR #4 получит разрешение конфликтов; повторный PR не требуется. Commit/push оставлены пользователю в соответствии с его действующими правилами. Перед слиянием PR в main нужны результаты CI отправленного коммита.

Сообщение напарнику для ручной передачи: «PR #4 пока показывает прежний мобильный commit, локальные разрешения готовы. Android/43 tests и контракт повторно прошли 25 октября. Backend и API сохранены; после commit/push нужно проверить CI. REMOTE-интеграция остаётся отдельным заданием».

## PR #4 — локальное объединение с main, 21 октября 2026

По явному разрешению пользователя выполнен `git merge --no-commit --no-ff origin/main` в `feat/mobile-bootstrap`. Все **14 конфликтов разрешены**, результат добавлен в индекс и оставлен для пользовательского commit/push. `HEAD` остаётся `5bc21123543c5e6cd966243420c1aaa052a7478e`; `MERGE_HEAD` и `origin/main` — `959e2f4db19a87ec13b60646d969d39a3e509859` (B1, PR #3). [PR #4](https://github.com/KDvibers/HackAtlas/pull/4) обновится после пользовательского push. Серверная авторизация B2 и мобильная REMOTE-интеграция этим объединением не реализуются.

Обычный `git fetch` обрывался; исходные объекты main получены через GitHub API с проверкой каждого Git SHA. Сохранены исходные коммиты, родители и история до общего `7824a4b`; новые коммиты не создавались. `git fsck --full --no-reflogs` не выявил повреждённых или недостающих объектов.

### Разрешённые файлы

- Взята актуальная версия main: `.gitignore`, `AGENTS.md`, `backend/AGENTS.md`, `docs/PROJECT.md`, `docs/ROADMAP.md`, `docs/STATUS_BACKEND.md`, `docs/TOOLCHAIN.md`, `docs/WORKFLOW.md`, `scripts/README.md`, `scripts/validate_contract.py`. Собственный отчёт backend не редактировался: он точно совпадает с main.
- `README.md` и `START_HERE.md` объединяют актуальные сведения B0/B1 и M0/M1/локальной M2, сохраняют ссылки и команды серверной части.
- `design/assets/README.md` сохраняет мобильные ресурсы и добавляет факт утверждения M1 20 октября; `docs/STATUS_MOBILE.md` сохраняет историю и содержит этот отчёт.
- Остальные изменения пришли автоматически из main. Поблобно и по Git file mode проверено объединение всех **236 файлов**: backend, infra, scripts, tools и CI совпадают с main; `mobile/`, ресурсы и референс совпадают с исходным мобильным HEAD. Все **10 файлов `api/` неизменны** относительно обоих родителей. Новых миграций сверх main нет.

### Свежие проверки объединённого состояния

Из `mobile/`, с `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` и `ANDROID_HOME=C:\Users\user\AppData\Local\Android\Sdk`:

```powershell
.\gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain
.\gradlew.bat :shared:testDebugUnitTest --rerun --offline --console=plain
```

- Обе команды — **BUILD SUCCESSFUL**, exit 0. Второй запуск действительно повторил тесты, а не использовал `UP-TO-DATE`: **43 теста, 0 failures/errors/skipped**.
- Android lint — **0 ошибок, 1 прежнее предупреждение** `ObsoleteSdkInt` о локальном `mipmap-anydpi-v26`. Проверка отсутствия DEMO fixtures в release — PASS.
- APK: `mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk`, SHA-256 `A81262703280484F67501337726DBBED2C2994C2F0748329F3F6453BC7C6B955`. Совпадает с APK ранее проверенного M2 smoke.
- Логи: `mobile/build/reports/pr4-merge-checks.log`, `pr4-merge-unit-tests.log`; XML результатов — `mobile/shared/build/test-results/testDebugUnitTest/`.

Расширенный валидатор из main проверен в отдельном Python 3.12 окружении с его закреплёнными зависимостями. Команды из корня:

```powershell
$taskPython = 'C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $taskPython -m venv mobile/build/pr4-contract-venv
& mobile/build/pr4-contract-venv/Scripts/python.exe -m pip install --disable-pip-version-check --requirement scripts/requirements-contract.txt
& mobile/build/pr4-contract-venv/Scripts/python.exe -X utf8 scripts/validate_contract.py
& mobile/build/pr4-contract-venv/Scripts/python.exe -m pip check
```

Все команды — exit 0. **PASS**: OpenAPI 3.1, локальные ссылки, operation IDs/path parameters, правила x-phase и opaque sessions без refresh, все шесть fixtures. `pip check`: `No broken requirements found`. Логи: `mobile/build/reports/pr4-contract-install.log`, `pr4-contract-check.log`. Старый набор двух Python-пакетов в историческом отчёте M2 ниже больше не достаточен для текущего валидатора.

Из `backend/`, с тем же JDK 21:

```powershell
.\mvnw.cmd --version
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Wrapper подтвердил Maven 3.9.16 / Java 21.0.9. Java-код скомпилирован, JAR создан, но **полный verify — BUILD FAILURE, exit 1**: Testcontainers сообщает `Could not find a valid Docker environment`. Docker недоступен, WSL не установлен. Failsafe сообщил 21 ошибку инициализации контекста; успешный запуск PostgreSQL/HTTP/миграционных тестов не подтверждён. Тесты не отключались. Лог: `mobile/build/reports/pr4-backend-verify.log`, детали — `backend/target/failsafe-reports/`. Для полного backend verify нужны Docker с Linux containers либо существующий CI после push; Bruno/Schemathesis и проверка настоящих HTTP-ответов здесь не выполнялись.

`git diff --cached --check` и `git diff --check` — PASS; `git ls-files -u` пуст. Независимое ревью подтвердило сохранность дерева обоих родителей и разрешения конфликтов. Локальная детализация сверки — `mobile/build/pr4-review/merge-verification.json` (ignored).

### Ограничения и передача

UI и шрифты не менялись, новый Android UI smoke не запускался. Ранее принятые screenshots — `mobile/artifacts/m1/`, снимки M2 — `mobile/artifacts/m2/screenshots/`; их содержимое и APK сохранены. iOS по-прежнему требует Mac/Xcode и здесь не проверен.

Изменений API нет. Короткое сообщение напарнику для ручной передачи:

> В `feat/mobile-bootstrap` подготовлено объединение с main `959e2f4` без коммита. Backend B0/B1 и API сохранены; Android build/43 tests и contract-check прошли. Локальный backend verify упирается в отсутствие Docker. После пользовательского commit/push результат попадёт в PR #4; мобильная интеграция с B1 остаётся отдельным заданием.

Граница задания: только merge, разрешение конфликтов и проверки. Commit и push выполняет пользователь. Новые функции M2/M3 не начинались.

## Исторический отчёт M2 — 20 октября 2026

Сведения ниже о B0 у напарника и отсутствии commit/push относятся к моменту реализации M2. Они не описывают текущее состояние PR #4.

Обновлено 20 октября 2026. Ветка `feat/mobile-bootstrap`, база `7824a4b` (`origin/main`, исходно только README). Изменения локальные: commit, push и PR не выполнялись.

**20 октября 2026: пользователь утвердил визуал M1 и поручил независимую от backend часть M2.** M0/M1 проверены на Android; iOS host подготовлен, но без Mac/Xcode не собран и не запущен. Текущий отчёт M2 находится перед историческим отчётом M1.

## Согласованный объём M2

Только постоянные гостевые закладки, сохранённые подробности без сети и календарь сохранённого. Backend у напарника на B0. API, auth, browser/share, reminders и публикация не входят в это задание.

1. Версионированный JSON-файл в приватном хранилище приложения: копии карточки/подробностей и время сохранения. Отдельный scope для DEMO; без токенов и данных аккаунтов. Android FileChannel.force + atomic move, iOS атомарная запись в Application Support. Обновлять UI только после успешной записи; ошибочный/неизвестный формат не затирать. Один владелец AppState на процесс, commit и публикация состояния защищены от отмены при пересоздании экрана.
2. Общий repository и тесты: восстановление без каталога, удаление последней записи, сбой записи и повтор, конкурентные клики, неизвестная версия и разделение scope.
3. Экран сохранённого открывает собственный snapshot; показывает дату копии, не подменяя lastVerifiedAt. Demo seed применяется только при первом создании хранилища.
4. Календарь месяца: дни мероприятий включительно, отдельные дедлайны, сохранение точности DATE/MINUTE и опубликованной локальной даты; неизвестные даты отдельным списком. Открытие деталей из snapshot.
5. Android build/tests/lint, force-stop/relaunch и offline smoke; новые скриншоты отдельно от утверждённого M1 baseline. iOS на Windows остаётся непроверенным.

## M2: изменения в коде

- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/GuestSavedRepository.kt` — версионированные копии подробностей, `savedAt`, StateFlow, последовательная атомарная запись и идемпотентные save/remove. Пустой файл после удаления последнего сохранения не заполняется заново. Ошибочный файл/неизвестная версия не сбрасываются.
- `mobile/shared/src/androidMain/kotlin/dev/hackatlas/mobile/data/AtomicGuestFileStorage.kt`, `AndroidGuestSnapshotStorage.kt` — приватное Android-хранилище в `no_backup`, временный файл в том же каталоге, force и atomic move с распространением ошибок.
- `mobile/shared/src/iosMain/kotlin/dev/hackatlas/mobile/data/IosGuestSnapshotStorage.kt`, `MainViewController.kt` — подготовлены атомарная запись в Application Support и один владелец состояния на процесс; не проверено на Mac.
- `mobile/androidApp/src/main/kotlin/dev/hackatlas/mobile/HackAtlasApplication.kt`, `MainActivity.kt`, `AndroidManifest.xml`; `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/AppState.kt`, `App.kt` — единый repository на процесс, восстановление сохранённого независимо от DEMO-каталога, корректное состояние при пересоздании Activity и отмене coroutine после записи.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/ProfileScreen.kt`, `DetailScreen.kt`, `DisplayFormat.kt` — сохранённые списки, актуальный локальный счётчик, дата копии и предупреждение об устаревании. `lastVerifiedAt` не меняется.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/domain/SavedCalendar.kt`, `ui/CalendarScreen.kt` — месяц, отдельные события/дедлайны, точность и часовой пояс источника, неизвестные даты, пустые состояния, открытие копии и возврат к выбранной дате. DEMO-маркировка календаря, без напоминаний.
- `domain/Models.kt` сериализуется только для внутреннего формата хранения; HTTP DTO и API не менялись. Тексты в `mobile/shared/src/commonMain/composeResources/values/strings.xml`.
- Регрессии: `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/GuestSavedRepositoryTest.kt`, `OfflineBootstrapTest.kt`, `domain/SavedCalendarTest.kt`; `mobile/shared/src/androidUnitTest/kotlin/dev/hackatlas/mobile/data/AtomicGuestFileStorageTest.kt`. Повторяемый Android smoke — `mobile/scripts/android_m2_smoke.py`; документация — `mobile/README.md`.

Независимое ревью выявило проблемы подавления ошибки в первоначальном Android AtomicFile, повторного toggle при удалении и нескольких владельцев repository при пересоздании host. Они исправлены; регрессионный запуск сначала воспроизвёл 4 падения, после исправлений тесты прошли. Логи — `mobile/build/reports/m2-review-red.log`, `m2-verified-build.log`. Также добавлена явная DEMO-маркировка календаря.

## M2: проверки и артефакты

Финальная команда из `mobile`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\user\AppData\Local\Android\Sdk'
.\gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain
```

- **BUILD SUCCESSFUL**, exit 0. Лог `mobile/build/reports/m2-final-build.log`.
- **43 / 43 теста**, failures=0, errors=0, skipped=0: прежние 18, GuestSavedRepository 12, OfflineBootstrap 3, SavedCalendar 8, Android filesystem adapter 2. XML: `mobile/shared/build/test-results/testDebugUnitTest/`.
- Lint: **0 ошибок, 1 прежнее предупреждение** `ObsoleteSdkInt` о пустой локальной папке `mipmap-anydpi-v26`. Проверки не отключались.
- `verifyReleaseNoDemoFixtures` — PASS. Release остаётся технической сборкой без готового API-клиента.
- APK: `mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk`, 25 408 235 байт. SHA-256: `A81262703280484F67501337726DBBED2C2994C2F0748329F3F6453BC7C6B955`.
- Все **10 файлов `api/` совпадают со Starter** при сравнении текста с нормализацией CRLF/LF. Отчёт: `mobile/artifacts/m2/starter-api-check.json`.
- `scripts/validate_contract.py` — PASS: шесть fixtures, локальные ссылки и схемы. Первоначально в текущем Python отсутствовали PyYAML/jsonschema; для повторного запуска установлены `PyYAML==6.0.2`, `jsonschema==4.23.0` в локальную ignored-папку `mobile/build/contract-deps`, без изменения окружения проекта или API.

Команды локальной проверки контракта из корня (для текущего bundled Python):

```powershell
$taskPython = 'C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $taskPython -m pip install --target 'mobile/build/contract-deps' 'PyYAML==6.0.2' 'jsonschema==4.23.0' --disable-pip-version-check
$env:PYTHONPATH = (Resolve-Path 'mobile/build/contract-deps').Path
& $taskPython -X utf8 scripts/validate_contract.py
```

Android-проверка выполняется на Pixel 8 AVD, API 37, в отдельном debug-пакете. Команда из корня:

```powershell
python -X utf8 mobile/scripts/android_m2_smoke.py --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" --serial emulator-5554 --capture-variants
```

Проверены добавление/удаление после force-stop и запуска; чтение подробностей в авиарежиме с отключённым источником каталога; отдельные дни проведения/дедлайны; возврат к выбранной дате; неизвестная версия файла без перезаписи; удаление всех закладок без повторного DEMO seed после перезапуска. Тест восстанавливает исходный файл debug-приложения и настройки эмулятора. Поправлены два допущения самого UI-сценария: время дедлайна ниже первого экрана требует прокрутки, а после возврата из карточки восстановленная прокрутка оставляет заголовок месяца выше viewport.

**Итоговый UI smoke: 6 / 6 PASS, exit 0**, включая смену конфигурации на 360 dp и text scale 1.3, открытие подробностей/возврат и проверку неизменности сохранённых копий. Hash APK в `android-smoke-environment.json` совпадает с указанным выше. Визуально просмотрены обычные saved/detail/calendar и варианты 360 dp: значимых наложений текста или недоступных кнопок не обнаружено, длинное содержимое прокручивается. После проверки исходный файл восстановлен, авиарежим выключен, параметры дисплея возвращены; созданный для проверки headless-эмулятор остановлен.

Артефакты M2: `mobile/artifacts/m2/review.html`, `android-smoke.json`, `android-smoke-environment.json`, `screenshots/`. Снимки получены из APK, не сгенерированы. Утверждённые снимки M1 сохранены отдельно в `mobile/artifacts/m1/`.

## M2: ограничения и передача

iOS compile/link и поведение файлового хранилища требуют Mac/Xcode: на Windows это не проверено. Не проверены физические Android-устройства, полная доступность TalkBack/VoiceOver, аварийное выключение устройства во время записи и подписанный release. Проверенные перезапуски — force-stop/relaunch, а не отключение питания. Изменённые даты/описания с сервера ещё не обновляют сохранённые копии; источник пока только DEMO Starter.

**Изменений API нет.** Короткое сообщение для напарника (текст для передачи, не отправлялось):

> Ветка `feat/mobile-bootstrap`, изменения пока локальные. На мобильной стороне готовы гостевые закладки с сохранением после перезапуска, офлайн-копии подробностей и календарь сохранённого. Используем неизменённый Starter API; backend/auth пока не подключали. Когда B1 будет готов, нужны base URL, команды запуска и примеры list/detail/filter-metadata. Авторизация в дальнейшем остаётся opaque Bearer sessions.

Следующий отдельный этап — согласованная интеграция REMOTE-каталога по готовности B1. Browser/share остаются отдельной частью M2; auth, синхронизация и reminders — M3. В текущем задании эти части не выполняются. Push, PR, merge и публикация не выполнялись.

## Исторический отчёт M1 — 12 октября 2026

Описанные ниже ограничения и результаты относятся к M1 до утверждения и реализации M2; актуальные изменения M2 перечислены выше.

### Основа и границы

Использован только `HackAtlas_Codex_Starter (1).zip`. Handoff устарел и не переносился. Все файлы `api/` побайтово по тексту сверены с Starter, изменений контракта нет. Авторизация будущего B2/M3: opaque Bearer sessions, без JWT и без refresh; сейчас не реализована и готовность сервера не заявляется.

Starter размещён в новом локальном клоне общего репозитория. Backend/infra реализация не добавлялась; файлы backend из Starter сохранены как документация.

## Что сделано

- `mobile/shared`, `androidApp`, `iosApp`; отдельный wrapper, version catalog, Android host и настоящий Xcode project с общей scheme. Совместимые версии и инструкции — `mobile/TOOLCHAIN.md`, `mobile/README.md`.
- Общий Compose UI: каталог, подробности, профиль; четыре вкладки, экран сохранённого, честное описание будущего календаря. Settings содержит вход в образец типографики. Android Back работает; фильтр и scroll сохраняются при возврате из detail.
- `HackAtlasTheme`, pixel headings, Exo 2 event titles, Inter body, JetBrains Mono captions, контурные иконки, cards/info/chips/tab bar и яркая CTA с локальным свечением.
- Четыре отдельных сгенерированных PNG и лицензированные шрифты с проверенными glyphs. Реестр, промпты, лицензии и аудиты — `design/assets/REGISTRY.md`. GreenTech — отдельная процедурная Canvas-иллюстрация турбин, не исходный арт с фото.
- DTO Starter отдельно от domain/UI. Один DemoRepository, канонические fixtures автоматически упаковываются только в debug. Фиксированное `asOf=2026-10-12T10:00:00Z`; поддержаны local demo search/filter/sort и общие закладки.
- Закладки M1 **в памяти**, без обещания сохранения после перезапуска. Счётчики профиля связаны с настоящим demo-state, никаких вымышленных побед. На registration/share/edit показывается пояснение этапа, внешние сайты и фиктивные отправки не запускаются.
- Loading, load error/retry, empty search/reset, empty saved. Поисковый ввод ограничен 120 символами контракта. Дата без времени не превращается в 23:59; неизвестные суммы не становятся нулём.
- Release не включает demo JSON, не запрашивает network permission и показывает экран отсутствующего каталога. Это техническая сборка, не готовый публичный release.

## Реальные проверки

PowerShell из `mobile`, с JDK 21 и существующим Android SDK:

```powershell
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures
```

- Android debug assembly — PASS. APK: `mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- Common tests через Android JVM: **18 / 18**, failures=0, errors=0, skipped=0 (`ContractParsingTest` 7, `DemoRepositoryTest` 11).
- Lint: **0 ошибок, 1 предупреждение** `ObsoleteSdkInt` о пустой локальной папке `mipmap-anydpi-v26` после переноса development icon. Исходный icon теперь в `mipmap-anydpi`; Git пустую папку не сохраняет. Автоматическая проверка инструмента отклонила попытку удалить пустую папку (`blocked by policy`); папка оставлена, lint не отключался.
- Unsigned release assembly и ZIP-проверка: debug содержит 6 demo JSON, release — 0. Release signing/store upload не выполнялись.
- `python scripts/validate_contract.py` с PyYAML/jsonschema — PASS для всех 6 fixtures, локальных `$ref` и JSON schemas. Это не полный OpenAPI lint и не проверка работающего сервера.
- Android UI smoke: реальные ADB taps и свежая UIAutomator hierarchy. Проверены независимый bookmark click, detail/back, безопасная demo-регистрация, общий счётчик, удаление из saved до empty, поиск 160→120 символов/empty/reset, offline-format filter и его сохранение после back, settings→typography→back, календарная граница этапа.
- Реальные screenshots получены на headless Pixel 8 AVD, Android API 37 (x86_64, 16KB image), основной viewport около 390×845 dp. Проверка одного эмулятора не означает проверку всех Android 8+ устройств.
- Начальный AVD с 2GB RAM однажды завершил приложение через low-memory killer (системный `ApplicationExitInfo: LOW_MEMORY`, без Java crash). После перезапуска AVD с 4GB и остановки Gradle daemon сценарии выполнены заново. Это не измерение производительности на физическом телефоне.

Запуск повторяемого UI smoke из корня (debug APK уже установлен, устройство подключено):

```powershell
python mobile/scripts/android_smoke.py --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" --capture-variants
```

Результаты и скриншоты: `mobile/artifacts/m1/`. Логи Gradle: `mobile/build/reports/m1-verified-build.log`; XML тестов: `mobile/shared/build/test-results/testDebugUnitTest/`. Build-каталоги ignored.

## Визуальная проверка

Открыть `mobile/artifacts/m1/review.html`. Основные реальные изображения: `screenshots/home.png`, `detail.png`, `profile.png`, `typography.png`. Визуально просмотрены также все три экрана в вариантах `*-360-large.png` (360 dp, text scale 1.3) и `*-430.png` (430 dp). Критических наложений текста или недоступных кнопок не обнаружено; на узком экране длинные строки переносятся, фильтры прокручиваются горизонтально, содержимое — вертикально. Итоговый повторяемый smoke завершился пятью PASS; результат — `mobile/artifacts/m1/android-smoke.json`.

SHA-256 итогового debug APK: `2740EF85D7EB7B45F9D127342B87CCF85B419CAC522878DA49D06DF4939A42F2`.

Сохранены чёрный фон, фуксия/cyan, пиксельная кириллица, wireframe-мотивы, тонкие outlines, композиция трёх экранов, четыре вкладки и закреплённая CTA. Проверены читаемость, реальные glyphs и независимые интерактивные элементы. По результатам первого просмотра затемнены микро-подписи над артом, добавлена тень под hero title, уплотнён профиль. UI не является картинкой референса.

Отличия от фото: исходный шрифт неизвестен, выбран Press Start 2P; event font — Exo 2. Новые иллюстрации ярче/объёмнее; у GreenTech иной Canvas-арт. Даты, город, интересы и честные счётчики следуют Starter, поэтому не повторяют вымышленные значения фото. Масштабы/safe areas/длина реальных текстов различаются; ниже видимой области содержимое прокручивается. Это не заявление о пиксельном совпадении. Approved screenshot baseline пока не создан.

## Не проверено / не входит в M1

iOS compile/link/resources/device; физические Android-телефоны; TalkBack/VoiceOver полным ручным прогоном; подписанный release; сервер, HTTPS/API integration, account auth, persistence/offline cache, notifications, настоящий browser/share, CI runs. Bundle/application ID временный development. M2 и M3 не выполнялись.

## Передача Java-разработчику и следующий шаг

Мобильная M1 использует неизменённые `api/openapi.yaml` + `api/README.md` + `api/examples/` Starter. Временного отдельного API нет. Для будущего M2 понадобятся реально готовые `/api/v1/hackathons`, `/hackathons/{id}`, `/metadata/filters`, base URL и инструкции запуска. Auth по прежнему согласован как opaque sessions; сейчас приложение не обращается к серверу.

Следующий шаг — пользовательский просмотр APK/трёх экранов/типографики и утверждение либо конкретные правки M1. Рекомендуемый будущий commit: `feat(mobile): bootstrap shared app and neon demo screens`; название PR: `Mobile M0/M1: shared Android/iOS skeleton and reference-based demo UI`. Не публиковать без поручения.
