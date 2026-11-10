# Закрытый N2. Задача 3

Ветка `feat/mobile-n2-verification` от `1f381a5` после PR #37.

## Результат

- `data/auth/N2AccountClient.kt` и `AuthSessionClient.kt` отправляют запрос
  кода и подтверждение с Bearer конкретного поколения. `202` открывает форму
  кода. После `204` клиент сверяет `GET /me`. Частные закладки и отметки
  заявок открываются при `emailVerified=true` в той же сессии.
- `ui/auth/EmailVerificationStore.kt` соблюдает TTL, паузу перед новым
  запросом и `Retry-After`. После потерянного confirm выполняется сверка
  профиля. При потере ответа на запрос кода перед повтором выдерживается
  пауза. После `404/501` повтор блокируется для этой сессии.
- `EmailVerificationScreen.kt`, `AccountProfileScreen.kt`, `App.kt` и
  `CatalogRuntime.kt` подключают экран к pending-входу и профилю. Код
  очищается при уходе приложения в фон. Ввод и challenge существуют в RAM.

## Проверка

```powershell
.\mobile\gradlew.bat -p mobile :shared:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -X utf8 scripts/validate_contract.py
git diff --check
```

Прошли 306 Android unit-тестов, из них 12 новых, без ошибок и пропусков.
Покрыты ведущий ноль, истечение TTL перед отправкой, `429`, потерянные
ответы, background, поздний ответ после logout и открытие обеих копий после
`GET /me`. TCP-тест на Android воспроизвёл повтор POST после
`503 Retry-After: 0` и подтвердил один запрос после исправления.

Android debug и release собраны. Lint, проверка release на отсутствие demo
fixtures, валидатор API 0.1.1 и Gitleaks для `mobile/shared/src` прошли.
Логи находятся в `mobile/build/n2-verification-task3/`.

На эмуляторе Android API 36 проверен маршрут pending-входа, запрос кода и
возврат в профиль после `confirm 204 → GET /me 200` через локальный
синтетический HTTP-сервер. В ходе прогона проверка статуса восстановила поток
после сетевого сбоя. Снимки [запроса кода](screenshots/request-code.png) и
[формы ввода](screenshots/enter-code.png) сделаны в отдельном preview APK.

Канонический контракт — API 0.1.1. Проверка с реальным стендом N2 и сборка iOS
входят в последующие этапы плана.

Напарнику: «Task 3 подключает подтверждение почты. Для Android-приёмки нужен
pending-аккаунт и Mailpit. Ожидаем `request → confirm → GET /me` с одним
Bearer, после чего обе частные копии становятся доступны».

## Ручной commit и PR

```powershell
git add -- docs/STATUS_MOBILE.md docs/superpowers/plans/2026-11-07-mobile-closed-n2.md mobile/artifacts/n2-verification-task3 mobile/shared/src
git diff --cached --check
git commit -m "feat(mobile): connect closed N2 email verification"
git push -u origin feat/mobile-n2-verification
gh pr create --base main --head feat/mobile-n2-verification --title "feat(mobile): connect closed N2 email verification"
```
