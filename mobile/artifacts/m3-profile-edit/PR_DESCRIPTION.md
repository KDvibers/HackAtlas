## Этап и изменение

M3 profile edit: общий Compose editor для displayName, city, countryCode, roleLabel,
avatarKey и interests. Email/permissions/counters read-only. Без application marks,
reminders, email verification/reset или загрузки фотографий.

PATCH `/me` отправляет изменённые поля: absent сохраняет старое значение, null
очищает nullable text, interests заменяется полным выбранным набором. Metadata
failure не очищает прежние интересы. Draft только в process memory, cancel/reload
подтверждаются, duplicate save блокируется, logout/owner change очищают draft.

## Проверка

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

**Полная нативная приёмка открыта.** Прежний UI APK прошёл4/6 групп Android→B2:
cancel, шесть полей/restart, nullable/interests clear, offline/retry. После последней
auth-only правки финальный APK прошёл unit/build checks, установленный SHA проверен,
но native preflight остановлен отсутствующим phone service. На отдельном API36 AVD
системные startup ANR phone/SystemUI сохранились после одного warm reboot.
Прежние4PASS не засчитаны финальному APK. Повторить все6групп на здоровом Android.
Итог — `android-acceptance.json`, история/окружение отдельно, SHA/build — verification.
Synthetic account/DEMO events, guest bytes/display восстановлены; при ошибке команды
restore phone сетевые settings дополнительно сверены readback. Credentials не в artifacts.

## Интерфейс

Сохранены M1 black/magenta/cyan, pixel heading, шрифты/artwork/четыре вкладки.
Общие реальные text fields, avatar/interest chips, IME scrolling и cancel confirmation.
Новых fonts/assets/dependencies нет. Screenshots в `mobile/artifacts/m3-profile-edit/`;
обзор `review.html`. Normal screenshots с прежнего UI APK; финальная правка меняла
только auth restore. 360dp/font130 и revoked-notice screenshots ещё не приняты.

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
  подтверждение на Android входит в оставшуюся нативную приёмку.
- iOS native build/Swift/Keychain/UI не выполнены на Windows; Mac/iPhone обязательны.
  TalkBack/физическое устройство отдельно не приняты.
- APK dev8772 требует своего запущенного B2/подходящего URL. Временная среда тестов
  после проверок остановлена; временный AVD в Корзине. Signing/release/public launch вне scope.

## Сообщение напарнику

Profile edit подключён по прежнему PATCH /me, контракт unchanged, opaque Bearer
сохранён. Клиент учитывает absent/null/interests и неопределённый ответ, не трогает
guest saved. Draft lifecycle proposal посмотрели; готовый mobile feedback отдельно,
реализацию verification/reset не начинали. Для iOS acceptance нужен Mac/HTTPS.
