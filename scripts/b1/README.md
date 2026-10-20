# B1: проверка настоящего HTTP API

Runner запускает **уже собранный** backend JAR с отдельным PostgreSQL 17.11, DEMO seed и фиксированными часами `2026-10-12T10:00:00Z`. Java, Docker и Python должны работать в одном Linux-окружении; на Windows используйте WSL. Общая dev-БД и `infra/.env` не используются.

После завершения сборки runner копирует JAR в свой временный Linux-каталог и запускает эту копию. Это фиксирует проверяемый артефакт и не удерживает исходный JAR на Windows-диске. Не заменяйте JAR во время первоначального копирования; обнаруженное изменение размера/mtime завершает проверку с ошибкой.

## Запуск

Из корня репозитория в Linux/WSL нужны Java 21, доступный Docker daemon, Node.js 24 (проверен 24.19.0) и Python 3.12+ с модулем `venv`.

```bash
python3 -m venv scripts/b1/.venv
scripts/b1/.venv/bin/python -m pip install -r scripts/b1/requirements.txt
scripts/b1/.venv/bin/python -m pip check
npm ci --prefix tools/bruno --ignore-scripts --no-audit --no-fund

cd backend
bash ./mvnw --batch-mode --no-transfer-progress verify
../scripts/b1/.venv/bin/python src/test/resources/validate_http_contract.py \
  --schema ../api/openapi.yaml --responses target/catalog-contract-responses.json
cd ..

scripts/b1/.venv/bin/python scripts/b1/run_contract_checks.py \
  --jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar
```

Для отдельного Node executable: `--node /absolute/path/to/node`. Для установленного в другом месте CLI: `--bruno-cli /absolute/path/to/@usebruno/cli/bin/bru.js`. Runner проверяет версии Schemathesis и Bruno до запуска сервера. Зависимости устанавливаются локально; глобальные CLI и Bruno GUI не требуются.

В текущей Windows-среде Docker доступен пользователю `root` WSL Ubuntu. Запускайте эти Linux-команды внутри `wsl -d Ubuntu -u root`; каталог репозитория там — `/mnt/c/Daniyar/MyProjects/HackAtlas`. Это не меняет пользователя Git и не требует выполнять Git-команды от root. Если в дистрибутиве нет поддержки `venv`, сначала подготовьте отдельное Python-окружение с этими зависимостями; runner не устанавливает системные пакеты.

## Что проверяется

1. Существующий `scripts/validate_contract.py`: канонический OpenAPI 3.1 и все шесть общих fixtures. Profile/auth fixture остаются данными для offline-проверки и не означают реализацию B2.
2. Двенадцать HTTP-сценариев: точное сравнение DEMO-страницы `NEWEST`, пустой страницы и AI details с fixtures; metadata; поиск с AND-фильтром; ошибки query/UUID/404. Каждый ответ проверяется JSON Schema Draft 2020-12 с `FormatChecker`.
3. Десять запросов и assertions [Bruno collection](../../tools/bruno/README.md) к тому же серверу.
4. Schemathesis **4.32.2**: только операции, у которых `x-phase: B1`. До старта runner требует ровно три публичных GET: каталог, details, metadata. Фазы `examples,fuzzing`, один worker, seed `20261020`, до 100 генерируемых примеров на операцию. Лимит и seed можно явно изменить аргументами `--max-examples` и `--seed`.

Schemathesis проверяет `not_a_server_error`, `status_code_conformance`, `content_type_conformance`, `response_headers_conformance`, `response_schema_conformance`, `negative_data_rejection`. Проверка `positive_data_acceptance` не включена: отдельные валидные даты могут образовывать запрещённый `startsFrom > startsTo`, описанный в `api/README.md`, но не выраженный независимыми параметрами OpenAPI. Допустимые сочетания фильтров проверяют deterministic smoke и интеграционные тесты; нарушения диапазона обязаны вернуть 400. Это явная граница генерации, а не список исключений для упавших примеров.

Metadata показывает доступные значения каталога. Общий fixture содержит полный enum форматов, включая `HYBRID`; среди пяти DEMO-событий доступны только `ONLINE` и `OFFLINE`. Остальные значения metadata сравниваются с fixture без зависимости от порядка.

## Изоляция и результат

PostgreSQL использует случайное имя, метку владельца, случайный loopback-порт и `tmpfs` вместо постоянного volume. Пароль генерируется в памяти и передаётся процессам через environment. Сервер привязан к `127.0.0.1`, работает в новом временном каталоге и не читает локальный dev `.env`. Проверки не получают URL внешнего сервера, не следуют redirect и не используют proxy. Они не отправляют реальные пользовательские данные внешним сервисам.

При завершении, ошибке или обычном SIGTERM/SIGINT runner останавливает свою JVM и удаляет только контейнер со своей меткой. После SIGKILL/падения хоста автоматическая очистка невозможна: имя имеет префикс `hackatlas-b1-`, точную принадлежность проверяют по `hackatlas.b1-run`. Не удаляйте контейнеры и volumes по общим маскам.

Ненулевой exit означает провал проверки. Ошибки не подавляются и не записываются в baseline исключений. Временные логи/БД удаляются после проверки; stdout содержит результат Bruno и Schemathesis. Это ограниченный контрактный прогон, не нагрузочный тест и не полный security audit. CI повторяет его после Maven `verify` и отдельной проверки HTTP-ответов `CatalogIT`.

## Pins и источники

`requirements.in` задаёт прямые зависимости; `requirements.txt` фиксирует полный набор версий вместе с существующим fixture validator. Обновлять их нужно согласованно с реальным прогоном. `package-lock.json` Bruno фиксирует npm-дерево и integrity.

- [Schemathesis 4.32.2 — upstream release](https://github.com/schemathesis/schemathesis/releases/tag/v4.32.2), [PyPI metadata](https://pypi.org/pypi/schemathesis/4.32.2/json), [официальный CLI reference](https://schemathesis.readthedocs.io/en/stable/reference/cli/).
- [Bruno CLI — официальный registry](https://registry.npmjs.org/@usebruno%2fcli), [официальная документация CLI](https://docs.usebruno.com/bru-cli/overview).
- [Node.js 24.19.0 — официальные SHA256](https://nodejs.org/dist/v24.19.0/SHASUMS256.txt).
- [setup-node v7.1.0](https://github.com/actions/setup-node/releases/tag/v7.1.0), закреплён полный SHA `949feb2413d6458794dcd2491c4babbbce0c15c1`, проверенный через upstream Git tag.
