# Проверка предложения, 1 ноября 2026

Исторические результаты PR #22 на базе 0.1.0. Новая проверка переходного canonical
0.1.1 — в [отдельном отчёте](../../research/2026-11-01-account-lifecycle-compatibility.md).

**Это schema/design review, не проверка реализованной авторизации.**

| Проверка | Результат |
|---|---|
| Base semantic SHA256 и version 0.1.0 | Совпадают с README |
| Применение 50 операций JSON Patch к копии | PASS; исходный файл не изменён |
| Полный кандидат через openapi-spec-validator | PASS, OpenAPI 3.1 |
| 6 synthetic response fixtures из examples | PASS: Profile/challenge/grant и 3 Problem |
| 3 корректных request payloads | PASS: code, email, reset confirm |
| 13 отрицательных schema случаев | Все отвергнуты: число вместо code, неверная длина/Unicode, плохой challenge/email, extra fields, короткий пароль, отсутствующий/неверный emailVerified и попытки назначить его через register/PATCH |
| Изменения security declarations | 2 новых verification POST требуют Bearer; 3 reset POST публичны |
| Совместимость envelopes | RegisterRequest и AuthResponse shape сохранены; вложенный Profile изменён и совместимость не обещается |
| Действующий Starter и fixtures | PASS через scripts/validate_contract.py |
| JSON/UTF-8/ссылки/README snippet/scope/whitespace | PASS, 11 файлов только в docs; canonical и код не изменены |
| Gitleaks всех changed/new файлов | PASS, утечек нет |

Сборка кандидата и проверки выполнены локально обычным пользователем UID 1000.
Полная сгенерированная копия и расширенный проверочный helper находятся в ignored
`backend/target/account-lifecycle-openapi.candidate.yaml`,
`validate-account-lifecycle-proposal.py`,
`account-lifecycle-proposal-validation.log`. Воспроизводимая сборка кандидата есть
в [README](README.md); секреты, SMTP и работающий backend для неё не нужны.

Авторский review проверил pending allowlist, привязку code к principal/purpose,
anti-enumeration границы, отзыв browser admin и backup rollback пароля. Независимый
агент не подключался по предпочтению пользователя. Направление ещё должен выбрать
напарник/пользователь; это не заменено проверкой синтаксиса.

**Не выполнялись и не заявляются:** новые HTTP endpoints, отправка писем, миграции,
отзыв реальных сессий, runtime race/security tests, Android/iOS UI и store-проверки.
Будущая матрица приёмки — в [дизайне](../../superpowers/specs/2026-11-01-account-lifecycle-design.md).
Maven/мобильные сборки ради изменений документов не повторялись.
