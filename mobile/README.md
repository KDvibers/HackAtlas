# HackAtlas Mobile

Kotlin Multiplatform / Compose Multiplatform. Общий код в `shared`, Android host в `androidApp`, Xcode host в `iosApp`. Версии и ограничения — [TOOLCHAIN.md](TOOLCHAIN.md). Выполненный этап и реальные проверки — [STATUS_MOBILE.md](../docs/STATUS_MOBILE.md).

Визуал M1 утверждён: [галерея трёх экранов и образец шрифта](artifacts/m1/review.html). Скриншоты получены из Android debug APK; они не являются макетами. [Результат UI smoke M1](artifacts/m1/android-smoke.json), сценарий — `scripts/android_smoke.py`. Текущий объём M2: постоянные гостевые закладки, сохранённые подробности без сети и календарь; подключение backend отдельно.

## Android на Windows

Откройте папку `mobile` в Android Studio либо запустите PowerShell из неё. Выберите JDK 21 в Gradle JDK IDE. Для текущего компьютера перед запуском команд можно задать переменные только в этом терминале:

```powershell
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest
.\gradlew.bat :androidApp:lintDebug
```

Пути выше описывают проверенное окружение; если Android Studio/SDK установлены иначе, укажите фактические пути. Не коммитьте `local.properties` и персональные SDK paths. Системный `gradle` не нужен: используйте wrapper.

