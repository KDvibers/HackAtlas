# Mobile Closed N2 Implementation Plan

> После поручения на реализацию использовать `superpowers:executing-plans`. Выполнять задачи последовательно. Отмечать пункт после его проверки. Commit, push и PR выполняет пользователь.

**Цель** — добавить подтверждение почты и восстановление пароля в отдельную тестовую сборку HackAtlas для закрытого N2.

**Архитектура** — режим доступа задаётся при создании `CatalogRuntime`. Клиент авторизации ведёт идентификатор сессии, а общий `AuthStore` определяет доступ к экранам и двум частным кешам. Verification использует текущую сессию. Recovery использует публичный транспорт и отдельный контекст в памяти.

**Стек** — существующие Kotlin Multiplatform, Compose Multiplatform, Ktor, kotlinx.serialization, Android Keystore и iOS Keychain. Проверки HTTP через MockEngine.

**Спецификация** — разделы 1–9 матрицы закрытого N2 и проверенный архив из 127 HTTP-ответов. [Матрица](../specs/2026-11-05-account-lifecycle-client-matrix.md) · [Ревью и проверка архива](../../research/2026-11-05-mobile-n2-compatibility-review.md)

## Границы и решения перед стартом

- Действующий контракт — `api/openapi.yaml` и `api/README.md` версии 0.1.1. Набор N2 проверяется по `0.2.0-test.1`. Opaque Bearer и существующее безопасное хранилище остаются основой обоих режимов.
- Пользователь утвердил два правила для N2. Pending скрывает обе частные копии, сохраняет их и Bearer, блокирует редактор, записи и импорт. После холодного запуска копии открываются только после успешного `GET /me` с `emailVerified=true` для текущей сессии. Потеря сети после проверки в том же процессе позволяет читать свою копию до expiry. Существующее офлайн-поведение legacy 0.1.1 покрыть регрессией.
- Для закрытого N2 согласованы signed-out recovery без `Authorization` и переход к обычному login после reset. Каждая успешно принятая login/register-сессия получает новое локальное поколение. Неудачный login сохраняет текущую сессию. Поздние ответы сверяются с источником, владельцем и поколением. Recovery ведёт отдельное поколение попытки.
- Пример реализации изоляции — отдельный идентификатор приложения для debug N2 на каждой платформе. Хранилища внутри сборки по-прежнему разделяются по источнику и владельцу. Закрытый режим задаётся явным флагом и валидным REMOTE URL. Release использует legacy, а iOS требует HTTPS согласно действующей конфигурации.
- Экран удаления аккаунта, напоминания и формы B3 идут отдельными задачами. Обе пары delete-password входят в проверку правил ошибок. Согласование общего strict rollout включает миграцию старых аккаунтов без адреса доставки и план обновления клиентов. [Отчёт N2](../../research/2026-11-05-mobile-n2-compatibility-review.md)
- Общие экраны используют утверждённые компоненты и ресурсы M1. Android-приёмка проходит на API 36 у бэкендчика. iOS проверяется на Mac с закрытым HTTPS-стендом. [Дизайн](../../STYLE_LOCK.md) · [Стенд Android](../../../infra/lifecycle-test/WINDOWS_ANDROID.md)

## Точки подключения

Все пути ниже относительно корня `mobile`. Для Kotlin-файлов используется пакет `dev.hackatlas.mobile`.

