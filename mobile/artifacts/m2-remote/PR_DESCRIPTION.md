## Этап и изменение

M2, Tasks 1–5: подключён публичный каталог Starter к общим экранам Android/iOS через Ktor. REMOTE использует серверные поиск, AND-фильтры, порядок, пагинацию и подробности; ошибки первого запроса, догрузки и detail показываются отдельно. Устаревшие ответы не заменяют новый запрос.

Закладка хранит полные подробности на устройстве. После перезапуска без сети доступны сохранённое, подробности и календарь. Копии разделены по API source и паре `(id, isDemo)`, прежние гостевые данные совместимы. Сетевая ошибка не включает встроенное DEMO.

При live-приёмке исправлены потеря букв при быстром вводе, повторное нажатие Retry после смены сети и сброс прокрутки каталога при возврате из подробностей. Debug page size можно задать в пределах1..50; release и bundled начинают с20.

## Проверка

Из `mobile/`, JDK21, Android SDK:

```powershell
.\gradlew.bat :androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8080/api/v1/' '-Phackatlas.allowDebugHttp=true' '-Phackatlas.debugPageSize=2' --console=plain --offline
```

BUILD SUCCESSFUL; 131 тест, 0 failures/errors/skipped; lint0; release без DEMO fixtures и cleartext. Для повторения `--offline` нужен уже заполненный Gradle cache.

Из корня:

```powershell
python -m unittest discover -s mobile/scripts -p test_android_remote_smoke.py
python -X utf8 scripts/validate_contract.py
python -X utf8 mobile/scripts/android_remote_smoke.py --adb adb --serial emulator-5554 --api-base-url http://127.0.0.1:8080/api/v1 --event-id 39287368-5259-5235-a1c4-25b4e1e8421e
git diff --check
```

2 проверки smoke-отчёта; Starter +6 fixtures; живой Android smoke с B1, страницы2/2/1, быстрый ввод, ошибки/Retry, возврат к нижней карточке, cold offline, календарь, удаление и восстановление данных/настроек эмулятора. Точные результаты и SHA APK: [verification.json](https://github.com/KDvibers/HackAtlas/blob/feat/mobile-catalog-api/mobile/artifacts/m2-remote/verification.json), [remote-smoke.json](https://github.com/KDvibers/HackAtlas/blob/feat/mobile-catalog-api/mobile/artifacts/m2-remote/remote-smoke.json).

Отдельный backend-клон `a745f4ade0cfacaa769fceaedf57a61baa397ee2`: `mvnw.cmd --batch-mode --no-transfer-progress verify` — 9 unit +258 integration tests PASS;139 HTTP-ответов прошли contract validator. Backend source не менялся.

## Интерфейс

Утверждённый M1 сохранён: прежние изображения, палитра, пиксельные заголовки и шрифты. Новых assets/fonts нет. [Галерея реальных Android screenshots](https://github.com/KDvibers/HackAtlas/blob/feat/mobile-catalog-api/mobile/artifacts/m2-remote/review.html) включает REMOTE, offline и360dp с font scale130%.

![Каталог](https://github.com/KDvibers/HackAtlas/blob/feat/mobile-catalog-api/mobile/artifacts/m2-remote/screenshots/remote-home.png?raw=true)
![Подробности](https://github.com/KDvibers/HackAtlas/blob/feat/mobile-catalog-api/mobile/artifacts/m2-remote/screenshots/remote-detail.png?raw=true)
![Календарь без сети](https://github.com/KDvibers/HackAtlas/blob/feat/mobile-catalog-api/mobile/artifacts/m2-remote/screenshots/calendar-offline.png?raw=true)

## API и данные

OpenAPI/README не менялись, миграций backend нет. Используются только `GET /hackathons`, `/hackathons/{id}`, `/metadata/filters`. Авторизация и приватные endpoints не подключены; согласованный opaque Bearer flow не изменён. Пять событий тестового backend явно имеют `isDemo=true`. Release fixture gate проверяет отсутствие встроенного DEMO; серверный DEMO не превращается в реальные события.

## Риски / ограничения

- Mac/Xcode отсутствуют: Native/Swift/Darwin build и iOS runtime не проверены. Общий код собирался на Android/JVM.
- Проверено на Android17/API37 emulator с4ГБ RAM. Физический Android, production HTTPS и CI не запускались.
- APK настроен на локальный backend компьютера через10.0.2.2:8080; это debug-сборка для эмулятора.
- Сохранённые копии не обновляются автоматически. Полный каталог без сети не обещается.
- Auth, серверные saved/import, переход к регистрации, share, reminders и remote images остаются отдельными этапами. M2 целиком и M3 не объявляются завершёнными.
- Независимое ревью: один P2 о прокрутке исправлен с live RED→GREEN; повторного review не было. Неисправленных блокирующих замечаний нет.

## Сообщение напарнику

Локально проверили мобильный REMOTE с B1 на commit `a745f4a`: три публичных GET, поиск/AND-фильтры, пагинация и детали работают; закладки и календарь доступны после перезапуска без сети. Контракт не меняли, DEMO сохраняет маркировку. Следующий отдельный мобильный шаг — регистрационная ссылка и share; авторизацию пока не подключаем.