Установка на уже запущенный эмулятор/подключённый телефон:

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices
& $adb install -r '.\androidApp\build\outputs\apk\debug\androidApp-debug.apk'
& $adb shell am start -n dev.hackatlas.mobile.debug/dev.hackatlas.mobile.MainActivity
```

Debug принимает `--es screen home`, `detail`, `profile`, `saved`, `calendar` и `typography`, например:

```powershell
& $adb shell am start -S -n dev.hackatlas.mobile.debug/dev.hackatlas.mobile.MainActivity --es screen typography
```

Не запускайте несколько Gradle build-процессов, одновременно редактирующих одни generated outputs. Логи текущих проверок сохраняются локально в `build/reports` и не коммитятся.

## DEMO и release

Debug по умолчанию выбирает DEMO явно через build configuration; также поддерживает REMOTE (см. ниже). Встроенный DEMO использует канонические вымышленные fixtures из `../api/examples`. `generateDebugFixtures` автоматически копирует JSON в Android **debug assets**. `Res.readBytes("files/demo/<filename>.json")` обращается к этим файлам; отдельной ручной копии JSON в исходниках нет. Common tests получают `CanonicalFixtures.files` из того же источника через `generateCanonicalTestFixtures`.

Общие строки, шрифты и графика находятся в `shared/src/commonMain/composeResources`. Сгенерированный public класс — `dev.hackatlas.mobile.resources.Res`.

Android release не получает demo JSON и всегда выбирает REMOTE. Без корректного HTTPS base URL он показывает состояние «Источник не настроен». Debug имеет отдельный ID `.debug`. INTERNET разрешён; cleartext запрещён, кроме явно выбранного host в debug-конфигурации ниже. Проверка упаковки unsigned release:

```powershell
.\gradlew.bat :androidApp:verifyReleaseNoDemoFixtures
```

Это проверка APK, не публикация и не подтверждение готовности release. Подписи, production backend, privacy и проверки на устройствах остаются последующими этапами. Launcher mark временный для development; финальная иконка магазина не утверждена.

## M2: гостевые закладки и календарь

`GuestSavedRepository` хранит полные локальные копии подробностей и `savedAt`. Копию можно открыть без загрузки каталога; дата последней проверки источника (`lastVerifiedAt`) при этом не меняется. Экран явно сообщает, что сведения могли устареть. Арт приложения доступен локально, новые изображения из сети не загружаются.

На Android файл находится в приватной `no_backup/guest-saved/demo-v1.json`: запись через `FileChannel.force` и атомарную замену файла. На iOS подготовлена атомарная запись в `Application Support/GuestSaved/demo-v1.json`, но её проверка на Mac ещё нужна. DEMO и каждый REMOTE origin имеют разные имена файлов и проверяемый scope; сессии и данные аккаунта здесь не хранятся.

Один `AppState` владеет хранилищем в течение процесса. UI подтверждает изменение после успешной записи. Ошибка чтения или неизвестная версия показываются пользователю без сброса данных. Три начальные DEMO-закладки добавляются только при создании файла: после удаления последней записи они не возвращаются. Удаление приложения удалит локальные закладки; перенос в аккаунт или синхронизация пока отсутствуют.

Календарь использует только сохранённые копии: дни проведения включительно и дедлайн регистрации отмечаются отдельно. Сохраняются опубликованные локальные даты, точность DATE/MINUTE и исходный часовой пояс. Неизвестные даты отображаются отдельным списком; время не выдумывается. Есть переходы между месяцами, «Сегодня» и открытие подробностей. Уведомления и системный календарь не подключены.

Повторяемая проверка M2 из корня репозитория после установки debug APK на эмулятор:

```powershell
python -X utf8 mobile/scripts/android_m2_smoke.py --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" --capture-variants
```

Сценарий проверяет перезапуск, авиарежим, недоступный каталог, календарь, пустое хранилище и неизвестную версию файла. `--capture-variants` дополнительно проверяет навигацию при 360 dp и тексте 130%. Он временно меняет файл отдельного debug-приложения и восстанавливает его и настройки дисплея в `finally`; запуск разрешён только на эмуляторе. Результат — `artifacts/m2/android-smoke.json`, снимки — `artifacts/m2/screenshots/`, [галерея M2](artifacts/m2/review.html).

Для ручной проверки сохранённого без каталога используйте debug-only параметр при холодном запуске:

```powershell
& $adb shell am start -S -n dev.hackatlas.mobile.debug/dev.hackatlas.mobile.MainActivity --es screen saved --ez savedOnly true
```

Это отключает каталог в debug-процессе; обычный холодный запуск возвращает выбранный в сборке источник. Выбор источника фиксирован до перезапуска процесса.

## iOS: подготовлен, не собран

Нужен Apple Silicon Mac и совместимый Xcode (см. TOOLCHAIN). На Mac:

```sh
cd mobile
chmod +x gradlew
open iosApp/iosApp.xcodeproj
```

Выберите общую scheme `HackAtlas`, Debug и ARM64 iPhone simulator. Xcode build phase запускает `:shared:embedAndSignAppleFrameworkForXcode`; заранее настройте JDK 21 в окружении, доступном Xcode. Для настоящего устройства отдельно выберите свою signing team; в проекте нет чужих team ID или сертификатов.

Проверка simulator без подписи на доступном Mac:

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme HackAtlas \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Swift — минимальная оболочка `ComposeUIViewController`, экраны остаются общими. Debug-only Xcode phase копирует canonical fixtures в app bundle; Release выбирает REMOTE и не копирует JSON. На Windows эти iOS команды не выполнялись. Проверьте ресурсные пути, Swift bridge и запуск на Mac до утверждения iOS-готовности.

## M2 Task 4: подключение источника

`CatalogRuntime` владеет process scope, одним AppState, клиентом и engine. Android использует OkHttp, iOS — Darwin той же версии Ktor 3.3.3. Поворот/пересоздание host не создаёт второго владельца файла. Закрытие процесса освобождает runtime; сохранение не привязано к жизненному циклу composable.

`BookmarkStore` сохраняет pending keys и неподтверждённые ошибки в памяти процесса. Новый экран после пересоздания получает текущее состояние; ошибка удаляется из очереди только по подтверждению. Эти UI-состояния не записываются на диск и не восстанавливаются после завершения процесса; сами snapshots сохраняются атомарно.

Android, команды из `mobile/` (JDK 21 и Android SDK в окружении):

```powershell
# Встроенная утверждённая демонстрация
.\gradlew.bat :androidApp:assembleDebug

# HTTPS API; адрес передаётся локально, в репозиторий его не записываем
.\gradlew.bat :androidApp:assembleDebug '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=https://YOUR_HOST/api/v1/'

# Только локальный emulator smoke из этого Task 4
.\gradlew.bat :androidApp:assembleDebug '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8768/api/v1/' '-Phackatlas.allowDebugHttp=true'

# Release принимает отдельный HTTPS адрес; иначе честная ошибка конфигурации
.\gradlew.bat :androidApp:assembleRelease '-Phackatlas.releaseApiBaseUrl=https://YOUR_HOST/api/v1/'
```

Debug HTTP требует явного opt-in и допускается только для точного hostname из debug URL, без поддоменов; остальные адреса запрещены. Release не содержит этого XML/manifest override, не читает debug URL и не разрешает HTTP. Настройка не изменяет системный trust store и не отключает TLS-проверки. Переходы HTTP redirects отключены. URL должен заканчиваться `/api/v1` либо `/api/v1/`; credentials, query/fragment и произвольный относительный путь запрещены.

iOS: передайте `HACKATLAS_SOURCE=REMOTE HACKATLAS_API_BASE_URL=https://YOUR_HOST/api/v1/` как локальные Xcode build settings или аргументы `xcodebuild`. Debug по умолчанию DEMO; Release — REMOTE. ATS exceptions не добавлены: iOS REMOTE требует HTTPS даже в Debug. Mac/Xcode compile/link и работа Darwin ещё не проверены.

