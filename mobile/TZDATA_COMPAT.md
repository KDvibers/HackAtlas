# Android: совместимость EventTime со старой tzdata

Исправление относится к чтению API EventTime с precision=MINUTE: каталог,
подробности и сохранённые снимки. API0.1.1, DTO, гостевые файлы и UI не меняются.
DATE сохраняет date/timeZone и не получает выдуманное время или countdown.

Причина: Android14/API34 с tzdata2023a считает Asia/Almaty зоной UTC+6 в2026г.
Поэтому корректная пара10:00/05:00Z отклонялась строгой проверкой клиента.
С2024-03-01 Алматы использует UTC+5 ([IANA NEWS](https://data.iana.org/time-zones/tzdb/NEWS)).
Проверка равенства date/time/timeZone/instant сохранена: неверные точные даты
по-прежнему делают ответ InvalidResponse; подмена REMOTE на DEMO запрещена.

## Реализация

Внутренний expect/actual `data/EventTimeRules` изолирует преобразование instant
в локальное время. Android-only ThreeTenBP1.7.5 содержит TZDB2026egtz независимо
от версии ОС. Android читает системную версию через публичный ICU API24+
`android.icu.util.TimeZone.getTZDataVersion()`; java.time и ICU используют системные
правила. `ZoneRulesProvider.getVersions` на Android недоступен: этот вызов оставлен
только в ветке обычного JVM для локальных unit-тестов, вне Dalvik.

Версии сравниваются после нормализации известного суффикса global-tz `gtz`.
Новые системные правила имеют приоритет над старыми bundled; при равных версиях
или неизвестном формате версии выбирается ОС. Известная bundled-зона, которой
нет в ОС, может использовать bundled. Неизвестная в обоих источниках зона остаётся
ошибкой. Секунды и наносекунды при сравнении не теряются; DST gap отвергается,
оба соответствующих instant при DST overlap допускаются.

Нет замены глобального java.time provider, изменения часов/настроек устройства
или загрузки tzdata по сети. Календарное «сегодня» и локальное отображение возраста
снимка сохраняют прежние системные правила. iOS actual использует прежний
kotlinx.datetime; совместимость старой tzdata на iOS этим изменением не решается.

## Зависимость и обновление

Источник: [Maven Central1.7.5](https://repo.maven.apache.org/maven2/org/threeten/threetenbp/1.7.5/),
[ThreeTenBP](https://github.com/ThreeTen/threetenbp), BSD-3-Clause.
JAR515351bytes, SHA256
`58c5593452f871ebf736dfda3bf8f3bc0de4e92cec61bc19c8ad81f9dfb70d72`.
Данные внутри проверены как2026egtz, а не заявлены «всегда последней tzdata».
При будущих законодательных изменениях и устаревании обоих источников нужен
обычный выпуск приложения с обновлённой зависимостью/данными.

Полная лицензия включена в debug и release APK:
`androidApp/src/main/assets/licenses/THREETENBP_LICENSE.txt`.
Это отдельная Android-зависимость; Darwin и общие модели не заменяются.
Альтернативы: OS-only воспроизводит дефект; удаление tuple-проверки принимает
ложное точное время; архивный TickTock меняет глобальный provider.

## Проверка

Отчёт и native-снимки: `artifacts/tzdata-compat/review.html`.
`scripts/android_tzdata_smoke.py` запускается на отдельном task-owned AVD с REMOTE
APK для `http://10.0.2.2:8772/api/v1/`. Он поднимает read-only HTTP replay канонических
Starter DEMO fixtures на127.0.0.1:8772; это не приёмка работающего Java backend.
Источник fixture явно DEMO, release gate запрещает упаковку fixture JSON в release.
Старый APK воспроизводит отказ на API34; новый проверяется на API34 и API36:
каталог, исходные точные времена, bookmark/restart/offline и отказ при реально
несогласованном MINUTE. Гостевые файлы и настройки восстанавливаются.

Сборка/общие тесты на Windows не подтверждают native iOS. Android26–33,
35 и физический телефон отдельно не запускались.
