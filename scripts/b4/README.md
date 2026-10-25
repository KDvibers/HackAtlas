# Локальная репетиция выпуска B4

`run_rehearsal.py` проверяет подготовленный release-stack из [infra/release](../../infra/release/README.md). Это локальная проверка на синтетических данных. Она не выполняет внешний deployment, не меняет DNS, не открывает production signup и не подключается к существующей базе.

Нужны Linux/WSL, Docker Engine, Docker Compose 2.24.4+, JDK 21 (`java` и `javac`), Python с зависимостями из [requirements-contract.txt](../requirements-contract.txt) и JAR после успешного `backend/mvnw verify`. Python использует стандартную библиотеку для процессов, HTTPS, cookie и файлов; PyYAML/jsonschema нужны для проверки трёх публичных ответов по каноническому OpenAPI.

Из корня репозитория:

```bash
python -m unittest discover -s scripts/b4 -p 'test_*.py' -v
python scripts/b4/run_rehearsal.py \
  --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar \
  --output-dir backend/target/b4-rehearsal
```

`--timeout 240` задаёт ожидание готовности и отдельного запуска миграций. Время загрузки базовых Docker images зависит от сети. Сборка использует копию указанного JAR во временном контексте; исходный JAR не перезаписывается.

## Что проверяется

- Случайное уникальное имя Compose project, отдельные volumes и временные file secrets. `.env` не читается; унаследованные переменные приложения/Compose не используются. Проверка эффективного Compose запрещает публикацию портов app/PostgreSQL и любых адресов, кроме двух proxy ports на `127.0.0.1`.
- Отдельный migrator process, runtime без Flyway, минимальные права PostgreSQL и реальное редактирование каталога через admin под runtime-ролью. Проверяются запреты DDL, изменения admin grants и удаления/редактирования audit.
- Реальные non-root users, read-only root filesystems, dropped capabilities и `no-new-privileges` у app/proxy/PostgreSQL.
- Caddy internal CA доверяется только явному HTTPS-клиенту этого запуска. Проверка сертификата и hostname включена; системное хранилище доверия не меняется. HTTP redirect, HSTS, защищённые admin cookie, CSRF и игнорирование поддельных forwarding headers проверяются через proxy.
- До внесения fixtures production каталог пуст и signup закрыт. Затем через SQL внутри только временной БД добавляется каноническая DEMO-карточка и два синтетических аккаунта. Случайный пароль хешируется настоящим Spring Argon2id encoder из проверяемого JAR; он не передаётся через argv и не выводится.
- Полный custom-format dump с SHA-256, отказ от перезаписи backup и существующей restore-базы, отказ при неверной checksum. Восстановление выполняется в новую БД, таблицы сравниваются по числу строк и SHA-256 упорядоченного JSON, Flyway проверяет восстановленную схему.
- `auth_session` намеренно исключается из равенства данных: все старые сессии должны быть удалены до подключения runtime. После переключения старые Bearer tokens возвращают 401, а три публичные GET проходят OpenAPI-проверку через HTTPS.

## Результат и ограничения

`report.json` содержит результат, проверки, image tags/IDs, отпечатки таблиц и фактическое время backup, restore и восстановления доступа через HTTPS. `commands.log` содержит названия этапов, exit codes и отредактированные сообщения ошибок, без SQL-ввода и HTTP credentials. Логи и отчёт находятся в игнорируемом `backend/target/`.

Скрипт дополнительно удаляет один синтетический аккаунт **после** backup. Старый snapshot возвращает его строку при restore: это явно отмечается как требование сверить позднейшие удаления перед открытием восстановленного сервиса. Отзыв сессий не доказывает полное удаление аккаунтов из резервных копий. `publicReleaseReady` остаётся `false` даже при техническом `PASS`.

Измеренные секунды относятся к маленькой локальной БД. Они не включают обнаружение аварии, действия оператора, передачу off-site backup, DNS или production-нагрузку. Регулярный backup и гарантированные RPO/RTO этим запуском не создаются.

После выполнения удаляются только контейнеры/volumes с совпадающими project/service/release labels, временные secrets, CA и dump. Уникальные собранные app/proxy images сохраняются для последующего Trivy scan; их точные IDs есть в отчёте. Если очистка не удалась, отчёт показывает `FAIL` и путь принадлежащего этому запуску временного каталога; чужие ресурсы не удаляются.