REMOTE не загружает профиль/каталог из fixtures. Гостевой профиль показывает число локальных закладок. Сохранение запрашивает полный detail и сверяет ID/isDemo перед записью; удаление использует точный ключ без HTTP. Файлы разных canonical base URL разделены. Сохранённое и календарь доступны до ответа каталога/metadata, а открытая копия остаётся видна после удаления закладки. `sourceUrl`, `lastVerifiedAt` и `savedAt` отображаются раздельно. Browser/share/auth в этот этап не входят.

Сетевой Android smoke (из корня, после установки REMOTE debug APK для URL выше):

```powershell
python -X utf8 mobile/scripts/android_catalog_smoke.py --adb "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
```

Скрипт поднимает временный synthetic HTTP fixture server на **127.0.0.1:8768**, проверяет реальные запросы OkHttp и UI, копии `isDemo=true/false`, 404, фильтры AND, metadata retry, пагинацию и холодный offline запуск. Тестовые названия начинаются с MOCK; `isDemo=false` здесь — тест классификации, не реальные события. Существующий файл именно этого test origin восстанавливается в `finally`; встроенный DEMO-файл не меняется. Нужен запущенный эмулятор; физические устройства скрипт отвергает. Результаты и снимки — `artifacts/m2-catalog/`. Это не приёмка настоящего B1: она остаётся Task 5 и требует адрес/запуск/ID от backend-разработчика.

## Task 5: приёмка с локальным B1

Для пяти событий backend seed Android debug можно собрать с двумя событиями на страницу. Параметр `hackatlas.debugPageSize` принимает целое 1..50 по Starter; без него используется 20. Bundled DEMO и Android release всегда начинают с 20; iOS host сохраняет прежний default.

```powershell
.\gradlew.bat :androidApp:assembleDebug '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8080/api/v1/' '-Phackatlas.allowDebugHttp=true' '-Phackatlas.debugPageSize=2'
```

Backend для Task 5 запускается отдельным клоном на commit `a745f4ade0cfacaa769fceaedf57a61baa397ee2`, JDK 21 и Docker Linux. Следовать `backend/README.md` этого commit: PostgreSQL, полный `mvnw.cmd --batch-mode --no-transfer-progress verify`, затем из `backend/`:

```powershell
java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --hackatlas.demo.enabled=true --hackatlas.demo.as-of=2026-10-12T10:00:00Z
```

В локальном стенде используется отдельный compose project `hackatlas-task5` с PostgreSQL на `127.0.0.1:5433`; JDBC URL в игнорируемом `infra/.env` должен соответствовать порту. Пароли и содержимое `.env` не добавлять в PR. Хост обращается к `http://127.0.0.1:8080/api/v1`, Android Emulator — к `http://10.0.2.2:8080/api/v1/`. Все пять событий seed вымышленные и имеют `isDemo=true`.

После установки APK (`adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`) выполнить из корня репозитория:

```powershell
python mobile/scripts/android_remote_smoke.py --adb adb --serial emulator-5554 --api-base-url http://127.0.0.1:8080/api/v1 --event-id 39287368-5259-5235-a1c4-25b4e1e8421e
```

Smoke использует настоящий API, временно отключает сеть **эмулятора**, сохраняет исходные guest-файлы и настройки дисплея/сети, восстанавливает их в `finally`. Резервные копии на случай обрыва процесса находятся в игнорируемом `mobile/build/task5/device-backup/`. Использовать тестовый эмулятор; для Pixel 8 с Google Play потребовалось 4 ГБ RAM, поскольку при 2 ГБ Android завершал процессы из-за LOW_MEMORY.

Результат и реальные screenshots сохраняются в `mobile/artifacts/m2-remote/`; проверять поле `status` в `remote-smoke.json`, наличие картинок само по себе не означает PASS. Состояние Task 5 и ограничения — в `docs/STATUS_MOBILE.md`; прежние M1/M2 галереи остаются историческими результатами.

Проверки самого отчёта (без adb/backend): `python -m unittest discover -s mobile/scripts -p test_android_remote_smoke.py`. Пустой `AssertionError` и прерывание сценария обязаны дать FAIL и выполнить восстановление. Принудительное завершение Python или компьютера не позволяет исполнить `finally`: для этого остаются emergency backups. Сценарий не рассчитан на параллельные запуски на одном эмуляторе.
