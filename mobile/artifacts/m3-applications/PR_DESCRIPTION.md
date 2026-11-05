## Этап и изменение

В подробностях события можно поставить личную отметку подачи заявки, увидеть дату
и снять отметку. UI меняется после подтверждения сервера; повторный PUT сохраняет
исходную дату. Вход гостя возвращает к выбранному событию. Закладки и официальный
сайт независимы от отметки.

После перезапуска без сети доступна своя read-only копия с датой/предупреждением.
Неизвестное состояние не выдаётся за отсутствие отметки. Отдельный source/user
cache не содержит токен; logout/401/смена владельца очищают private state, поздние
ответы не возвращают его. Подтверждённый результат сохраняется при сбое диска/профиля.
Без offline queue, отдельного списка заявок, reminders или email lifecycle.

## Проверка

268shared tests,0 failures/errors/skipped (32 новых); Android assembleDebug,
lint0 issues, release-no-demo gate и canonical validator PASS. Backend package
для стенда:31unit tests PASS, серверный verify/IT повторно не запускался.
MockEngine/cache/store/visibility проверяют schema/owner/HTTP, пагинацию, неизвестный
результат, ошибки записи,401 и гонки logout/write. Один независимый source review:
0Critical/0Important/0Minor. Gitleaks history + changed-file snapshot:0findings.

Android16/API36: **10/10 PASS в одном полном прогоне**, настоящий локальный B2,
одноразовая PostgreSQL, synthetic users/DEMO. Проверены guest sign-in return,
явная отметка/дата, idempotentPUT/restart, bookmark independence, cold offline cache,
remove, switchA→B,360dp/font130 и настоящий revoked401 с очисткой credential/обоих
private caches при сохранении guest bytes. failure=null, restoreErrors=[],
observation retries0. SHA установленного APK совпал с проверенной сборкой.

9helper selfchecks/regressions PASS.4 прежние неудачные попытки сохранены в истории:
2helper bugs исправлены,2host qemu crashes обойдены аппаратной графикой. APK и код
приложения во время native не менялись; частичные прогоны не засчитаны как приёмка.
Отчёт: mobile/artifacts/m3-applications/REVIEW.md, verification.json, android-smoke.json.

## Интерфейс

Общий Compose-компонент Android/iOS. Прежние M1 шрифты/палитра/pixel headings,
арт и основная кнопка регистрации сохранены. Новых fonts/assets/dependencies нет.
Галерея10реальных screenshots: mobile/artifacts/m3-applications/review.html;
ключевые состояния просмотрены,360dp/font130 текст/действие доступны.

## API и данные

GET `/me/applications`, PUT/DELETE `/me/applications/{id}` по Starter0.1.1.
API/README/examples/backend/infra/CI и миграции без изменений, opaque sessions прежние.
APPLICATIONS — отдельная no-backup/protected директория; saved/guest schema прежние.
Тестовые данные остаются DEMO. Preview не является подачей заявки организатору.

## Риски / ограничения

- Личная отметка не подтверждает приём заявки организатором.
- Потерянный ответ требует GET перед новым изменением; очереди без сети нет.
- Полный повторный обход снижает drift; API без revision не даёт транзакционный snapshot.
- iOS native build/Keychain/UI на Windows не выполнялись, нужны Mac/iPhone.
- Физическое устройство/TalkBack и software-renderer emulator не приняты.
- Debug APK8774 требует своего backend; временные процессы остановлены. Удаление
  локального AVD/env заблокировала автоматическая проверка без пояснения, ignored
  файлы оставлены; generated DB удалена. Release/deployment вне scope.

## Сообщение напарнику

Личные отметки реализованы по действующему API0.1.1 без изменений контракта/сервера.
268shared и10/10Android native PASS, включая offline/switch/revoked401/360dp-font130.
iOS native остаётся отдельной проверкой. Новые блоки M3 этим PR не добавляются.
