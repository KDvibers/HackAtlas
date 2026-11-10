# Закрытый N2: задача 2

Ветка `feat/mobile-n2-session` основана на `3fb554d` из PR #35.
Изменения оставлены в рабочем дереве для коммита пользователя.

## Что изменилось

- `data/auth/AuthSessionClient.kt` и `ui/auth/AuthStore.kt` в `commonMain`
  связывают ответ с источником, владельцем и локальным поколением. Даже вход с
  теми же UUID и expiry закрывает старый доступ. Неудачный вход оставляет его.
- `AccountCache` и `ApplicationCache` сохраняют поколение вместе со снимком.
  Pending и холодный офлайн сохраняют файлы скрытыми. После `GET /me` с
  `emailVerified=true` потеря сети оставляет только чтение до expiry. Для
  возврата к записи снова нужен `GET /me`. Обе копии, календарь, личные
  отметки, профиль, редактор и импорт подчиняются этому правилу.
- Logout, текущий защищённый 401 и локальное expiry закрывают доступ и очищают
  сессию и обе копии. Если очистка прерывается, в защищённой записи остаётся
  маркер без исходного Bearer, а доступ закрыт до повтора. Публичные детали и
  гостевые закладки работают отдельно.

## Проверка

`N2PrivateAccessTest` проверяет холодный и тёплый офлайн, возврат через
`GET /me`, expiry без HTTP, pending через профиль и 403 трёх групп, прерванные
записи обоих файлов, поздние 200/204/401 после нового входа с теми же UUID и
expiry, регистрацию, неудачный login, смену владельца, изоляцию источника и
режима, очистку при отзыве, ошибку очистки и её повтор после перезапуска.
Тесты задачи 1 по-прежнему проверяют обе пары delete-password `401 → 200`.
До реализации три сценария падали на базе задачи 1. Первый общий прогон выявил
регрессию legacy при исчезновении Bearer. Она исправлена.

```powershell
.\mobile\gradlew.bat -p mobile :shared:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -X utf8 scripts/validate_contract.py
git diff --check
```

294 Android unit-теста, из них 12 новых, 0 ошибок и пропусков. Debug и release
собраны. Lint и проверка release на отсутствие demo fixtures прошли.
OpenAPI 0.1.1 и его примеры прошли валидатор. Gitleaks не нашёл
секретов в `mobile/shared/src`. Логи лежат в `mobile/build/n2-session-task2/`.

Нативная приёмка N2 на Android-стенде и iOS не проводилась. Обычные хосты
по-прежнему выбирают legacy. Канонический API, сервер и инфраструктура не
изменились. Подтверждение почты и recovery идут следующими задачами.

Напарнику: «Задача 2 прошла в MockEngine. Оба кеша закрыты при pending и
холодном офлайн, а поздние ответы старого поколения не меняют новую сессию.
Поведение legacy 0.1.1 сохранилось».

## Ручной commit и PR

```powershell
git add -- docs/STATUS_MOBILE.md docs/superpowers/plans/2026-11-07-mobile-closed-n2.md mobile/artifacts/n2-session-task2/REVIEW.md
git add -- mobile/shared/src/commonMain mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/auth/N2HttpResponsesTest.kt mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/ui/N2PrivateAccessTest.kt
git diff --cached --check
git commit -m "feat(mobile): scope closed N2 private data to session generations"
git push -u origin feat/mobile-n2-session
```

Перед отдельным PR в `main` обнови `origin/main` через `git fetch origin main` и проверь, что [PR #35](https://github.com/KDvibers/HackAtlas/pull/35)
влит. На момент проверки GitHub показывал его открытым, поэтому ветка пока
включает задачу 1. Если #35 будет влит через squash, после локального коммита
перенеси только коммит задачи 2 командой
`git rebase --onto origin/main 3fb554d feat/mobile-n2-session` перед первым push.
