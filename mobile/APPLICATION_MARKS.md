# Личная отметка заявки — M3

Пользователь явно ставит «Я подал(а) заявку» в подробностях события. Это личная
отметка, а не подтверждение организатора или отправка заявки. Регистрация на сайте,
«Поделиться» и закладка не создают запись автоматически. Повторный PUT существующей
отметки сохраняет серверный markedAt, DELETE снимает её независимо от закладки.

Гость входит из этого блока и возвращается к выбранному событию. Bundled DEMO
честно сообщает о недоступности изменения; REMOTE DEMO-события сохраняют DEMO label.
Новые разделы навигации, список заявок и напоминания не добавляются.

## API и состояние

Канонический Starter0.1.1/README, без изменений:

- GET /me/applications?page=0&size=50 → ApplicationPage.
- PUT /me/applications/{id} →200 ApplicationMark.
- DELETE /me/applications/{id} →204.

AuthSessionClient проверяет expectedUserId под существующим mutex и отправляет
opaque Bearer только своему API. UUID, APPLIED/selfReported=true, markedAt,
requested page/size, метаданные/дубликаты и asOf валидируются. 401 очищает сессию
существующим путём; refresh-token flow не вводится.

ApplicationStore публикует полное membership только после всех страниц. Для
многостраничного списка выполняется повторный упорядоченный scan; сдвиг totals/
дубликат/изменившийся результат отвергаются. Как и у saved, число страниц ограничено;
API без snapshot revision не позволяет обещать сериализуемое чтение при произвольных
внешних конкурентных изменениях. При сбое ранее подтверждённая копия сохраняется.

Нет optimistic success: сначала ответ сервера. Неопределённый результат блокирует
следующую мутацию до явного GET-обновления. Подтверждённое изменение остаётся видно
при сбое локальной записи/обновления профиля, с соответствующей ошибкой. Счётчик
заявок обновляется через GET /me, а не выдумывается из числа локальных записей.

## Offline и изоляция

Пользователь отдельно утвердил server-confirmed writes + offline read-only cache.
Очереди нет. После перезапуска без сети доступны только известные данные своего
аккаунта с датой копии/предупреждением; незагруженный список не означает отсутствие
отметки. Кнопка изменения недоступна, пока сессия и данные сервера не подтверждены.

Копия содержит только API scope, userId, cachedAt и список отметок, без токена или
профиля. Android: no_backup/account-applications/{sourceHash}.json;
iOS: ApplicationSupport/AccountApplications/{sourceHash}.json, прежние атомарные
операции и запрет backup. PrivateCacheKind.SAVED по умолчанию оставляет существующие
saved paths/schema; гостевые файлы не читаются и не мигрируются этой функцией.

Logout/смена аккаунта очищает приватное состояние; сбой очистки блокирует показ
до повтора. Поколения запросов и mutex кеша защищают от поздних ответов/записей.
visibleFor дополнительно скрывает старого владельца до выполнения async collector.
Один process-owned runtime; произвольные одновременно активные владельцы одного
файла, как и раньше в приложении, не поддерживаются.

## Проверка

Спецификация утверждена в docs/superpowers/specs/2026-11-05-mobile-application-marks-design.md.
План/статус: docs/superpowers/plans/2026-11-05-mobile-application-marks.md и
docs/STATUS_MOBILE.md. Логи: mobile/build/m3-applications/.

268 shared tests, Android debug/lint/release gate и contract validator прошли.
Новых32теста:10 MockEngine,6 cache,13 store/lifecycle,3 visibility. Исходные RED
и дополнительные regression-проверки записаны в ledger и логах. Отдельный fresh
read-only review не нашёл Critical/Important/Minor дефектов исходного кода.

Android native10/10PASS: scripts/android_application_marks_smoke.py, настоящий
B2:8774/одноразовая PostgreSQL:5551, synthetic users/DEMO. Restart/offline, independent
bookmarks, account switch, real revoked401,360dp/font130; installed APK SHA совпал,
settings/guest bytes восстановлены.4прежние неудачные попытки сохранены;2helper bugs
исправлены с regression checks, software qemu crashes обойдены hostGPU/Vulkan-off.
Код приложения/проверенный APK не менялись.9helper checks PASS. Native отчёт,
история, скриншоты/галерея: artifacts/m3-applications/. Временные процессы остановлены;
удаление ignored AVD/env заблокировала автоматическая проверка, файлы оставлены.

iOS на Windows не собирался; Keychain/Foundation/VoiceOver требуют Mac/iPhone.
