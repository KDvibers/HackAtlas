# M3 block 1 — session core

Клиент `data/auth/AuthSessionClient.kt` пока не подключён к App/экранам. Создавайте один экземпляр на API scope и закрывайте его при завершении runtime. Каталог использует прежний отдельный public client.

```kotlin
val source = CatalogSource.Remote("https://YOUR_HOST/api/v1/")
// Android (host/application context):
val store = AndroidSecureTokenStore(applicationContext, source)
// iOS Kotlin host: val store = IosSecureTokenStore(source)
val auth = createAuthSessionClient(source, store)
val restored = auth.restore() // null: записи нет; ошибки обрабатываются отдельно
val user = auth.login(email, password)
val outcome = auth.logout()
auth.close()
```

`register(email, password, displayName)`, `login`, `profile` и `restore` возвращают существующий `ProfileDto`. Токен хранится только через store; публичный StateFlow содержит `NotLoaded`, `SignedOut`, `Authenticated(user)`, `SignInRequired` или `StorageUnavailable`. UI следующего блока должен явно обрабатывать AuthException, включая неуспешный restore; ошибка сети не означает отсутствия сессии. Пароль не trim, не сохраняется. Токен не интерпретируется как JWT.

`expiresAt <= clock` и 401 от `/me` очищают текущую запись и требуют повторного входа. Ошибка login не отзывает/не удаляет прежнюю сессию. Register/login всегда без Bearer; он добавляется только к фиксированным me/logout. Отдельный клиент не устанавливает cookies, logging, Auth или retry plugin; redirects выключены. Android auth engine дополнительно отключает OkHttp connection retries, а public GET engine сохраняет прежнюю политику. Не подменяйте auth engine публичным engine или клиентом с глобальными headers/interceptors. Нормализованный API base URL store и client обязаны совпадать.

Logout различает `Revoked` (204), `AlreadyInvalid` (401/истёкшая запись), `NoLocalSession` (нечего отправлять), `LocalOnly(error)` (серверный отзыв не подтверждён). Даже при network error/cancellation выполняется локальная очистка; cancellation пробрасывается. При невозможности очистки — AuthError.Storage/StorageUnavailable, успешный выход не заявляется. Ошибка чтения при logout не мешает попытке удалить повреждённую запись, результат остаётся LocalOnly(Storage).

Операции одного экземпляра сериализованы Mutex, запись/очистка и публикация состояния выполняются вместе в NonCancellable. Это защищает от позднего 401/logout, стирающего новую сессию. Общие private caches/перенос гостевых закладок ещё не реализованы; добавлять их можно только с очисткой по account switch/logout в следующем блоке. Независимые экземпляры для одного API scope не предназначены для совместного управления сессией. Если сервер успел выдать токен, но ответ потерян или store не принял запись, клиент не заявляет успешный вход; неизвестная клиенту серверная сессия остаётся до TTL/серверного отзыва.

Android: ciphertext AES-256-GCM в AtomicFile внутри noBackupFilesDir, ключ AndroidKeyStore, AAD=API base URL, provider-generated IV. Запись явно вызывает fd.sync и сверяет committed ciphertext, поскольку AtomicFile может лишь логировать ошибку. Наличие файла проверяется через Os.lstat: только ENOENT означает отсутствие, ошибки доступа/I/O дают Storage. Ключ может быть программно или аппаратно защищённым — StrongBox не обещается. Полный профиль/пароль на диск не записываются. Corruption/невалидный key не превращаются в пустую сессию. iOS: generic-password Keychain, отдельные service/account, WhenUnlockedThisDeviceOnly, synchronizable=false. После переустановки iOS Keychain может сохранять запись; TTL/серверная проверка всё равно обязательны. На заблокированном устройстве доступ к Keychain может завершиться Storage; это не logout.

## Проверки

Из `mobile/`, JDK21 и существующий SDK36:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain
.\gradlew.bat :shared:assembleDebugAndroidTest --offline --console=plain
adb -s emulator-5554 install -r shared/build/outputs/apk/androidTest/debug/shared-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w dev.hackatlas.mobile.shared.test/dev.hackatlas.mobile.data.auth.SecureStoreInstrumentation
```

Native runner работает в отдельном library test APK, использует только синтетический токен и `*.invalid` API scopes, не вызывает backend и не читает хранилище основного приложения. Вывод обязан содержать `PASS native Keystore`, наличие APK само по себе не PASS. Он проверяет write/read через новый экземпляр, ciphertext, replace, source separation, чужой/corrupt ciphertext, clear/reuse. Review-регрессии: инъекция log-only commit failure поверх существующей сессии и временный chmod тестового каталога с восстановлением в finally; read/clear обязаны сообщать Storage, старый credential не должен считаться удалённым. Выбор отдельного сценария: добавить `-e case commit`, `-e case permissions-read` или `-e case permissions-clear` перед именем runner. Повторное чтение новым экземпляром не доказывает работу после перезагрузки устройства/изменений lockscreen/Keystore invalidation.

На Mac требуется compile/link обеих iOS targets и проверка реального Keychain: запись, повторное чтение после перезапуска host, update/clear; два source scopes; недоступность при locked-device и отсутствие plaintext fallback; logout/restore после expiry401. Windows/MockEngine не проверяют Security framework.

B2 проверяется текущим `backend/mvnw.cmd --batch-mode --no-transfer-progress verify` с Testcontainers и validator `backend/src/test/resources/validate_http_contract.py --schema api/openapi.yaml --responses backend/target/b2-contract`. Это реальные HTTP/БД тесты, а не повторный запуск пользовательского dev backend. Четыре auth-операции мобильного клиента покрыты отдельно MockEngine; live Ktor→B2 на устройстве остаётся проверкой следующего UI/host блока.

Основания: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec), [Apple Keychain accessibility](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly). Источник API — только Starter `api/openapi.yaml` + `api/README.md`.
