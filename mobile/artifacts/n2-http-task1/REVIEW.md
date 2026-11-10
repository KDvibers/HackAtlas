# Закрытый N2. Задача 1

Ветка `feat/mobile-n2-http` от `c543eb0` после PR #33. Локальные решения и новые
разделы матрицы из `main` собраны вместе. Результат этапа — проверка HTTP-ответов
закрытого кандидата в существующем мобильном клиенте. [План](../../../docs/superpowers/plans/2026-11-07-mobile-closed-n2.md)

- `mobile/test-fixtures/n2/` содержит два JSON из переданного архива в исходном виде.
  `mobile/shared/build.gradle.kts` генерирует тестовые источники из этих файлов.
  `N2HttpFixturesTest.kt` воспроизводит все 127 ответов, проверяет заголовки, пустое
  тело 204 и индексы обеих пар delete-password.
- `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/auth/N2Responses.kt`
  проверяет флаг подтверждения и классифицирует ошибки по операции, статусу и коду.
  `AuthModels.kt` и `AuthSessionClient.kt` передают результат в режиме `CLOSED_N2`.
  Хосты используют прежний режим `LEGACY`. Две существующие ветки отображения ошибки
  получили обработку `UnsupportedServer` с прежним текстом некорректного ответа.
- `N2HttpResponsesTest.kt` проверяет чтение pending/verified, ошибку login при живой
  сессии и обе пары `401 INVALID_CREDENTIALS → GET /me 200` с одинаковым тестовым
  Bearer. Неверный protected 401 закрывает текущую сессию. Проверены девять pending
  guards, режим recovery только для ошибки login, `Retry-After` и 503.

Сначала целевые тесты выявили отсутствие интерфейсов N2. Дополнительное ревью
воспроизвело отказ на строковом `requestId` и очистку сессии при 401 на recovery с
ошибочно приложенным Bearer. Обе регрессии исправлены. Строковый `requestId` сверяется
с заголовком при его наличии, а 401 на recovery относится к этому потоку.

## Проверки

Из корня репозитория с JDK 21 и Android SDK 36:

```powershell
.\mobile\gradlew.bat -p mobile :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -X utf8 scripts/validate_contract.py
```

Gradle завершился успешно. Всего 282 теста, 0 ошибок и пропусков. Новые тесты — 2
в `N2HttpFixturesTest` и 12 в `N2HttpResponsesTest`. Lint без замечаний. Отдельно
проверено содержимое debug и release APK на отсутствие файлов и классов fixtures.
`validate_responses` подтвердил 127 записей и четыре связанных ответа по кандидату
`0.2.0-test.1`. Валидатор канонического API 0.1.1 прошёл. Gitleaks и `git diff --check`
прошли. Логи лежат в `mobile/build/n2-http-task1/`.

Проверка проводится по архиву в MockEngine. Подключение режима к хостам, нативный
прогон на стенде и iOS-приёмка входят в последующие задачи. Канонические
`api/openapi.yaml` и `api/README.md` сохраняют версию 0.1.1.

Напарнику можно передать следующее. «Мобильная задача 1 прошла. Все 127 ответов
воспроизводятся в MockEngine. Обе пары неверного пароля удаления сохраняют Bearer
для последующего `GET /me`. Следующая задача применит результат к поколениям
сессии, доступу и двум частным кешам».

## Ручной commit и PR

Выполнить из текущего worktree после просмотра изменений:

```powershell
git add -- docs/STATUS_MOBILE.md docs/superpowers/specs/2026-11-05-account-lifecycle-client-matrix.md docs/superpowers/plans/2026-11-07-mobile-closed-n2.md docs/research/2026-11-05-mobile-n2-compatibility-review.md
git add -- mobile/shared mobile/test-fixtures/n2 mobile/artifacts/n2-compat-review/verification.json mobile/artifacts/n2-http-task1/REVIEW.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): validate closed N2 HTTP responses"
git push -u origin feat/mobile-n2-http
```

PR в `main` с заголовком `feat(mobile): validate closed N2 HTTP responses`.
Описание. «Добавляет разбор профиля и HTTP-ошибок закрытого N2. Подключает 127
ответов к MockEngine и проверяет сохранение Bearer в обеих парах `401 → 200`.
Legacy-клиент остаётся режимом хостов. 282 unit-теста, Android build/lint и
валидация API 0.1.1 прошли».
[Открыть форму PR после push](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-n2-http?expand=1)
