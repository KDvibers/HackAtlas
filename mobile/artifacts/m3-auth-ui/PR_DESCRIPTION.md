## Этап и изменение

M3 block 2: вход/регистрация и настоящий профиль подключены к общему Compose UI
и Android/iOS hosts. Каталог доступен без входа. Один process-owned AuthStore и
opaque-session client на REMOTE source переживают пересоздание экрана.

Добавлены валидация/маскирование пароля, защита от повторного submit, restore,
повторный вход после expiry/401, обновление профиля и подтверждаемый logout.
Формы не сохраняют credentials в Bundle/settings; background очищает поля.
Ошибки restore/storage и локальный выход без подтверждённого server revoke
показаны явно. Сохранённое/календарь остаются локальными гостевыми данными.

## Проверка

Из `mobile/`, JDK21/SDK36:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8770/api/v1/' '-Phackatlas.allowDebugHttp=true'
```

**BUILD SUCCESS, 188 тестов (15 новых auth UI), 0 failures/errors/skipped, lint0,
release-no-demo gate PASS.** Полные результаты — `verification.json` рядом
с этим описанием. Starter schema/6 examples прошли validator.
Актуальный backend собран `mvnw.cmd --batch-mode --no-transfer-progress package`
(21 unit test PASS; это не повторный backend verify/integration suite).

Живой Android→B2 smoke: **7 групп PASS**, Pixel8/API37, реальный local dev JAR,
отдельная PostgreSQL с DEMO-событиями и одноразовыми synthetic аккаунтами.
Проверены register/login/wrong password, process restart/GET me, server logout,
реальный revoked401 при foreground, второй аккаунт, offline logout, сохранность
guest bytes, background clearing и normal/narrow/large-text/wide формы.
`android-smoke.json`: fullAcceptance=true, restoreErrors=[].

Один независимый reviewer: 0 Critical / 1 Important / 1 Minor. Important исправлен
в исходниках: SwiftUI scenePhase вместо недоставляемых AppDelegate scene callbacks.
Native iOS проверка после исправления остаётся открытой. Детали в REVIEW.md.

## Интерфейс

Сохранены M1 палитра, пиксельные заголовки, шрифты, иллюстрации, четыре вкладки.
Новых ассетов/шрифтов нет. Реальный профиль отображает ProfileDto/server stats;
локальные закладки подписаны отдельно и не подменяют серверный savedCount.

Скриншоты: `mobile/artifacts/m3-auth-ui/screenshots/`; обзор — `review.html`.
Основные: login.png, register.png, profile.png. Есть 360dp, font130%, 432dp и
ошибки/offline/repeat-login. В GitHub PR можно приложить основные PNG из каталога.

## API и данные

OpenAPI/README/endpoint semantics не меняются, миграций нет. JWT/refresh flow
не добавлены. Public catalogue HTTP client по-прежнему без Bearer. Android
Keystore/iOS Keychain factories используют прежний source-scoped auth core.
Private profile хранится только в памяти и скрывается при logout/401/storage failure.
Guest snapshots не импортируются, не синхронизируются, не удаляются при входе/выходе.

## Риски / ограничения

- iOS build/Swift interop/Keychain/lifecycle/UI не выполнены на Windows; нужен Mac/iPhone.
- Minor: после submit очищается пароль, имя/email остаются для retry, хотя spec
  шире описывает очистку всех значений. Background/mode/disposal очищают всё.
- Saved sync/import, profile edit, reminders/deletion/reset/verification и public launch
  не входят в этот блок. Backend production readiness не заявляется.
- APK smoke настроен на dev port8770. Временная среда остановлена; для своей проверки
  нужен доступный B2 и соответствующий debug URL. Credentials в artifacts отсутствуют.

## Сообщение напарнику

Подключили M3 auth UI по текущему Starter: register/login/logout/me, opaque Bearer,
без JWT/refresh. Живые Android→B2 сценарии прошли на отдельной dev DB. Контракт
не менялся; saved пока локальные, sync/import отдельно. Для iOS-приёмки нужен Mac
и доступный HTTPS API. Commit/push/PR выполняет владелец mobile-ветки.
