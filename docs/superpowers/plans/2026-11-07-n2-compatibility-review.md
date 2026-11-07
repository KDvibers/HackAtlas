# N2 Compatibility Review Plan

> **For agentic workers:** use superpowers:executing-plans inline. Один исполнитель;
> commit/push/PR выполняет пользователь. Продуктовые предложения не реализовывать.

**Goal:** уточнить совместимость N2 после PR31, передать обезличенные HTTP fixtures
и подготовить решение legacy delivery-address gap для совместного согласования.

**Architecture:** существующий закрытый кандидат0.2.0-test.1 и канонический0.1.1.
Меняются документы и тестовые exports/fixtures; runtime/auth/SQL/mobile прежние.

**Tech Stack:** текущие Java21/Spring Boot/PostgreSQL/Testcontainers/Mailpit,
Python unittest/jsonschema; новые зависимости не нужны.

**Spec:** [матрица N2](../specs/2026-11-05-account-lifecycle-client-matrix.md)
и пять замечаний мобильного review, переданных пользователем7ноября.

## Global Constraints

- api/openapi.yaml, api/README.md, канонические fixtures и серверная авторизация прежние.
- Opaque Bearer, без JWT/refresh; PASSWORD_RESET_REQUIRED только login.
- 9 pending guards прежние; любой401/403 не означает global logout.
- Pending bytes/offline required policy, legacy migration и strict rollout не приняты.
- Mobile/STATUS_MOBILE не менять; не трогать данные запущенного пользовательского стенда.

## Review Focus

- Экспорт не сохраняет token/OTP/grant/request credentials и лишние headers.
- Delete-password401 + GET/me200 используют тот же Bearer для pending и verified.
- Saved и applications, а также все private UI entry points учтены вместе.
- Legacy delivery-address gap остаётся явным gate, в том числе после restore.
- Нельзя выдать schema/HTTP fixtures за мобильную приёмку или утверждение offline policy.

## Task 1: фактические HTTP-примеры

Files: backend/src/test/java/com/hackatlas/{B2HttpSupport,LifecycleApiIT}.java;
scripts/lifecycle/{validate_http,test_test_contract,export_mobile_examples,test_mobile_examples}.py;
docs/examples/n2-http/{README.md,responses.json}.

- [x] RED: validator отвергает противоречивые/чувствительные headers; отсутствие exporter.
- [x] Добавить только N2 header capture и два integration tests: wrong-delete password
  не отзывает pending/verified Bearer; private guards сохраняют правильный результат.
- [x] Минимальный exporter выбирает реальные ответы, проверяет пары, обезличивает
  идентичности, сохраняет важные headers и нерабочие proof placeholders.
- [x] Java verify от UID1000, N2 HTTP validation; сгенерировать и проверить fixtures.

## Task 2: совместимость и предложение

Files: api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md; docs/superpowers/specs/
2026-11-05-account-lifecycle-client-matrix.md и2026-11-07-legacy-account-recovery-design.md.

- [x] Уточнить contextual401, оба private stores, detail/calendar/editor/import.
- [x] Отделить legacy offline от предлагаемого required cold start; обе продуктовые
  политики оставить неутверждёнными; minimum client version не выдумывать.
- [x] Сравнить три legacy-пути по сложности, стоимости/поддержке и совместимости;
  указать рекомендацию, restore gap, решения и критерии следующего отдельного этапа.

## Task 3: проверка и передача

- [x] Canonical OpenAPI/fixtures + transition/lifecycle Python tests PASS.
- [x] Проверить неизменность runtime/API/mobile, redaction, ссылки, Gitleaks и diff.
- [x] Обновить только STATUS_BACKEND, записать реальные результаты и ограничения.
- [x] Самостоятельный review всей ветки; оставить unstaged changes и дать точные
  команды commit/push/PR пользователю, без исполнения этих действий.
