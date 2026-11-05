# M3: личные отметки заявок

5 ноября 2026. Ветка `feat/mobile-application-marks`, база `c1c3718` (PR27).
Commit/push/PR выполняет пользователь. Android-приёмка завершена: **10/10 PASS**.

## Результат

В подробностях события есть личная отметка, дата и снятие отметки. Сервер
подтверждает изменения до публикации результата. Вход гостя возвращает к событию;
сам вход, закладка и сайт организатора отметку не создают. Общий Compose UI/store
подключён на Android/iOS с прежним дизайном. Новых шрифтов, assets или зависимостей нет.

Своя копия доступна после холодного старта без сети, с датой/предупреждением;
изменения недоступны. Токен остаётся в secure store. Отдельные APPLICATIONS paths
не меняют saved/guest schema. Logout/смена аккаунта/401 очищают private state;
поколения запросов и атомарная запись защищают от поздних ответов и гонок.
Подтверждённый сервером результат не откатывается при ошибке диска/обновления профиля.

Очередь, отдельный список заявок, напоминания и email lifecycle не добавлены.
Starter API0.1.1, opaque sessions, backend/infra/CI не меняются.

## Основные файлы

- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/auth/ApplicationMarks.kt`
  и `AuthSessionClient.kt`: DTO/GET/PUT/DELETE.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/applications/`:
  отдельный cache; `ui/applications/`: store и Compose control.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/{App.kt,ui/CatalogRuntime.kt,ui/DetailScreen.kt}`:
  навигация, ownership и подключение. Android/iOS hosts и account-cache storage
  получают отдельный `PrivateCacheKind.APPLICATIONS`.
- `mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/`: ApplicationClientTest,
  ApplicationCacheTest, ApplicationStoreTest, ApplicationVisibilityTest.
- `mobile/scripts/android_application_marks_smoke.py`: воспроизводимая native-приёмка
  с отказом для чужого AVD/DB и существующих private files.

## Проверки

- Shared: **268 тестов, 0 failures/errors/skipped**, включая 32 новых.
- Android debug build, lint (0 issues), release-no-demo gate: PASS.
- Canonical contract validator: PASS; backend offline package:31unit tests PASS.
  Backend verify/IT повторно не запускался: сервер не менялся.
- Gitleaks8.30.1: history `--all` и финальный снимок59изменённых/новых файлов —0находок.
- Один независимый read-only source review: **0 Critical / 0 Important / 0 Minor**.
  Review предшествовал native; это разные виды проверки.
- Native helper:9 selfchecks/regressions PASS. Воспроизведены и исправлены
  коллизия унаследованного имени HTTP и определение enabled у Compose-кнопки по
  реальным XML. Изменения helper не меняли APK/код приложения.

Из корня, JDK21/SDK36:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME='C:/Users/user/AppData/Local/Android/Sdk'
.\mobile\gradlew.bat -p mobile :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8774/api/v1/' '-Phackatlas.allowDebugHttp=true'
```

Полный вывод: ignored `mobile/build/m3-applications/full-final-verification.log`.
После этой сборки Kotlin/resources не менялись; на native проверялся тот же APK.
SHA установленного APK совпал с замороженным файлом:
`015395af1dc7b092b915d7203bcc908ba575feabb91dcb25964fd6628ec9f156`.
APK: `mobile/build/m3-applications/HackAtlas-applications-debug.apk`.
Это REMOTE debug для временного B2:8774; после остановки стенда сервер нужно
поднять заново или пересобрать APK с URL своего backend. Release/deployment вне задания.

## Android: один полный успешный прогон

Настоящий локальный B2:8774, одноразовая PostgreSQL:5551, два synthetic accounts
и пять DEMO-событий. Android16/API36,2GB/4cores, GPU host NVIDIA RTX4050,
Vulkan disabled. Скрипт `mobile/scripts/android_application_marks_smoke.py`.

1. Гостевая закладка; вход из detail возвращает событие без автоматической отметки.
2. Официальный DEMO-preview не создаёт отметку и не отправляет заявку организатору.
3. Явная отметка: UI, SQL, cache и API подтверждают APPLIED/selfReported/markedAt;
   серверный счётчик профиля равен1.
4. Повторный host PUT не меняет markedAt; после process restart дата прежняя.
5. Account bookmark add/remove независимы от отметки; guest bytes прежние.
6. Холодный offline Saved/detail читает свой cache; изменение отключено, bytes/SQL прежние.
7. После сети снятие подтверждается сервером, UI и пустым cache (клиент принимает DELETE204).
8. Logout/loginB не показывает и не импортирует отметку аккаунтаA.
9. Readback1080px/480dpi=360dp,font_scale1.3; текст и кнопка доступны.
10. SQL отзывает сессии тестовогоB; настоящий401 убирает credential и обе private-копии,
    гостевые данные не меняются.

`failure=null`, `restoreErrors=[]`, fresh observation retries0. После завершения
отдельно перечитаны реальные display/network/font settings, guest bytes и отсутствие
private files: `android-restoration.json`. Пять host HTTP-ответов проверены по схеме
текущего OpenAPI, без сохранения токенов/паролей. Даты markedAt в dev-стенде идут от
фиксированного серверного Clock12октября; дата копии — время устройства5ноября.

[Галерея](review.html):10 настоящих screenshots. Просмотрены ключевые состояния,
включая offline,360dp/font130 и revoked. Верх блока может уходить за край при
прокрутке; контент и действия доступны прокруткой. Нового визуального направления нет.

## Прежние попытки и окружение

`android-history.json` сохраняет4 неуспешные попытки: коллизия helper до HTTP;
два аварийных завершения qemu на Windows (`0xc0000005`, software renderer);
неверный class-only probe disabled-кнопки в helper. Ошибки восстановления при потере
эмулятора не скрыты. До следующего прогона восстановлены исходные settings/guest
bytes и удалены только private files своего test-source на отдельном AVD.

Успешный прогон использует аппаратную графику. Флаг отключения Vulkan описан в
[официальном troubleshooting Android Emulator](https://developer.android.com/studio/run/emulator-troubleshooting).
Причина падения эмулятора не объявляется исправлением кода приложения; software
renderer не принят. Физическое устройство/TalkBack и iOS native не проверены.
На Windows iOS не собирался; Mac/iPhone остаются обязательной отдельной проверкой.

Временные backend/эмулятор остановлены, labelled PostgreSQL удалена. Очистку
локальных временных файлов автоматическая проверка отклонила без конкретной причины:
AVD/registry и generated env остаются остановленными/неиспользуемыми в ignored
каталоге; чужие AVD не менялись. Точный cleanup status — environment.json.

## Передача

[PR_DESCRIPTION.md](PR_DESCRIPTION.md), [COMMIT_PR.md](COMMIT_PR.md),
[verification.json](verification.json), [final-review.json](final-review.json).

Напарнику: «Личные отметки подключены по текущему API0.1.1; контракт и сервер не
менялись. Изменения подтверждает сервер, offline — только своя копия.268shared и
10/10Android native PASS, включая смену аккаунта/отзыв401/360dp-font130. iOS native
остаётся отдельной проверкой; очередь/напоминания не добавлены».

Следующий шаг — пользовательский commit/push/PR и ревью напарника. К новым блокам
M3 без отдельного задания не переходить.
