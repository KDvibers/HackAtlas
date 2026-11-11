## Этап и изменение

Задача 5 закрытого N2. Android включает N2 по `hackatlas.debugAccountMode=N2_REQUIRED`, iOS использует отдельную схему `HackAtlasN2` с HTTPS. Закрытые сборки используют `dev.hackatlas.mobile.debug.n2` и собственные хранилища. Обычный Debug и Release работают в legacy.

## Проверка

- Прошёл 321 Android unit-тест, включая четыре новых. Android debug, lint, release и `scripts/validate_contract.py` прошли.
- На эмуляторе API 36 проверены DEMO, legacy REMOTE, появление «Забыли пароль?» в закрытом N2, ошибка при неверном URL и раздельные песочницы двух установок.
- Release с переданными N2-флагами сохранил `LEGACY`. В APK отсутствуют DEMO и N2 fixtures, debug HTTP отключён.

## Интерфейс

Используются экраны из предыдущих задач. Правок дизайна, шрифтов и ассетов в этом PR нет.

## API и данные

Канонический API 0.1.1 сохраняется. Миграций нет.

## Риски / ограничения

На Windows проверена конфигурация iOS. Сборка и нативные проверки lifecycle, Keychain и частных копий ожидают Mac. Приёмка реального backend и Mailpit относится к задаче 6.

## Сообщение напарнику

Для приёмки API 36 собери APK по разделу «Закрытый N2 Debug» в `mobile/README.md`. Он использует `http://10.0.2.2:8081/api/v1/` и требует флаг `hackatlas.debugAccountMode=N2_REQUIRED`.
