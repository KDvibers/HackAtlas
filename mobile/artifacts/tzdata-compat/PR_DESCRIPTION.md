## Этап и изменение

Исправлено открытие каталога на Android со старой tzdata. API34/2023a раньше
отклонял корректный MINUTE Asia/Almaty:10:00/05:00Z из-за системного UTC+6.
Android-only ThreeTenBP1.7.5 с TZDB2026egtz даёт независимые правила; более новые
системные сохраняют приоритет. Версия ОС читается через публичный Android ICU API.
Строгая проверка date/time/timeZone/instant сохранена, iOS resolver прежний.

## Проверка

- `:shared:testDebugUnitTest --rerun`:236 PASS,0 failures/errors/skipped.
- `:androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures`:PASS, lint0.
- `scripts/validate_contract.py`:PASS, API0.1.1.
- Старый APK на API34/2023a воспроизводит отказ; новый APK:4/4 на API34 и4/4 на API36/2025a.
- Каталог/точные времена; guest bookmark/restart/offline; неверный MINUTE отвергается.
- Совпадают SHA установленного APK и байты валидного HTTP каталога before/after.
- Bundled TZDB и полный BSD license asset присутствуют в debug/release.
- Один fresh read-only final review:0 Critical/Important/Minor.

Native проверка использует read-only HTTP replay Starter DEMO fixtures, не живой B2.
Неудачные попытки сохранены в android-history.json; они не засчитаны как PASS.
Отчёт: mobile/artifacts/tzdata-compat/review.html и verification.json.

## Интерфейс

Дизайн M1, шрифты/artwork и экраны не менялись. Native screenshots приложены
в mobile/artifacts/tzdata-compat/{api34,api36}/screenshots.

## API и данные

OpenAPI/README/examples, backend и схема гостевых файлов прежние. Миграций нет.
Канонические fixture JSON остаются только debug/test; release gate PASS.
Новые auth/emailVerified/403 правила и следующие блоки M3 не вводятся.

## Риски / ограничения

Новая Android-only зависимость BSD-3-Clause; полная лицензия упакована asset.
При устаревании и ОС, и bundled правил нужен выпуск с обновлённой tzdata.
Native iOS/Mac, физический Android и API26-33/35 не запускались; iOS старая
база правил этим изменением не исправляется. Глобальная java.time и часы ОС прежние.

## Сообщение напарнику

Устранена клиентская несовместимость EventTime со старой Android tzdata.
API0.1.1 и backend менять не требуется; строгая согласованность MINUTE остаётся.
