## Этап и изменение

M3 profile edit: общий Compose editor для displayName, city, countryCode, roleLabel,
avatarKey и interests. Email/permissions/counters read-only. Без application marks,
reminders, email verification/reset или загрузки фотографий.

PATCH `/me` отправляет изменённые поля: absent сохраняет старое значение, null
очищает nullable text, interests заменяется полным выбранным набором. Metadata
failure не очищает прежние интересы. Draft только в process memory, cancel/reload
подтверждаются, duplicate save блокируется, logout/owner change очищают draft.

## Проверка

Прежний unit/build/lint/release прогон —2ноября. В приёмке3ноября APK и код приложения
не менялись; эти проверки повторно не запускались. Ниже их команда и результат.

Из `mobile/`, JDK21/SDK36:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8772/api/v1/' '-Phackatlas.allowDebugHttp=true'
```

**228 tests PASS (17 новых), 0 failures/errors/skipped; Android build/lint0/
release-no-demo gate PASS.** Starter validator +6fixtures PASS.
Backend package BUILD SUCCESS/21unit tests, не повторный backend verify/IT.

Новые тесты проверяют PATCH exact URL/header/owner/body, absent/null, allowlist,
401/400/invalid200, Unicode code point limits, no-change/cancel, duplicate save,
network retry, logout during PATCH и foreground draft. Review-регрессии воспроизвели
applied-response-lost и schema-invalid200 до исправлений, затем GREEN/full suite.

**Android-приёмка3ноября:6/6 PASS в одном полном запуске** на Android16/API36,
2GB,4cores,swiftshader,tzdata2025a. SHA установленного APK совпал с проверенной
сборкой e95ba0373662b5172f98953b68cadd961fa386170e6ec0d95ea66a2d4a1cd6e4.

1. Dirty cancel/продолжение редактирования без PATCH.
2. Шесть полей, подтверждение в БД, восстановление после restart.
3. Nullable clear, пустые интересы и смена аватара.
4. Offline ошибка сохраняет draft, явный online retry успешен.
5. Same-owner draft сохраняется на foreground; отозвана1active session,
   сообщение входа показано, draft/credential/cache очищены, guest bytes прежние.
6. Readback1080px/480dpi=360dp,font_scale1.3, Save и валидация пустого имени доступны;
   профиль на сервере не изменился.

Failure=null, restoreErrors=[], свежих повторов наблюдения0. Реальный локальный
B2 и одноразовая PostgreSQL, synthetic accounts/DEMO; credentials в artifacts нет.
Smoke helper добавляет SQL/readback доказательства и надёжный false-отчёт при потере
device. Максимум1повтор свежего read-only XML-наблюдения на весь прогон, без повторов
input/HTTP/SQL или изменения таймаутов. Два harness selfcheck PASS; в финальном run
повтор не понадобился. Отчёты android-acceptance.json/android-smoke.json, устройство
android-device.json, прежние неудачные попытки android-history.json.

## Интерфейс

M1 black/magenta/cyan, pixel heading, шрифты/artwork/четыре вкладки сохранены.
Новых fonts/assets/dependencies нет. Все текущие screenshots сняты на финальном
APK3ноября, включая360dp/font130, ошибку имени и сообщение после отзыва.
Галерея review.html; изображения просмотрены исполнителем, текст/ошибка читаемы.

## API и данные

Starter OpenAPI/README/examples не меняются, миграций нет. Expected owner сверяется
под прежним auth mutex; response Profile валидируется до публикации. Токен остаётся
в secure store, editor не пишет profile/draft в Bundle/settings/cache. Bookmarks/
guest-copy/calendar схемы и файлы не меняются.

`message(18).txt`/PR22 — unapproved lifecycle proposal, не готовый backend/API.
Подготовлено только mobile review + текст для ручной передачи в BACKEND_DRAFT_REVIEW.md.
Его JSON patch, emailVerified/pending/reset/SMTP не применялись.

## Риски / ограничения

- Один независимый review:0Critical/2Important/0Minor, оба исправлены одним проходом.
  Неопределённый результат сохраняет sent-fields до ack/reload: retry учитывает
  возвращённое старое значение. Schema-invalid200 не закрывает editor как success.
- Уже отправленный PATCH может завершиться после Back: закрытие UI его не откатывает.
  Потерянный ответ не доказывает отсутствие server update, UI сообщает это явно.
- Native регрессия с исчезновением expired-notice воспроизведена отдельным unit RED:
  route refresh заменял SignInRequired на SignedOut. Исправлена, full228 GREEN;
  подтверждено реальным отзывом сессии в полном Android-прогоне3ноября.
- Отдельный открытый вопрос: конкретный API34 образ с tzdata2023a отвергает валидный
  MINUTE для Алматы (старыйUTC+6). API34_TZDATA.md содержит воспроизведение и IANA.
  Проверки дат/fixtures не ослаблялись;6/6 относится к API36, не ко всем версиям ОС.
- iOS native build/Swift/Keychain/UI не выполнены на Windows; Mac/iPhone обязательны.
  TalkBack/физическое устройство отдельно не приняты.
- APK dev8772 требует своего запущенного B2/подходящего URL. Временная среда тестов
  после проверок остановлена; временный AVD в Корзине. Signing/release/public launch вне scope.

## Сообщение напарнику

Android-профиль6/6PASS на API36, включая401 и360dp/font130. Старыйtzdata риск отдельно.
Profile edit подключён по прежнему PATCH /me, контракт unchanged, opaque Bearer
сохранён. Клиент учитывает absent/null/interests и неопределённый ответ, не трогает
guest saved. Draft lifecycle proposal посмотрели; готовый mobile feedback отдельно,
реализацию verification/reset не начинали. Для iOS acceptance нужен Mac/HTTPS.
