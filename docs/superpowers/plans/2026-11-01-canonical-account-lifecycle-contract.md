# План переходного account lifecycle контракта

> Исполнение одним агентом, superpowers:executing-plans; commit/push/PR выполняет пользователь.

**Цель:** канонический OpenAPI 0.1.1 с будущими lifecycle операциями и проверенной
совместимостью с нынешними backend/mobile. Почту, runtime endpoints и миграции не делать.
**Основание:** merged PR #22 и поручение подготовить canonical contract/compatibility plan.
**База:** main `7f6795530489e1be409c9457d10d83f4e5645761`, чистая ветка
`chore/account-lifecycle-contract`. Обычный main CI прошёл; это не security readiness.

## Решение и границы

Использовать переходный 0.1.1: emailVerified optional boolean, без default/null;
missing = UNKNOWN, а не true/false. Пять новых POST — отдельная фаза ACCOUNT_LIFECYCLE,
`x-implementation-status: planned`. Старые B1/B2/B3 и Bearer/refresh invariants не ослаблять.
Целевой 0.2 с required flag и enforcement — будущий отдельный этап после готовности клиента/сервера.

Текущий клиент читает Profile через ignoreUnknownKeys и Problem.code как String,
но не реализует verification UI. Старый examples/manifest.json содержит ровно 6
примеров, что проверяется мобильным тестом: сохранить его и добавить отдельный
lifecycle manifest. Никаких mobile source/STATUS_MOBILE/Gradle изменений.

## Task 1 — строгие проверки переходного контракта

Файлы: `scripts/validate_contract.py`, `scripts/test_contract_transition.py`.

- [x] Добавить тесты: точные 5 planned routes/security, запрет refresh и подмены
  старых фаз/публичности, сохранение обязательных старых Profile полей, optional
  bool без default/null, grant отдельно от AuthResponse, два набора fixtures.
- [x] Наблюдать RED на текущем 0.1.0/старом validator; реализовать проверку
  точных planned routes. Нельзя обойти её произвольным x-phase/planned marker.

## Task 2 — canonical OpenAPI / семантика / fixtures

Файлы: `api/openapi.yaml`, `api/README.md`, `api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md`,
новый `api/examples/account-lifecycle/` с собственным manifest; документы proposal/design/DECISIONS.

- [x] Перенести reviewed delta, адаптировав stage/optional flag/metadata будущих guard.
  Старые status maps/Problem не расширять; LifecycleProblem и будущие 403 компонентов
  пока не подключать к старым маршрутам. Это результат проверки oasdiff.
  Сохранить старые шесть примеров, request DTO, текущий AuthResponse envelope.
- [x] Написать матрицу старый/обновлённый клиент × нынешний/будущий backend;
  закрытая legacy работа не подтверждает почту. REQUIRED mode запрещает downgrade
  к legacy при missing flag или 404 новых операций; единый контролируемый cutover.
- [x] Описать этапы rollout/rollback, owner/gates, saved/import PR #21, restore guards,
  ошибки 400/401/403/429, риски register 409, отсутствие runtime capability negotiation.
- [x] Пометить прежний proposal историческим; patch не применять к новой базе.

## Task 3 — совместимость и итоговая проверка

- [x] Получить oasdiff из официального release с checksum; сравнить 0.1.0→0.1.1,
  записать findings и их практические последствия без suppression.
- [x] Kotlin 2.2.21 + serialization 1.9.0: воспроизвести реальные текущие ProfileDto/
  ProblemDto с legacy/false/true и новыми кодами. Harness только ignored target;
  это не Android/iOS/полный KMP build.
- [x] Contract validator + negative tests; Maven verify и fresh HTTP exports против
  нового canonical; существующие B1/B2/B3 scope gates остаются неизменными.
- [x] Gitleaks, ссылки/JSON/YAML/whitespace/scope; собственный STATUS, отчёт и точные
  пользовательские git команды. Новые runtime операции не запускались/не реализованы.
