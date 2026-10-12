# Проверка контракта

Из корня репозитория в PowerShell, при установленном Python 3.12:

```powershell
# Только при первом запуске, если .venv ещё нет:
py -3.12 -m venv .venv

.\.venv\Scripts\python.exe -m pip install -r .\scripts\requirements-contract.txt
.\.venv\Scripts\python.exe .\scripts\validate_contract.py
.\.venv\Scripts\python.exe -m pip check
```

Активация окружения и изменение ExecutionPolicy не требуются. После каждой команды `$LASTEXITCODE` должен быть `0`; при ошибке сначала устранить её. Проверено с Python 3.12.14. Все использованные прямые и транзитивные зависимости закреплены в `requirements-contract.txt`; это инструменты разработчика, не runtime зависимости приложения. JDK, Docker и работающий API для этой проверки не нужны.

Скрипт проверяет:

- локальные `$ref` и отсутствие внешних ссылок, требующих отдельного согласования;
- OpenAPI 3.1 через `openapi-spec-validator`, включая уникальность `operationId` и обязательные параметры пути;
- проектные правила v0.1.0: `x-phase` B1/B2/B3, ровно три публичные операции каталога B1, auth register/login/logout на B2, opaque BearerSession без refresh endpoint/token и авторизацию остальных операций;
- JSON Schema моделей и все шесть demo JSON из `api/examples/manifest.json`, включая форматы date, date-time, email, UUID и URI. Для проверки URI в зависимостях явно закреплён `rfc3986-validator`; без него jsonschema может молча пропустить этот формат.

Используется документированный вызов `validate(api, cls=OpenAPIV31SpecValidator)`: [Python API openapi-spec-validator](https://openapi-spec-validator.readthedocs.io/en/latest/python.html). Проверки `operationId` и path parameters выполняет библиотека; собственного дублирующего валидатора для них нет. При согласованном изменении API обновить соответствующие проектные правила скрипта вместе с контрактом.

Успешный результат содержит `PASS` для спецификации, проектных правил и каждого fixture; ошибка возвращает ненулевой код. Скрипт не меняет `api/` и работает без сетевых запросов после установки зависимостей.

Проверка не запускает Android/iOS, сервер или PostgreSQL, не проверяет реальные HTTP-ответы, реализацию безопасности, согласованность IANA timezone с instant, сортировку/фильтрацию или бизнес-правила между полями. Это не интеграционные тесты. Markdown-ссылки, hash референса и визуальная приёмка также не входят в этот скрипт.
