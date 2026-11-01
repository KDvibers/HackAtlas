# Planned account lifecycle fixtures

Все данные синтетические. `A…A`/`R…R` — shape-примеры, не действующие secrets и не
образец генерации. Время соответствует clock 2026-11-01T12:00:00Z, не live API.

Этот manifest отдельно проверяется scripts/validate_contract.py. Шесть старых
примеров и manifest сохранены для нынешнего mobile. Gradle читает root `*.json`.
Pending/verified примеры показывают optional bool; старый profile.json — missing/UNKNOWN.
Новые ошибки используют LifecycleProblem. Операции пока planned, HTTP не реализован.
[Совместимость](../../ACCOUNT_LIFECYCLE_COMPATIBILITY.md).
