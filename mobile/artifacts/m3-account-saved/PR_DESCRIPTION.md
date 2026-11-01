## Этап и изменение

M3 block3: закладки аккаунта подключены к каталогу/подробностям/сохранённому,
профилю и календарю. Добавление/удаление подтверждаются сервером; без сети
доступен просмотр отдельной копии текущего аккаунта. Offline outbox не добавлен.

Копирование гостевых закладок требует явного подтверждения текущего аккаунта.
Каждый успешный PUT записывается в журнал; частичный сбой допускает повтор.
Исходные гостевые файлы сохраняются даже после полного успеха. Нет автопереноса
и silent merge между аккаунтами/API.

## Проверка

Из `mobile/`, JDK21/SDK36:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8771/api/v1/' '-Phackatlas.allowDebugHttp=true'
```

**211 tests PASS (23 новых), 0 failures/errors/skipped; Android build PASS,
lint0, release-no-demo gate PASS.** Starter validator и6fixtures PASS.
Backend package — BUILD SUCCESS/21unit tests; это не повторный backend verify/IT.

Live Android→B2: одноразовая PostgreSQL, настоящий актуальный dev JAR на8771,
synthetic accounts и DEMO-каталог. Основной сценарий: отмена подтверждения без
server writes, явный перенос, durable journal, реальные PUT/DELETE, календарь,
cold offline saved/details/calendar, удаление кеша при logout, гостевые bytes
не меняются, второй аккаунт не видит прежние данные, 360dp/font130%.
Отдельные review-регрессии: network mutation error на details и архивирование
уже открытого snapshot с удалением старого CTA. Итоговые JSON — рядом.

Одно независимое review: **0 Critical / 3 Important / 0 Minor**. Все3исправлены:
reconcile открытой копии после удаления, ошибка на текущем экране, bounded
повторная сверка multi-page состава перед заменой кеша. RED→GREEN и полный suite;
второе независимое review не запускалось. См. REVIEW.md/verification.json.

## Интерфейс

Сохранены палитра/шрифты/artwork/четыре вкладки утверждённого M1. Новых assets,
fonts или dependencies нет. Добавлены реальные Compose status/confirmation/error
surfaces, freshness/read-only подписи, disabled account mutation buttons offline.

Обзор `mobile/artifacts/m3-account-saved/review.html`; screenshots в `screenshots/`.
Основные: copy-confirmation.png, account-saved.png, account-saved-offline.png.
Есть second-account-empty, offline detail/calendar и profile-360-font130.

## API и данные

OpenAPI/README/endpoint semantics не меняются, миграций нет. Saved использует
прежний GET `/me/saved` и PUT/DELETE `/me/saved/{id}` через единственный auth client.
Bearer не добавлен к public catalogue; ожидаемый owner проверяется перед запросом.

Private cache version1 привязан к API+owner и не содержит token/password/profile.
Android no-backup atomic file, iOS protected ApplicationSupport с backup exclusion
(source only). Guest schema не меняется. Logout/switch/401 скрывают private state,
cleanup сериализован с записями; ошибка удаления явно блокирует доступ.

## Риски / ограничения

- iOS compile/Foundation atomic write/file protection/backup exclusion/Keychain/UI
  не проверены на Windows; нужен Mac/iPhone и HTTPS B2.
- Starter offset pagination не даёт snapshot revision. Две совпавшие упорядоченные
  выборки ограничивают обнаруженные гонки, но не гарантируют абсолютную snapshot
  согласованность. Предел200страниц; ошибка сохраняет прежнюю копию.
- Изменения требуют сети. Сервер мог принять PUT до ошибки следующего GET/cache;
  UI сообщает это явно, предлагает refresh/retry.
- Guest bytes сохраняются; журнал копирования приватный и очищается при logout.
  Повторный вход может снова предложить копирование, только с подтверждением.
- Profile edit/application marks/reminders/deletion/reset/verification и release
  вне этапа. APK настроен на dev8771; временная среда после тестов остановлена.

## Сообщение напарнику

Подключили account saved/copy guest по прежнему Starter B2. Клиент не меняет
контракт, токены opaque, refresh нет. Guest-copy поэлементный и подтверждаемый,
исходные гостевые данные сохраняются. Offline read-only, без outbox. Android
проверен с двумя аккаунтами на отдельной БД; iOS-приёмка требует Mac/HTTPS.
