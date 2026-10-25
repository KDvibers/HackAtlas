Title: feat(mobile): connect catalog stores to Android and iOS

## Этап и изменение
M2 Task 4: подключены DEMO/REMOTE, CatalogStore и DetailStore к общим экранам Android/iOS. Сохранение получает полный detail и проверяет ID/isDemo до записи. Сохранённое и календарь открываются независимо от сети; копии разных источников не смешиваются. Ожидание сохранения и неподтверждённые ошибки переживают пересоздание Activity через process-owned BookmarkStore.

## Проверка
- `:androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures` — PASS, 126 tests, lint 0 issues.
- Контракт Starter и 6 fixtures — PASS; API не менялся.
- Android emulator: 6 offline и 7 финальных REMOTE mock smoke групп PASS, включая AND-фильтры, metadata retry, страницу 2, 404, две классификации одного ID, чтение после перезапуска без HTTP и сохранение pending/error feedback при пересоздании Activity.
- iOS source подготовлен, Mac/Xcode compile/link и реальный B1 не проверены.

## Интерфейс
Сохранены утверждённые шрифты, графика, палитра и карточки. Добавлены состояния сети, лист фильтров и гостевой REMOTE-профиль без вымышленных пользовательских данных. Снимки: `mobile/artifacts/m2-catalog/review.html`.

## API и данные
Starter OpenAPI/README без изменений. Только публичные GET. Auth/private saved/browser/share не подключались. Release разрешает только REMOTE HTTPS; отсутствующий URL даёт ошибку конфигурации. Debug HTTP доступен только для явно выбранного host. DEMO fixtures не попадают в release; synthetic MOCK события используются только smoke-стендом.

## Риски / ограничения
Нужны Mac/iPhone и отдельная Task 5 с доступным B1. Нет утверждённого production URL. Snapshots не обновляются автоматически; время сохранения не выдаётся за проверку источника. Единственное Important P2 итогового независимого ревью (потеря feedback при пересоздании host) исправлено с RED→GREEN тестами; подробности в `docs/STATUS_MOBILE.md`.

## Сообщение напарнику
Для приёмки нужны base URL с /api/v1, команды запуска и ID опубликованного события; уточните DEMO seed. Контракт не меняли, авторизация в этом PR не используется.
