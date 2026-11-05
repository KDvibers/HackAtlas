# PR27: Gitleaks — 5 ноября2026

Причина двух красных checks (push/pull_request) одинакова: Gitleaks8.30.1
посчитал восемь значений старого поля отчёта контрольными API-ключами.
Все восемь независимо сверены с SHA-256 публичного api/openapi.yaml в коммите
392c37d2a83bad134a5554891fb1278cf5f8cdca. Это не credentials, токены или пароли.
CI run42056595563/job127346532353; Backend verify и Contract check в нём success.

В новых отчётах/helper поле названо contractSha256. Его значение и все native
результаты/installed APK SHA прежние. Так как CI сканирует --all, удаление старого
имени из рабочей копии не устраняет исторические находки. .gitleaksignore содержит
ровно восемь fingerprint: commit:file:rule:line. Целые коммиты/пути и правила не
отключены, история не переписывалась. Документация механизма:
https://github.com/gitleaks/gitleaks/blob/v8.30.1/README.md#gitleaksignore

## Проверка

Проверен Windows archive8.30.1 по официальному release checksum. Та же версия,
правила и команда, что в CI; после fetch refs сканируются те же32коммита:

```powershell
& mobile/build/ci-gitleaks/tool/gitleaks.exe git --redact=100 --no-banner '--log-opts=--all' .
```

До исправления:exit1/8находок. После:exit0/0находок. Четыре отдельные контрольные
проверки с действующим ignore-файлом, в том числе на том же пути/номере строки:

- contractSha256 с публичным digest:exit0.
- Новое старое поле с тем же digest:exit1 — историческое исключение его не скрывает.
- Новый synthetic accessToken:exit1.
- Новый synthetic токен под старым именем поля:exit1.

Synthetic значения созданы только в ignored build controls, не настоящие секреты.
Все выходы дополнительно сверены с JSON finding count и правилом generic-api-key.
Helper Python syntax PASS. Логи/контроли: mobile/build/ci-gitleaks/;
итог без секретов: ci-gitleaks-verification.json.

App/shared/backend/API/workflow/dependencies не менялись. Поэтому прежние236
тестов и native4/4+4/4 относятся к3ноября, повторно не запускались. Новая remote
проверка произойдёт после пользовательского push; текущие старые красные checks
сами от локальных изменений не сменятся.

## Обновить текущий PR27

```powershell
git add -- .gitleaksignore docs/STATUS_MOBILE.md mobile/scripts/android_tzdata_smoke.py mobile/artifacts/tzdata-compat
git diff --cached --stat
git commit -m "fix(ci): resolve contract digest false positives in Gitleaks"
git push origin fix/mobile-tzdata-compat
```

Новый PR не нужен, force/amend не нужны. Commit/push выполняет пользователь.
Новые блоки M3 не начинались.
