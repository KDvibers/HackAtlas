# Проверка контракта

`python scripts/validate_contract.py` проверяет JSON Schema, локальные `$ref` и шесть demo JSON. Требуются PyYAML и jsonschema в virtual environment; их точные версии для CI должен зафиксировать Codex при bootstrap. Это вспомогательная developer-проверка, а не обязательная runtime dependency приложения.

Она **не** запускает Android/iOS, сервер или PostgreSQL и **не** заменяет полноценный OpenAPI validator и интеграционные тесты.
