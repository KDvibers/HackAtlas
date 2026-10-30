# Итоговое review M3 auth core — 31 октября 2026

Одно независимое read-only review относительно `92ca815d4c12249cbff829fc3f62b1280bde866d`. Первоначально **0 Critical / 2 Important / 0 Minor**. Оба Important исправлены исполнителем в одном проходе с native RED→GREEN; второго review не было. Изменения оставлены без commit/push.

## Исправлено

1. **Ложный успех AtomicFile commit.** `finishWrite` может лишь записать ошибку в лог ([AOSP](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/util/AtomicFile.java)). Теперь выполняются явный fd.sync и чтение/сверка committed ciphertext под тем же lock. Native test `case commit` моделирует silent failure поверх сохранённой сессии: RED до исправления, GREEN после; предыдущая запись сохраняется, write сообщает Storage. Internal AtomicFile factory доступен для тестовой инъекции, публичный constructor по-прежнему context+source.
2. **Ошибки доступа принимались за отсутствие файла/успешное удаление.** Вместо неоднозначного File.exists используется Os.lstat: только ENOENT означает отсутствие. Native `permissions-read` и `permissions-clear` временно запрещают traversal в собственном каталоге test APK и восстанавливают permissions в finally. Оба RED до исправления, GREEN после; оставшийся credential читается после восстановления доступа.

Финальный native `all` (включая encryption/reopen/replace/source separation/tamper/corruption/clear) прошёл после исправлений. Test APK удалён, принадлежащий этому запуску эмулятор штатно остановлен. Основное приложение/его данные не менялись. Окончательная общая проверка записана в verification.json; новое независимое одобрение после исправлений не заявляется.

## Границы решения

- iOS compile/link/Keychain/lockscreen: Mac отсутствует, выполнен source review. Native дефекты до Mac-проверки не исключены; обязательный checklist в AUTH_CORE.
- Android reboot/lockscreen/Keystore invalidation: вне фактически выполненной instrumentation. На целевых устройствах поведение ещё может отличаться.
- UI, private-cache cleanup, saved sync и live device→B2: исключены прямым запросом первого блока. Core не подключён к App; весь M3 не объявляется готовым.
- Несколько клиентов на один scope: документирован один владелец сессии. Mutex защищает один экземпляр; создание нескольких конкурирующих владельцев при следующем DI-блоке потребует отдельного решения.

Отложенных Minor нет. Переход к экранам/синхронизации не разрешён этим отчётом.
