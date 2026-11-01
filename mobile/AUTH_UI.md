# M3 block 2: авторизация в приложении

Этот документ фиксирует границу второго блока. Последующий блок закладок аккаунта,
private offline cache и подтверждённого guest-copy описан в [ACCOUNT_SAVED](ACCOUNT_SAVED.md).
Там обновлено поведение saved/календаря после входа; auth screens остаются прежними.

Общий Compose UI использует готовый [auth core](AUTH_CORE.md). `CatalogRuntime`
владеет одним AuthSessionClient/AuthStore для фиксированного REMOTE API URL;
Android Application и iOS process runtime передают Keystore/Keychain factories.
Публичный каталог имеет отдельный HTTP client без Bearer. DEMO не создаёт auth.

Вкладка «Профиль» предлагает вход/регистрацию. После входа показан настоящий
ProfileDto: имя, email, выбранный avatarKey, сведения/интересы и серверные счётчики.
Редактирование профиля в этот блок не входит. Вход не блокирует каталог.

Сохранённое и календарь по-прежнему гостевые, локальные, разделённые по API URL.
В профиле они подписаны отдельно от серверного savedCount. Вход/выход/смена
аккаунта не импортируют, не синхронизируют и не удаляют эти файлы. Единственные
данные аккаунта вне secure store — профиль в памяти AuthStore; отдельного
persisted private cache нет. При logout/401/storage failure он скрывается.

## Поведение

- Формы используют обычный `remember`, без `rememberSaveable` для имени/email/
  пароля. Секреты не входят в маршруты, AuthUiState или логи. На принятом submit
  пароль очищается из поля; при смене формы/уходе и background очищаются все поля.
  Background revision доставляется даже после паузы Compose. Начатый запрос
  принадлежит процессу и продолжает работу после закрытия/пересоздания экрана.
- Повторный submit во время операции отклоняется. Ввод пароля не trim. Вход:
  1..256 символов; регистрация: 12..256, имя 1..60, email до 254. Клиент даёт
  подсказки, окончательная валидация остаётся серверу.
- Restore не блокирует гостевой каталог/хранилище. Ошибка сети не означает, что
  сохранённой сессии нет: доступны повторная проверка или явный выход.
- При foreground/открытии профиля выполняется GET `/me`; `refresh()` в AuthStore
  означает эту проверку, не обновление токена. JWT/refresh endpoint отсутствуют.
  Expiry/401 требуют повторного входа; пароли для автологина не хранятся.
- Logout требует подтверждения, скрывает прежний профиль сразу. Подтверждённый
  сервером отзыв и локальный выход без подтверждения показаны разными сообщениями.
  Ошибка удаления credential показывает storage error и не объявляет успех.
- Сообщения 400/401/403/409/429/timeout/network/storage фиксированы на клиенте;
  raw body, requestId, token и stack trace не показываются пользователю.

## Android: сборка для локальной проверки

Из `mobile/`, JDK21 и SDK36:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8770/api/v1/' '-Phackatlas.allowDebugHttp=true'
```

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Для своего backend
измените debug URL/порт. HTTP разрешён только выбранному dev host; release — HTTPS.
Регистрация в private dev должна быть явно включена по backend/README.md.

Автоматический smoke `scripts/android_auth_smoke.py` предназначен только для
одноразовой среды: PostgreSQL-контейнер `hackatlas-m3-auth-ui-db`, отдельный B2 на
8770 и emulator-5554. Он создаёт synthetic `example.org` аккаунты, проверяет
GET `/me`, отзывает только их сессии SQL-запросом, моделирует отсутствие сети,
восстанавливает display/network/guest bytes. Credentials остаются в памяти.
Не запускайте его против пользовательской БД или production. Скрипт отказывается
работать с уже существующим credential для своего API scope.

## iOS и ограничения

Shared формы/состояния и Keychain factory подключены к MainViewController;
SwiftUI scenePhase передаёт foreground/background (single-argument onChange для iOS16).
UIApplicationDelegate оставлен только для завершения runtime. Сборка, Swift interop,
реальный Keychain и UI требуют проверки на Mac/Xcode. На Windows они не проверены.
iOS REMOTE по-прежнему требует HTTPS; ATS исключения не добавлялись.

Следующий отдельный блок: согласованная saved sync/подтверждаемый guest import.
Профиль edit, account deletion, reminders и public launch также не входят сюда.