| Ответственность | Файлы |
|---|---|
| Тестовые ответы и сценарии | `test-fixtures/n2/all-responses.json`, `test-fixtures/n2/delete-password-same-session.json`, `shared/build.gradle.kts`, новый `data/auth/N2HttpFixturesTest.kt` в `commonTest` |
| Режим, декодирование, транспорт | `domain/CatalogConfiguration.kt`, `data/auth/AuthSessionClient.kt`, `data/auth/AuthModels.kt`, новые `data/auth/N2AccountClient.kt` и `data/auth/N2Responses.kt` в `commonMain` |
| Доступ и хранилища | `ui/auth/AuthStore.kt`, `ui/saved/AccountSavedStore.kt`, `ui/applications/ApplicationStore.kt`, `ui/profile/ProfileEditStore.kt`, `data/saved/AccountCache.kt`, `data/applications/ApplicationCache.kt` |
| Маршруты и процессы | `App.kt`, `ui/AppState.kt`, `ui/CatalogRuntime.kt`, `ui/auth/AuthScreen.kt`, новые `ui/auth/EmailVerificationStore.kt`, `PasswordRecoveryStore.kt`, `EmailVerificationScreen.kt`, `PasswordRecoveryScreen.kt` |
| Платформенные настройки | `androidApp/build.gradle.kts`, `androidApp/src/main/kotlin/dev/hackatlas/mobile/HackAtlasApplication.kt`, `shared/src/iosMain/kotlin/dev/hackatlas/mobile/MainViewController.kt`, `iosApp/iosApp/HackAtlasApp.swift`, `iosApp/iosApp/Info.plist` |

Общие исходники в таблице расположены в `shared/src/commonMain/kotlin/dev/hackatlas/mobile/`, а общие тесты — в `shared/src/commonTest/kotlin/dev/hackatlas/mobile/`.

## Проверки, которые определяют разбивку

| Риск | Проверка |
|---|---|
| Некорректный `emailVerified` открывает частный доступ | Задачи 1 и 2. Отдельно проверить отсутствие поля, `null`, строку, `false` и `true` |
| Неверный пароль удаления воспринимается как отзыв сессии | Задача 1. Обе архивные пары `401 → 200` и контрольный `401 AUTH_REQUIRED` |
| Новый вход получает поздний `401`, `200` или `204` старой сессии | Задача 2. Совпадающие `userId` и `expiresAt`, разные поколения |
| Офлайн или истечение срока оставляют частные данные на экране | Задача 2. Холодный запуск, потеря сети после проверки, истечение срока без нового запроса, ошибка очистки |
| Потеря ответа или background повторно открывают использованный proof | Задачи 3 и 4. Поздний ответ, отмена, повторное нажатие и неизвестный исход каждого POST |

## Блок 1. Ответы N2 и правила HTTP

### Задача 1. Тестовый набор и обработка ответов

**Файлы** — оба JSON в `mobile/test-fixtures/n2/`, `mobile/shared/build.gradle.kts`, `data/auth/N2Responses.kt`, `data/auth/AuthSessionClient.kt` и `data/auth/AuthModels.kt`. Тесты в `data/auth/N2HttpFixturesTest.kt` и `data/auth/AuthSessionClientTest.kt`.

**Результат** — тестовый набор `N2HttpFixtures` отдаёт ответ по индексу и обе связанные последовательности. Декодер закрытого режима различает отсутствие `emailVerified`, невалидный тип и корректный boolean. Правило обработки принимает метод, путь, HTTP-статус и проверенный `Problem.code` и возвращает действие для текущего запроса.

- [x] Импортировать два JSON из `HackAtlas_N2_HTTP_Examples_127_2026-11-07.zip` после `validate_responses`. Проверить 127 записей, две пары по индексам и соответствие заголовков. Генератор разбивает содержимое на части для ограничения JVM и подключает его к `commonTest`.
- [x] Воспроизвести 127 ответов в MockEngine, включая пустой 204, `Content-Type`, `Cache-Control`, `Retry-After` и `requestId`. Сроки проверяются с часами, выставленными по захвату.
- [x] Проверить pending register/login, verified login и `GET /me`. Закрытый режим различает несовместимый сервер при отсутствии `emailVerified` и невалидный ответ при `null` или другом типе. Legacy decode проверен прежними тестами.
- [x] Проверить сохранение действующей сессии после неудачного login и обе пары delete-password `401 → GET /me 200` с одним тестовым Bearer. Совпавший protected `401` с `AUTH_REQUIRED` или невалидным Problem завершает сессию. Public recovery 401 оставляет сессию в её текущем состоянии.
- [x] Проверить девять pending guards, `PASSWORD_RESET_REQUIRED` на login, обычный `FORBIDDEN`, невалидные сочетания пути, статуса и кода, 429, 503 и несовместимые lifecycle 404/501. `EMAIL_NOT_VERIFIED` помечает текущий опубликованный профиль как pending. Единое состояние доступа и поколения реализуются в задаче 2.
- [x] Выполнить RED и финальный прогон. 282 теста прошли, включая 14 новых. Debug и release APK проверены на отсутствие архива и тестовых классов. [Отчёт задачи 1](../../../mobile/artifacts/n2-http-task1/REVIEW.md)

