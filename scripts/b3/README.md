# B3: проверка модерации и удаления аккаунта

Runner проверяет собранный JAR через настоящий HTTP и общую OpenAPI-схему. Он создаёт одноразовые аккаунты в отдельной PostgreSQL, отправляет предложения/сообщения об ошибке, затем проверяет удаление аккаунта с отзывом всех его сессий. После HTTP-проверок выполняются [24 запроса Bruno](../../tools/bruno-b3/README.md). Внешний сервер и существующие аккаунты не используются.

## Запуск

Используются прежние закреплённые Python dependencies, Node **24.19.0** и Bruno CLI **4.2.1**. Второе npm/Python-дерево не требуется. Нужны Java 21, Docker с Linux containers и Python 3.12+; на текущей Windows-машине запуск выполняется внутри WSL Ubuntu пользователем с доступом к Docker.

Из корня проекта в Linux/WSL:

```bash
python3 -m venv scripts/b1/.venv
scripts/b1/.venv/bin/python -m pip install -r scripts/b1/requirements.txt
scripts/b1/.venv/bin/python -m pip check
npm ci --prefix tools/bruno --ignore-scripts --no-audit --no-fund

cd backend
sh ./mvnw --batch-mode --no-transfer-progress verify
../scripts/b1/.venv/bin/python src/test/resources/validate_http_contract.py \
  --schema ../api/openapi.yaml --responses target/b3-contract
cd ..

scripts/b1/.venv/bin/python scripts/b3/run_contract_checks.py \
  --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar
```

Для отдельной установки доступны `--node /absolute/path/to/node` и `--bruno-cli /absolute/path/to/bru.js`. Окружение текущей машины описано в [backend README](../../backend/README.md#проверенный-путь-на-текущей-windows-машине). Git, push и PR эти команды не выполняют.

## Покрытие

- Все **три операции B3**: `POST /suggestions`, `POST /hackathons/{id}/reports`, `POST /me/delete-account`. Runner проверяет точный набор `x-phase: B3` и требование BearerSession перед запуском.
- Предложение и report возвращают `202` с `PENDING` и UUID. Публичный каталог после отправки остаётся неизменным: форма пользователя не публикует и не исправляет событие автоматически.
- Проверяются `401` без сессии, `400` для небезопасного URL/неизвестного reason/невалидного UUID и `404` для отсутствующего события. HTTP, JavaScript scheme и URL с userinfo не принимаются; тесты не переходят по этим адресам.
- Удаление с неправильным паролем возвращает `401`, аккаунт остаётся доступным. Пробелы в пароле значимы. Правильный пароль даёт `204`, обе сессии аккаунта получают `401`, повторный login невозможен.
- Второй аккаунт и его закладка остаются доступными. Повторная регистрация удалённого email получает новый ID и пустые личные данные; прошлые интересы, закладки и отметки не восстанавливаются.
- Каждый HTTP-ответ проверяется по canonical schema, status, media type и форматам. `204` обязан быть без тела. У выдачи токена проверяется `no-store`, JSON API не создаёт cookie-сессию.

Rate limits, транзакционность, содержимое PostgreSQL, admin browser-session/CSRF и обновление сохранённого после редакторских изменений проверяются Java integration tests. Их JSON-экспорты лежат в `backend/target/b3-contract/`; отдельный validator требует в том числе ошибки `429`. В standalone runner лимиты не выключаются и не повышаются.

[B1](../b1/README.md) и [B2](../b2/README.md) прогоны остаются обязательными regression checks. Schemathesis по-прежнему генерирует запросы к публичным B1 endpoints; B3 использует явный сценарий состояний и не заявляется как генеративный security audit. Админские HTML-формы не являются новым mobile API и не добавляются в OpenAPI.

## Изоляция и очистка

Runner переиспользует проверенный `isolated_server` из B2. **Каждый запуск создаёт новую БД**; это не подключение к существующему B2-серверу. У ресурсов поэтому остаются имена `hackatlas-b2-…` и метка `hackatlas.b2-run`. Случайный пароль PostgreSQL передаётся через process environment; данные находятся в `tmpfs`, а порт доступен только через loopback. JAR запускается из отдельной временной Linux-копии, dev signup и DEMO включены явно, локальный `infra/.env` не читается.

Bruno получает случайный пароль и синтетические email через process environment. Токены существуют только в памяти. Коллекция запускается из временной копии без локального `.env`; redirects и proxy отключены. В stdout нет request/response bodies, вывод дополнительно редактирует распознаваемые секреты. Schema errors не печатают auth response.

При завершении и обычном SIGINT/SIGTERM останавливается созданная JVM и после проверки ownership label удаляется только собственный контейнер. При SIGKILL/сбое хоста автоматическая очистка невозможна: проверяйте конкретный контейнер и `hackatlas.b2-run`, не удаляйте ресурсы по общей маске. Локальный лог можно сохранить в игнорируемый `backend/target/b3-checks/`; не включайте HTTP trace с токенами или паролями.

Ненулевой exit означает провал проверки. CI запускает B3 schema validation и standalone runner дополнительно к существующим B1/B2 проверкам. Публичный deployment и доступ к реальным данным этим прогоном не выполняются.
