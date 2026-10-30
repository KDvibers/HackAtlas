## Этап и изменение

Первый блок M3 добавляет основу авторизации по Starter: register/login/logout/me, сохранение opaque Bearer-сессии в Android Keystore ciphertext / iOS Keychain и восстановление через me. Клиент выделен отдельно от публичного каталога; UI/экраны и синхронизация закладок пока его не используют.

Expiry/защищённый401 требуют нового входа без refresh. Ошибка login сохраняет прежнюю сессию. Logout различает подтверждённый серверный отзыв и только локальную очистку. Ошибки native storage явные; пароли/токены не включаются в диагностические строки и raw HTTP errors. API scope изолирует сохранённые сессии; Mutex и завершённые локальные commits защищают смену сессии от cancellation/гонок.

## Проверка

Mobile: 173 теста (27 auth MockEngine), 0 failures/errors/skipped; Android debug/test APK build, lint0, release gate PASS. Native Android instrumentation проверил Keystore/ciphertext, повторное чтение, update/clear, source isolation и corrupt/tamper rejection. Подробные команды — mobile/AUTH_CORE.md, docs/STATUS_MOBILE.md; результаты — mobile/artifacts/m3-auth-core/verification.json.

B2 на актуальном коде: Maven verify 279 PASS и 345 реальных HTTP-ответов по неизменённой OpenAPI. Временные БД Testcontainers; пользовательский dev backend не менялся. Starter +6 fixtures и diff check PASS.

Итоговое review нашло два Important Android storage: silent AtomicFile commit failure и ошибочное отсутствие файла при недоступном каталоге. Добавлены sync/readback и проверка errno; native commit/permissions-read/permissions-clear проверены RED→GREEN, затем полный native all PASS. Повторного независимого review не было; замечания закрыты регрессиями.

## Интерфейс

UI не изменён, новых ассетов/шрифтов нет. Экраны авторизации и host integration — следующий отдельный блок.

## API и данные

Контракт и backend source не менялись. JWT/refresh не добавляются. Пароли не хранятся, токен/expiry/userId сохраняются только в native secure store, привязанном к API URL. Существующие гостевые данные не меняются; private cache и saved sync будут отдельной работой.

## Риски / ограничения

iOS compile/link/Keychain runtime требует Mac и ещё не проверен. Android проверен в одном эмуляторе; reboot/lockscreen/Keystore invalidation остаются device checks. Нет live Ktor→B2 user flow на устройстве: core пока не подключён к App. Если сервер выдал сессию, а ответ/запись потеряны, неизвестная клиенту сессия живёт до TTL/серверного отзыва; успешный вход клиент не заявляет. Полный M3 этим PR не закрывается.

## Сообщение напарнику

B2 проверен, новые endpoints не требуются. Следующий согласуемый шаг — host/UI и live вход, затем отдельная синхронизация закладок. iOS требует доступного HTTPS-стенда.
