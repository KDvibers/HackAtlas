# MOBILE: M0 / M1 и независимая часть M2

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
