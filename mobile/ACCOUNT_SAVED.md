# M3 block 3 — закладки аккаунта и копирование гостевых

Выбранная пользователем модель: добавление/удаление подтверждает сервер, без сети
доступен просмотр кеша. Очереди изменений без сети нет. Используется прежний Starter:
GET `/me/saved?page=&size=`, PUT/DELETE `/me/saved/{id}`; JWT/refresh нет.

## Владельцы и хранилище

Один `AuthSessionClient` владеет токеном. Saved-операции проверяют ожидаемый UUID
внутри того же mutex, прежде чем отправлять Bearer. Устаревшая операция не сможет
передать запрос от имени следующего пользователя. Protected401/expiry очищают
credential и доходят до `AuthStore`, даже если запрос сделан из каталога.

После локальной проверки защищённой сессии публикуются только userId/expiresAt.
Это не подтверждённый сервером профиль и не право записи: при cold offline restore
эти метаданные нужны для выбора правильной копии. Без валидной локальной сессии
private cache не показывается. После expiry/401/storage error/выхода owner очищается.

`AccountCache` — отдельная схема version1: normalized API URL, UUID владельца,
время кеширования, снимки публичных событий и журнал подтверждённых guest-copy
keys. Файл один на API URL, его body дополнительно привязан к владельцу. При
смене владельца предыдущая копия удаляется; она никогда не становится гостевой.
Токен, пароль и профиль в этот JSON не записываются. Формат гостевого файла не меняется.

Android: AtomicGuestFileStorage/atomic rename/fsync, каталог
`no_backup/account-saved/<SHA256 API URL>.json`. Это app-private/no-backup файл,
не дополнительное шифрование credential; сам токен остаётся в Keystore-backed store.
iOS: ApplicationSupport/AccountSaved, atomic string write, protected directory и
backup exclusion. iOS adapter подготовлен на Windows; Foundation/protection/backup
поведение и компиляция требуют отдельной Mac/iPhone проверки.

## Обновление и очистка

- Все страницы и полные public details собираются до замены копии. Проверяются
  page/size/count, уникальность IDs, total и ID/isDemo подробностей.
- Для нескольких страниц выполняется вторая ограниченная сверка упорядоченного
  состава. При расхождении остаётся прежняя копия и ошибка. `asOf` — время сервера,
  не версия снимка: строгой транзакционной согласованности HTTP-пагинации нет.
  Защитный предел — 200 страниц; превышение оставляет прежнюю копию и ошибку.
- После неуспешного server/cache шага UI не обещает, что сервер ничего не изменил.
  Доступны обновление/повтор; PUT/DELETE идемпотентны. Ошибки видны также в каталоге
  и подробностях, где пользователь нажал закладку.
- Logout/account switch скрывают личное состояние сразу. Поколение владельца
  отбрасывает запоздавшие ответы; mutex упорядочивает начатую atomic write и clear.
  Ошибка cleanup блокирует показ private saved до повторной проверки, не трактуется
  как успешное удаление кеша.
- Открытые подробности копии сверяются с новым составом. Если событие исчезло,
  cached CTA больше не показывается: public detail запрашивается заново, archived404
  отображается как недоступность. Изменившиеся/отменённые события получают новые details.

## Копирование гостевых закладок

Пользователь нажимает кнопку и подтверждает число закладок и текущий аккаунт.
Список фиксируется при подтверждении; public detail проверяет ID/isDemo, затем
выполняются поэлементные PUT. Каждый подтверждённый успех записывается в журнал
до следующего элемента. Частичный сбой оставляет прогресс и исходные guest bytes.
Повтор не создаёт дубликаты; потерянный ответ безопасно повторяется.

**Гостевой файл не удаляется даже после полного успеха.** Нет автопереноса,
silent merge, копирования между разными API URL или автоматического retry для
другого аккаунта. После logout журнал приватной копии удаляется; новый вход может
снова предложить копирование, но только с подтверждением и идемпотентными PUT.
Запись в журнале означает подтверждённое копирование в прошлом, не запрет серверу
удалить событие или гарантию текущего наличия закладки.

## UI и проверки

Аккаунт использует свои saved/calendar/profile snapshots. Без подтверждённой
сессии видны только matching cache, его свежесть и read-only пояснение; профиль
не выдумывается. Гость продолжает пользоваться старым локальным режимом.
Цвета, шрифты, artwork и четыре вкладки M1 сохранены.

Сборка из `mobile/`:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8771/api/v1/' '-Phackatlas.allowDebugHttp=true'
```

Live smoke `scripts/android_account_saved_smoke.py` предназначен только для
своей временной БД `hackatlas-m3-account-saved-db`/B2 порт8771 и emulator-*.
Он использует synthetic accounts/DEMO events, сохраняет guest bytes/settings,
отказывается от существующих credential/cache этого scope. Дополнительный режим
`--review-errors-only` проверяет network error на details и архивирование события
в тестовой БД (с восстановлением publication status). Не запускать против
пользовательской/production БД. Credentials не включаются в artifacts.

Результаты — `artifacts/m3-account-saved/verification.json`, screenshots и REVIEW.md.
Mac/iOS build/runtime/Keychain и реальное устройство остаются открытыми. Profile edit,
application marks, reminders, account deletion/reset/verification и release не входят.