## Блок 2. Доступ, сессия и кеши

### Задача 2. Единое состояние доступа и защита scope

**Файлы** — `domain/CatalogConfiguration.kt`, `data/auth/AuthModels.kt`, `data/auth/AuthSessionClient.kt`, `ui/auth/AuthStore.kt`, оба cache/store, `ui/profile/ProfileEditStore.kt`, `App.kt` и `ui/CatalogRuntime.kt`. Тесты в `ui/auth/AuthStoreTest.kt`, `ui/AccountSavedStoreTest.kt`, `ui/ApplicationStoreTest.kt`, `ui/ApplicationVisibilityTest.kt` и `ui/ProfileEditStoreTest.kt`.

**Результат** — `AuthStore` публикует единое состояние доступа для экранов и stores. Для N2 оно содержит источник, владельца и поколение сессии, время истечения и результат проверки в текущем процессе. Права чтения, записи, редактора и импорта выводятся из этого состояния.

- [x] Ввести явный выбор legacy или `N2_REQUIRED` при создании процесса. Проверить отказ для N2 без валидного REMOTE URL. Связать запрос, результат и запись кеша с исходными `apiBaseUrl`, `userId`, режимом и поколением. Использовать новый локальный идентификатор при успешном входе или регистрации, включая совпадение UUID и expiry. Для восстановления именно той же копии после перезапуска хранить локальный идентификатор в защищённой записи N2 и её частных снимках. Подтверждение для показа хранится в памяти текущего процесса.
- [x] Привязать видимость `account-saved` и `account-applications`, календаря, отметки в detail, редактора и импорта к единому состоянию. При pending и Unknown скрыть содержимое, остановить запись и импорт и сохранить частные файлы. Публичные детали и гостевые закладки продолжают работать. В регрессии вызвать pending через `GET /me` и через 403 каждой группы. Проверить, что поздний ответ операции, начатой в Verified, не публикует и не переписывает скрытую копию.
- [x] При `GET /me` с `emailVerified=true` открывать только копию текущего scope. Смоделировать холодный запуск офлайн, затем восстановление сети. После успешной проверки проверить чтение при потере сети, запрет записи и закрытие видимости при локальном expiry даже без нового HTTP. При возврате сети проверить `/me` перед возобновлением защищённых операций.
- [x] При logout, отзыве или expiry сразу закрывать доступ и инвалидировать операции. Очистка затрагивает Bearer и оба кеша соответствующей сессии. При ошибке очистки доступ остаётся закрыт до успешного повтора. Тесты удерживают старые `401` и `200/204` до нового входа, включая тот же UUID. Проверить профиль, UI, обе копии и действующую новую сессию после доставки каждого ответа. Проверить также изоляцию другого источника, смену владельца, исключение delete-password 401 и сохранность гостевого файла.
- [x] Проверить legacy cold offline, logout, обычный импорт с частичным отказом и существующие сценарии M3. Выбор режима фиксируется при создании runtime. При смене активной сессии прежний доступ закрывается, поздние записи блокируются. Настройка песочниц Android и iOS относится к задаче 5.

