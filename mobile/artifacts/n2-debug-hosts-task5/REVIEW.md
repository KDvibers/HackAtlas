# Закрытый N2. Задача 5

Ветка `feat/mobile-n2-debug-hosts` основана на `799bcd1`. Android выбирает
закрытый режим по `hackatlas.debugAccountMode=N2_REQUIRED`. iOS использует
`HackAtlasN2` с конфигурацией `Debug-N2`, флагом `HACKATLAS_ACCOUNT_MODE`
и HTTPS-адресом. Идентификатор закрытой сборки на обеих платформах —
`dev.hackatlas.mobile.debug.n2`. Legacy сохранил прежние идентификаторы.
Обе оболочки передают общий выбор источника и режима в `CatalogRuntime`.
Неверный адрес или DEMO в закрытом режиме открывают экран ошибки настройки.

## Проверки

JDK 21.0.9, Android SDK 36, PowerShell из корня репозитория.

```powershell
.\mobile\gradlew.bat -p mobile :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --console=plain
python -X utf8 scripts/validate_contract.py
```

- Прошёл 321 unit-тест, включая четыре новых теста выбора конфигурации.
  Android lint завершился без замечаний. Валидатор контракта прошёл.
- Закрытый APK собран с флагом N2 и `http://10.0.2.2:8081/api/v1/`
  по инструкции в [mobile/README.md](../../README.md#закрытый-n2-debug).
  SHA-256 проверенного APK `f04be097ca6d0b8dbd05416447e005b59ced616a57e4f70db680e6bff20ea859`.
  Для приёмки на другом компьютере нужно записать SHA-256 его APK.
- При передаче тех же debug-флагов в `verifyReleaseNoDemoFixtures` Release
  сохранил `dev.hackatlas.mobile`, режим `LEGACY` и отключённый HTTP.
  `aapt` подтвердил release-манифест без debug network config и `debuggable`.
  Проверка ZIP подтвердила отсутствие DEMO и обоих N2 HTTP fixtures.
- На Windows проверены XML схем и `Info.plist`, настройки `Debug-N2`,
  выбор debug-фреймворка Kotlin, ограничение Swift и синтаксис shell-скриптов.
- На эмуляторе Android API 36 обычный Debug открыл DEMO-каталог. Legacy
  REMOTE открыл вход без ссылки восстановления. В закрытой сборке с
  валидным URL ссылка «Забыли пароль?» появилась. С неверным URL открылся
  экран «Источник не настроен». У двух установленных сборок разные UID и
  приватные каталоги. Доставка почты в этой проверке не участвовала.

## Проверки на целевых устройствах

На Mac собрать и запустить `HackAtlas` Debug и `HackAtlasN2` `Debug-N2`
с валидным HTTPS, проверить возврат из фона, холодный запуск и изоляцию
Keychain и двух частных копий. В Release с переданным N2-флагом проверить
legacy. Для Android приёмка реальной почты и recovery со стендом API 36
и Mailpit относится к задаче 6.

## Публикация ветки

Выполните `git worktree list` и перейдите в каталог, рядом с которым указана
ветка `[feat/mobile-n2-debug-hosts]`. Команды ниже запускаются из этого каталога.

```powershell
if ((git branch --show-current) -ne 'feat/mobile-n2-debug-hosts') { throw 'Перейдите в worktree ветки feat/mobile-n2-debug-hosts.' }
git add -- docs/STATUS_MOBILE.md mobile/README.md mobile/androidApp mobile/iosApp mobile/shared/src mobile/artifacts/n2-debug-hosts-task5
if ($LASTEXITCODE -ne 0) { throw 'git add завершился ошибкой.' }
git diff --cached --check
if ($LASTEXITCODE -ne 0) { throw 'git diff --cached --check завершился ошибкой.' }
git commit -m 'feat(mobile): enable closed N2 in debug hosts'
if ($LASTEXITCODE -ne 0) { throw 'Коммит не создан.' }
git push -u origin HEAD
```

[Открыть сравнение с main](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-n2-debug-hosts?expand=1).
[Готовое описание PR](PR_DESCRIPTION.md).
