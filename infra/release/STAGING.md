# Подготовка staging HackAtlas

Staging завершает серверную часть B4 и даёт мобильной команде HTTPS-окружение для M4. Стенд использует канонический API 0.1.1. Регистрация и DEMO в релизном Compose выключены. Проверки подтверждения почты и сброса пароля с Mailpit проходят в [закрытом N2](../lifecycle-test/README.md).

## Текущее состояние

На 12 ноября 2026 PostgreSQL Alpine без gosu и пересобранный Caddy находятся в `main`. Интеграция пересобранного restic прошла проверку на ветке `feat/release-restic-rebuilt`, коммит `a88fb91`. В [запуске External release readiness](https://github.com/KDvibers/HackAtlas/actions/runs/43088840175) прошли HTTPS, резервное копирование, чтение шифрованного архива, восстановление в карантин и Trivy. Критических, высоких и неопределённых находок в проверенных образах и restic не осталось. У restic зафиксирована одна LOW-находка.

Изменения restic вошли в [PR #62](https://github.com/KDvibers/HackAtlas/pull/62), его Backend CI прошёл. Сейчас базовая ветка PR — `fix/release-caddy-go-dependencies`. Чтобы доставить изменения в `main`, согласуйте целевую ветку PR. Перед запуском staging повторите **External release readiness** на конкретном SHA, который пойдёт на стенд. Запуск выше подтверждает кандидата `a88fb91`. Локально проверена конфигурация Compose для совместного дерева текущего `main` и кандидата restic. Внешний хост и DNS ещё не выбраны.

## 1. Зафиксировать параметры стенда

| Что согласовать | Что записать перед запуском |
| --- | --- |
| Хост и доступ | Linux amd64, Docker с Compose 2.24.4 или новее, провайдер или имеющаяся машина, регион, бюджет, ответственный за доступ и обновления. |
| HTTPS и сеть | Имя хоста, DNS, доступ Caddy к портам 80/443, способ выпуска сертификата и круг пользователей стенда. API base URL для клиента — `https://<согласованный-хост>/api/v1/`. |
| Данные | Отдельный Compose project и PostgreSQL volume. Подготовленные тестовые записи, доступ к админке и список сценариев для M4. |
| Резервные копии | Хранилище, независимое от узла PostgreSQL, отдельные права операторов, хранение ключа, частота backup и выгрузок удалений, сроки хранения, ответственный за сбои. |
| Измерение восстановления | Кто выполняет восстановление, какая потеря данных допустима (RPO), за какой срок восстанавливаем доступ (RTO) и как фиксируем результаты. |

Для внешнего запуска требуется согласование конкретного окружения по [B4 в дорожной карте](../../docs/ROADMAP.md#b4--stagingrelease). Репетицию можно повторить локально до выбора хоста. Пароли, ключ архива, дампы, выгрузки удалений и review остаются в закрытом хранилище.

## 2. Повторить локальную репетицию

После интеграции restic используйте чистый checkout выбранного SHA. Команды выполняются в Bash из корня клона с JDK 21, Python 3.12 и Docker Linux. Репетиция создаёт собственные ресурсы и использует локальный CA. Она не обращается к DNS staging.

```bash
(
  set -e
  mkdir -p backend/target
  output=$(mktemp -d "$PWD/backend/target/staging-readiness.XXXXXXXX")
  python3 -m venv "$output/venv"
  py="$output/venv/bin/python"
  "$py" -m pip install -r scripts/b1/requirements.txt
  "$py" -m pip check

  (
    cd backend
    bash ./mvnw --batch-mode --no-transfer-progress verify dependency:list \
      -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
  )
  "$py" scripts/b4/install_restic.py
  "$py" -m unittest discover -s scripts/b4 -p 'test_*.py'
  "$py" -m unittest discover -s scripts/security -p 'test_*.py'
  "$py" scripts/security/check_maven_dependencies.py \
    --dependencies backend/target/runtime-dependencies.txt \
    --output "$output/dependency-audit.json"
  "$py" scripts/b4/run_rehearsal.py \
    --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar \
    --output-dir "$output/rehearsal"
  "$py" scripts/security/run_container_audit.py \
    --rehearsal-report "$output/rehearsal/report.json" \
    --output-dir "$output/container-audit" --postgres-alpine-without-gosu
  "$py" scripts/security/audit_recovery_tool.py \
    --restic backend/target/restic-0.19.1-rebuilt \
    --output-dir "$output/recovery-tool-audit"
)
```

Все отчёты локального прогона записываются в `backend/target/staging-readiness.*`. Сверьте `rehearsal/report.json`, результаты сканирования образов, доказательство по конкретному ID PostgreSQL и SHA-256 restic. Скачайте также артефакт `external-release-readiness` из [Actions](https://github.com/KDvibers/HackAtlas/actions/workflows/release-readiness.yml) для выбранного SHA. Если изменились образы, JAR или операторский бинарник, результат привязывается к новым ID и hash после повторной проверки.

## 3. Запустить согласованный стенд

1. На выбранном хосте подготовьте отдельные каталоги, имя Compose project и параметры `HACKATLAS_SITE`, `HACKATLAS_DB_NAME`, `HACKATLAS_APP_IMAGE`, `HACKATLAS_PROXY_IMAGE`, `HACKATLAS_POSTGRES_IMAGE` и `HACKATLAS_SECRET_DIR`. Сгенерируйте секреты через `prepare_secrets.py` в приватном каталоге native Linux. Используйте одни и те же теги образов и имя project во всех операторских командах. Подробности — в [релизной инструкции](README.md#секреты).
2. На хосте используйте базовый `infra/release/compose.yaml` с `--env-file /dev/null` и явным `--project-name`. Для локальной проверки подключайте `compose.local.yaml`. В базовой конфигурации порты 80/443 публикует Caddy, а backend и PostgreSQL доступны по внутренним сетям Compose. В локальной конфигурации Caddy слушает только `127.0.0.1`.
3. Соберите релизный образ PostgreSQL из `infra/release/postgres` и образы `app` и `proxy`. Перед использованием образов сверьте их ID с результатами репетиции и аудита. Образы, пересобранные на хосте с новыми ID, требуют своих проверок.
4. Проверьте `docker compose config --quiet`. Поднимите PostgreSQL и дождитесь healthcheck. Выполните `--profile ops run --rm migrate` под ролью Flyway. После успешной миграции запустите `app` и `proxy` и дождитесь готовности. Пример команд сборки и порядка запуска — в [релизной инструкции](README.md#локальный-запуск-после-успешной-сборки-и-тестов).
5. С компьютера разработчика и мобильного устройства проверьте разрешение имени, доверие сертификату, HTTP→HTTPS и readiness. Затем проверьте каталог, подробности известного тестового события и метаданные фильтров. Передайте мобильной команде SHA, base URL, ID события и результаты smoke.

Пример HTTPS smoke. Подставьте согласованные значения `HACKATLAS_SITE` и `staging_event_id` до запуска.

```bash
site="https://${HACKATLAS_SITE:?Set the approved staging hostname}"
event_id="${staging_event_id:?Set a published staging event UUID}"
curl --fail --silent --show-error "$site/actuator/health/readiness"
curl --fail --silent --show-error "$site/api/v1/hackathons"
curl --fail --silent --show-error "$site/api/v1/hackathons/$event_id"
curl --fail --silent --show-error "$site/api/v1/metadata/filters"
```

Ожидаются `UP`, HTTP 200 на публичных GET и данные из согласованного набора стенда. Проверьте фильтр и поиск по известному событию, пустую выдачу и гостевую закладку на клиенте. Проверьте, что регистрация закрыта, а admin cookie защищён. В текущей конфигурации backend видит IP прокси, поэтому IP rate limit общий для запросов через Caddy. См. [ограничения прокси](README.md#изоляция-и-доступ).

## 4. Проверить backup, восстановление и откат

1. Запишите UTC-границу **до** запуска `backup.py`, имя project, имя БД и SHA-256 дампа. Сохраните дамп с контрольной суммой и выгрузку квитанций `export_deletions.py` в проверенный шифрованный архив. Поздние выгрузки сохраняйте отдельными снимками. Зафиксируйте полные snapshot ID и результат `check` с чтением данных. Команды приведены в [инструкции архива](RECOVERY_ARCHIVE.md).
2. Извлеките выбранный backup и позднюю выгрузку по полным snapshot ID. Восстановите в новую `hackatlas_restore_<unique>`. Проверьте `QUARANTINED`, отсутствие runtime-доступа и отзыв восстановленных сессий. Исходная БД остаётся доступной для диагностики. При переносе с Bookworm на Alpine используйте логический dump и отдельный volume. См. [backup и восстановление](README.md#backup-и-восстановление).
3. Перед открытием остановите все процессы записи в источник. Подготовьте приватный review через `prepare_restore_review.py` и проверьте полноту удалений с границы backup. Если источник потерян и история обрывается после последнего export, оставьте копию в карантине до восстановления достоверной истории. После сверки примените `approve_restore.py`, который применяет подтверждённые удаления и открывает runtime-доступ одной транзакцией. См. [операторское одобрение](README.md#проверка-и-одобрение-карантина).
4. Переключите только целевой runtime на одобренную БД. Повторите HTTPS smoke, проверьте доступные записи, удалённые аккаунты и отклонение прежних сессий. Запишите фактический интервал потери данных и время от начала восстановления до успешного smoke.
5. До обновления сохраните идентификаторы предыдущего рабочего выпуска и снимок БД. Если выпуск остановился до открытия, оставьте рабочее окружение на прежней версии. Для отката после переключения проверьте совместимость схемы со старым приложением и учтите новые записи и удаления. Если нужен restore, повторите карантин и review для новой БД. Держите предыдущий volume до проверки и согласно согласованному сроку хранения.

## 5. Передать стенд на приёмку

В отчёте укажите SHA, ссылку на Actions, ID образов и SHA-256 restic, URL, время smoke, результат миграций и HTTPS, результат восстановления, RPO/RTO и план отката. В общий отчёт не включайте Bearer, пароли, содержимое дампов, выгрузки UUID и private review.

Напарнику нужны base URL, ID опубликованного тестового события, сборка клиента и список сценариев. Проверки M4 на Android и iPhone, включая подключение через доверенный HTTPS, входят в [план приёмки](../../docs/ROADMAP.md#m4--качество-и-устройства). Для публичного открытия действуют отдельные [предрелизные условия](../../docs/ROADMAP.md#условия-перед-публичным-запуском).