Сценарии MockEngine и регрессии legacy прошли. [Отчёт задачи 2](../../../mobile/artifacts/n2-session-task2/REVIEW.md).

## Блок 3. Verification и recovery

### Задача 3. Подтверждение почты

**Файлы** — `data/auth/N2AccountClient.kt`, `data/auth/AuthSessionClient.kt`, `ui/auth/EmailVerificationStore.kt`, `ui/auth/EmailVerificationScreen.kt`, `App.kt`, `ui/AppState.kt`, `ui/CatalogRuntime.kt` и `shared/src/commonMain/composeResources/values/strings.xml`. Тесты в `data/auth/N2AccountClientTest.kt` и `ui/auth/EmailVerificationStoreTest.kt`.

**Результат** — экран pending открывает поток подтверждения для текущей сессии. `N2AccountClient` принимает scope и параметры формы. `AuthSessionClient` проверяет scope и добавляет Bearer перед защищённым запросом. Результат запроса кода различает 202 с `EmailActionChallenge` и 204 с пустым телом. После 204 статус подтверждает `GET /me`.

- [x] Добавить тесты `POST /auth/email-verification/request` без тела и `POST /auth/email-verification/confirm` с `challengeId` и `code`. Оба запроса идут с Bearer захваченной сессии. Проверить строку из восьми ASCII-цифр с ведущим нулём, TTL из `expiresAt`, cooldown из `resendAfterSeconds` и 429 с `Retry-After`.
- [x] Подключить pending-маршрут к существующему UI авторизации. `202` открывает ввод кода. `204` запускает `GET /me` той же сессии, включая ответ «уже подтверждено». Частный доступ открывает только `emailVerified=true` от сервера. Подтверждение сохраняет прежние Bearer и `expiresAt`. Для новой долгой сессии используется обычный вход.
- [x] После таймаута confirm сверить `GET /me` перед новой отправкой. После потерянного 202 дождаться разрешённого повторного запроса. Проверить неверный и просроченный код, повторное нажатие, сетевой сбой, очистку введённого кода при переходе в background и поздний ответ после logout. Код и challenge держатся в памяти с redacted представлением состояния. Срок действия challenge ограничивает повторное использование.
- [x] Прогнать тесты HTTP и экрана на уровне состояния. На выходе — подтверждение переключает обе копии через единое состояние доступа и сохраняет срок исходной сессии.

[Отчёт задачи 3](../../../mobile/artifacts/n2-verification-task3/REVIEW.md).

### Задача 4. Восстановление пароля

**Файлы** — `data/auth/N2AccountClient.kt`, `ui/auth/PasswordRecoveryStore.kt`, `ui/auth/PasswordRecoveryScreen.kt`, `ui/auth/AuthScreen.kt`, `App.kt`, `ui/CatalogRuntime.kt` и строки Compose. Тесты в `data/auth/N2AccountClientTest.kt` и `ui/auth/PasswordRecoveryStoreTest.kt`.

**Результат** — из состояния без входа доступны запрос кода, его проверка и установка нового пароля. Публичные методы `N2AccountClient` используют тот же разрешённый источник через транспорт без Bearer. Recovery привязан к источнику и собственному поколению попытки. `resetToken` существует только в этом потоке и передаётся только в теле confirm.

