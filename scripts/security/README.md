# Проверка зависимостей перед выпуском

Используется публичный [OSV API](https://google.github.io/osv.dev/post-v1-querybatch/). Запрос содержит только имена Maven packages и разрешённые версии, без исходников, токенов, пользовательских данных и `.env`. Дополнительная библиотека Python не требуется. Проверяется фактический runtime graph Maven, а не только прямые зависимости в POM.

Из корня репозитория, после успешного `verify`:

```bash
cd backend
bash ./mvnw --batch-mode --no-transfer-progress dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
cd ..
python3 scripts/security/check_maven_dependencies.py --dependencies backend/target/runtime-dependencies.txt --output backend/target/dependency-audit.json
```

При наличии известных уязвимостей, недоступном API, пустом inventory или неполном ответе команда завершается ошибкой. Pagination обрабатывается до конца; нельзя заменять ошибку сети утверждением об отсутствии уязвимостей. Отчёт содержит дату и IDs обнаруженных advisory. Успех означает отсутствие совпадений в OSV на момент проверки: это не анализ достижимости, не полный security audit и не проверка OS/JRE/base images. Перед реальным deployment требуется повторить проверку с актуальными базами и отдельно проверить контейнерные образы.

Автообновление dependencies, bot PR и automerge не включены. Обновление совместимого Boot/BOM требует сборки и регрессионных проверок; контракт и авторизацию ради advisory не менять.
