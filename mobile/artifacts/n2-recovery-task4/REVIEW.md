# Закрытый N2. Задача 4 — восстановление пароля

Ветка `feat/mobile-n2-password-recovery` от `19ad561` после PR #42.

## Результат

- `N2AccountClient.kt` и `AuthSessionClient.kt` подключают три публичных
  POST для запроса, проверки и установки нового пароля. Grant используется
  только в теле confirm. Публичный `401` сохраняет активную чужую сессию.
- `PasswordRecoveryStore.kt` учитывает TTL и общий `Retry-After` для
  verify и confirm. После неопределённого verify нужен новый код. После
  неопределённого confirm пользователь может попробовать обычный вход
  или заново начать восстановление.
- `PasswordRecoveryScreen.kt`, `AuthScreen.kt`, `App.kt` и
  `CatalogRuntime.kt` подключают вход из «Забыли пароль?» и после
  `PASSWORD_RESET_REQUIRED`. Ответ `204` возвращает на обычный вход.
  Проверки длины пароля учитывают Unicode code points и пробелы.
- Введённые код и пароль очищаются при уходе в фон. Ещё действующий
  challenge остаётся доступен после возврата из почты, если verify
  не был отправлен. Закрытие формы инвалидирует поколение попытки.

## Проверка

```powershell
.\mobile\gradlew.bat -p mobile :shared:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -X utf8 scripts/validate_contract.py
git diff --check
```

Прошли 317 unit-тестов, из них 11 новых, без ошибок и пропусков. MockEngine
проверил публичные заголовки и тела, пробелы и Unicode, expiry grant,
потерю verify/confirm, `429` между стадиями, `503`, поздний ответ после
background и возврат приложения на передний план. TCP-тест Android
подтвердил одну отправку verify и confirm при `503 Retry-After: 0`.
Android debug и release собраны. Lint, проверка release на отсутствие
demo fixtures, валидатор API 0.1.1 и Gitleaks для `mobile/shared/src` прошли.

На Android API 36 с синтетическим сервером пройден маршрут
`PASSWORD_RESET_REQUIRED → request 202 → verify 200 → confirm 204`.
После возврата из фона challenge сохранился, введённый код очистился.
После `204` открылся экран входа, в журнале сервера новых login-запросов
не появилось. Снимки: [email](screenshots/request-code.png),
[код](screenshots/enter-code.png), [пароль](screenshots/new-password.png),
[возврат ко входу](screenshots/back-to-login.png).

Реальный стенд N2 и iOS входят в последующие этапы плана. Канонический
контракт — API 0.1.1. Логи лежат в `mobile/build/n2-recovery-task4/`.

Напарнику: «Task 4 подключает публичный reset в закрытом N2. На реальном
стенде проверить письмо в Mailpit, `202 → 200 → 204` без Bearer и обычный
вход с новым паролем».

## Ручной commit и PR

```powershell
git switch feat/mobile-n2-password-recovery
git add -- docs/STATUS_MOBILE.md docs/superpowers/plans/2026-11-07-mobile-closed-n2.md mobile/shared/src mobile/artifacts/n2-recovery-task4
git diff --cached --check
git commit -m "feat(mobile): connect closed N2 password recovery"
git push -u origin feat/mobile-n2-password-recovery
gh pr create --base main --head feat/mobile-n2-password-recovery --title "feat(mobile): connect closed N2 password recovery" --body-file mobile/artifacts/n2-recovery-task4/PR_DESCRIPTION.md
```