- [x] Добавить тесты `POST /auth/password-reset/request` с `email`, `verify` с `challengeId` и `code`, `confirm` с `resetToken` и `newPassword`. Для всех трёх запросов проверить отсутствие `Authorization`. Ответ 202 даёт одинаковую формулировку для известного, неизвестного и удалённого адреса. Проверить пароль длиной 12–256 без trim и срок grant по `expiresAt`.
- [x] Связать signed-out recovery со ссылкой «Забыл пароль» и `PASSWORD_RESET_REQUIRED` от login. Вход из активного аккаунта возможен после явного выхода. `204` после reset очищает контекст и возвращает на обычный вход. Проверить, что токен из verify никогда не попадает в `SecureTokenStore`, Bearer или сохранённое состояние экрана.
- [x] При потерянном ответе verify предложить запросить новый код. При потерянном ответе confirm предложить попробовать обычный вход с новым паролем или начать новый recovery. При 400, 429 и 503 сохранить только состояние, подходящее для ручного повтора по матрице. Проверить отсутствие автоматического повтора потребляющих proof POST.
- [x] При Back, background, смене источника или новом recovery инвалидировать поколение попытки и очистить введённый код, пароль и grant. Неиспользованный challenge допустим в памяти до expiry в рамках открытого flow. После неопределённого verify требуется новый запрос. Поздний verify не восстанавливает grant. Проверить это отложенным ответом и убедиться, что public 401 не завершает сессию другого аккаунта.

[Отчёт задачи 4](../../../mobile/artifacts/n2-recovery-task4/REVIEW.md).

## Блок 4. Сборки и совместная приёмка

### Задача 5. Включение в debug-хостах и регрессия legacy

**Файлы** — Android Gradle и `HackAtlasApplication.kt`, `MainViewController.kt`, `HackAtlasApp.swift`, `Info.plist`, `mobile/README.md` и `docs/STATUS_MOBILE.md`. Использовать существующий выбор REMOTE, защищённые хранилища и процессный `CatalogRuntime`.

- [ ] Передать режим в runtime только из явно настроенной debug-сборки. Для Android предложенный флаг `hackatlas.debugAccountMode=N2_REQUIRED` использует отдельный package ID. Для iOS передать `HACKATLAS_ACCOUNT_MODE` только в закрытой debug-конфигурации с отдельным bundle ID и HTTPS. Проверить случай передачи флага для release, неверный URL и запуск обычного debug. В release конфигурация всегда legacy.
- [ ] Из корня выполнить `mobile\gradlew.bat -p mobile :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --console=plain` с JDK 21 и настроенным SDK. Отдельно собрать явно включённый N2 debug с адресом стенда из handoff. Проверить отсутствие N2 fixtures и тестового режима в релизном APK. Выполнить `python scripts/validate_contract.py`.
- [ ] На Mac собрать обычный iOS Debug и закрытый Debug с валидным HTTPS URL по инструкции в `mobile/README.md`. Проверить обработку lifecycle приложения, Keychain и приватные копии. Если окружение ещё недоступно, записать конкретно недостающие build и device checks.

### Задача 6. Android-приёмка у бэкендчика

**Вход** — согласованная матрица закрытого N2, проверенный N2 debug APK и стенд API 36 с Mailpit. **Результат** — отчёт по сборке, версиям стенда и сценариям с фактическими результатами. [Инструкция стенда](../../../infra/lifecycle-test/WINDOWS_ANDROID.md)

- [ ] Pending-регистрация и обычный вход. Публичные детали и гостевые закладки доступны, обе частные копии и редактор скрыты. Код из Mailpit → 204 → `GET /me` true. Проверить срок той же сессии, явный импорт и обе частные копии после подтверждения.
- [ ] Холодный запуск без сети, возврат сети и временная потеря сети уже после подтверждения. Проверить скрытие или чтение обеих копий по утверждённым правилам, запрет записи, expiry и повторный вход. При 401 от реально отозванной сессии обе копии очищаются. Гостевая закладка сохраняется.
- [ ] Запрос и проверка reset с Mailpit, установка нового пароля, обычный вход. Старый пароль и старая сессия отклоняются сервером. Отдельно проверить 429, ошибку кода и возврат из background на Android.
- [ ] Зафиксировать Android API, точный commit backend, API origin, commit и SHA-256 APK, итог по каждому пункту и безопасные снимки. Для delete-password `401 → 200` указать MockEngine и серверное доказательство из архива. Для нативной проверки iOS указать результат задачи 5. Провести итоговый обзор изменений и передать ветку пользователю для commit/push.
