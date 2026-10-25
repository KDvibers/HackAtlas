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

Debug — явно обозначенный DEMO на канонических вымышленных fixtures из `../api/examples`. `generateDebugFixtures` автоматически копирует JSON в Android **debug assets**. `Res.readBytes("files/demo/<filename>.json")` обращается к этим файлам; отдельной ручной копии JSON в исходниках нет. Common tests получают `CanonicalFixtures.files` из того же источника через `generateCanonicalTestFixtures`.

Общие строки, шрифты и графика находятся в `shared/src/commonMain/composeResources`. Сгенерированный public класс — `dev.hackatlas.mobile.resources.Res`.

Android release не получает demo JSON и передаёт `demoEnabled = false`; до отдельной интеграции backend он не является готовым клиентом работающего API. Debug намеренно имеет отдельный ID `.debug`. Открытого HTTP и сетевых разрешений нет. Проверка упаковки unsigned release:

```powershell
.\gradlew.bat :androidApp:verifyReleaseNoDemoFixtures
```

Это проверка APK, не публикация и не подтверждение готовности release. Подписи, production backend, privacy и проверки на устройствах остаются последующими этапами. Launcher mark временный для development; финальная иконка магазина не утверждена.

## M2: гостевые закладки и календарь

`GuestSavedRepository` хранит полные локальные копии подробностей и `savedAt`. Копию можно открыть без загрузки каталога; дата последней проверки источника (`lastVerifiedAt`) при этом не меняется. Экран явно сообщает, что сведения могли устареть. Арт приложения доступен локально, новые изображения из сети не загружаются.

На Android файл находится в приватной `no_backup/guest-saved/demo-v1.json`: запись через `FileChannel.force` и атомарную замену файла. На iOS подготовлена атомарная запись в `Application Support/GuestSaved/demo-v1.json`, но её проверка на Mac ещё нужна. DEMO и будущий REMOTE имеют разные имена файлов и проверяемый scope; сессии и данные аккаунта здесь не хранятся.

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

Это намеренно отключает источник каталога в debug-процессе; обычный холодный запуск возвращает DEMO-каталог. К API приложение в этом этапе не обращается.

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

Swift — минимальная оболочка `ComposeUIViewController`, экраны остаются общими. Debug-only Xcode phase копирует canonical fixtures в app bundle; Release передаёт `demoEnabled = false` и не копирует JSON. На Windows эти iOS команды не выполнялись. Проверьте ресурсные пути, Swift bridge и запуск на Mac до утверждения iOS-готовности.
