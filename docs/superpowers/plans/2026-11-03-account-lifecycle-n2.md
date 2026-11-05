# N2 — полный lifecycle в закрытом тестовом профиле

**Продолжено пользователем 5 ноября 2026.** WIP в исходном checkout;
PR25 merged в main090935b. Локальная реализация/проверки завершены; CI main
отдельно упал на скачивании restic, fresh download здесь также не подтверждён.
Итог и ограничения в STATUS_BACKEND, canonical0.1.1 прежний.

> Исполнение: superpowers:executing-plans и TDD, один исполнитель, один финальный
> read-only reviewer безопасности. Commit/push/PR делает пользователь.

**Goal:** пройти регистрацию/письмо/подтверждение и reset/code/grant/new password
через HTTP и настоящий локальный SMTP, не активируя это на обычном сервере.
**Architecture:** профиль lifecycle-test, Java/Spring Mail, bounded in-memory queue,
N1 JdbcTemplate store, существующий PasswordEncoder и отдельная PostgreSQL test DB.
Тестовый кандидат контракта строится из Starter 0.1.1; канон не изменяется.
**Tech Stack:** Java21/Boot4.1.2/PostgreSQL17.11; Spring mail starter через Boot BOM;
Mailpit v1.33.0 как локальный test SMTP sink, image digest закрепить после проверки.
**Spec:** [дизайн](../specs/2026-11-01-account-lifecycle-design.md),
[план совместимости](../../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md).
**Base:** N1 `98f1cbeb3847009865c1be9ff8616a74316c7dfe`, PR #25 ещё open;
ветка `feat/account-lifecycle-test-runtime` поверх N1. Main `40cd841`/PR #24
содержит mobile profile edit, CI success. N1 merge не выполняется агентом.

## Global Constraints

- Пользователь передал ответ друга: строгий 0.2 **не согласован**. Canonical API0.1.1,
  его шесть legacy fixtures, mobile sources/STATUS_MOBILE не менять.
- PASSWORD_RESET_REQUIRED — только API login. На защищённых запросах revoked session
  →401; pending restriction →403 EMAIL_NOT_VERIFIED. Browser login остаётся generic.
- Полный runtime только dev/test + lifecycle-test, без prod/release, только loopback
  и отдельная тестовая БД. Обычный запуск сохраняет прежний API; публичный signup
  и внешний deployment не включать. Key не имеет default и не хранится в БД.
- Opaque Bearer, без JWT/refresh. Pending TTL30мин, verified прежний TTL; no renewal.
- Code8 ASCII/10мин/5 persisted attempts; cooldown60с/max3active; grant256bit/5мин/one-use.
- Request: IP10/15мин, email5/час + cooldown60с; verify/reset-confirm IP30/15мин.
  Отдельно от login quotas, bounded maps, одни правила для неизвестного email.
- Public reset: lookup только в job после generic202; queue full→503 для всех.
  До ACK не выяснять существование аккаунта. SMTP acceptance не обещает delivery.
- Worker uses stored delivery address; stale expected version/deleted/expired job
  отбрасывается. Code/token не сохранять в mail jobs/logs. Notification best effort
  после commit reset; её сбой не отменяет пароль и не превращает 204 в ошибку.
- Счётчик неверных попыток commit до HTTP400; successful action/proof consumption
  в одной транзакции. User-first lock для login/reset/delete/admin mutations.
- Native/admin credential version checked. Restore отзывает tokens/proofs и требует
  fresh reset для lifecycle DB; old backup upgrade/preparation idempotent по restoreId.
- Существующие миграции до V8 не менять. Реальные БД/адреса/письма не использовать.

## Review Focus

1. Public reset existence/timing leak, queue overflow, plaintext/log leakage.
2. Wrong principal/purpose/version, delayed jobs after reset/delete and response loss.
3. Concurrent attempts/consumption/login/delete/admin mutation; rollback and revocation.
4. Test-only config escaping into prod/release or ordinary clients, source downgrade.
5. V6/V8/V9 restore preparation/approval/replay and revival of old password/cookie.

## Task 1 — профиль, локальный SMTP, очередь и admission

**Files:** auth/lifecycle/LifecycleSettings.java, LifecycleRuntimeConfiguration.java,
LifecycleMailQueue.java, LifecycleRateLimiter.java, LifecycleMailer.java;
application-lifecycle-test.yaml, pom.xml; unit tests этих boundaries.
**Interfaces:** Settings(Environment) exposes enabled/key/SMTP/capacity; queue.submit(Runnable)
rejects503 and close drops pending jobs; limiter.request(ip,email)/verify(ip);
mailer.code(address,purpose,Challenge) и passwordChanged(address), без logging PII.

- [x] RED tests: prod/release/non-loopback/missing key rejected, disabled boot no key;
  queue overflow/restart/timeouts, independent quotas/cooldown/capacity.
- [x] Реализовать settings guard, bounded queue и limiter. Добавить Spring mail
  starter (BOM), SMTP timeouts/debug off. Закрепить официальный Mailpit image digest.
- [x] GREEN unit suite; выбранные версии/источники и синтетические secrets зафиксировать.

## Task 2 — facade, HTTP и полная авторизация тестового режима

**Files:** LifecycleService.java, LifecycleController.java; JsonRequests,
AuthService/SessionPrincipal/ProfileService/PersonalDtos, security filters/config,
AdminPrincipal/provider/access/controller/catalogue service; V9 migration.
**Interfaces:** service.requestVerification(principal,ip)→Optional<Challenge>,
requestReset(email,ip)→Challenge, confirmVerification(principal,id,code,ip)→void,
verifyReset(id,code,ip)→ResetGrant, confirmReset(token,password,ip)→void.
Settings.enabled controls exposure; legacy serializers omit optional flag.

- [x] RED HTTP/SMTP IT с реальным Mailpit и PostgreSQL: все пять операций,
  truthfully boolean flag, pending allowlist, no-store/Retry-After/shape limits,
  чужой Bearer не меняет reset target, invalid400 не logout, uniform202 unknown/deleted.
- [x] Реализовать facade, parser/controller, jobs + stored-address/version checks,
  безопасные counters. Добавить V9: native session version, credential_changed_at,
  lifecycle DB state. Mutation/grant consumption atomic; attempts commit before errors.
- [x] RED revocation/races/admin-cookie tests; реализовать version checks на каждом
  native/admin request и внутри admin mutations, pending30min и password reset guard
  на login. Смена пароля инвалидирует native/admin/прочие proofs и queued jobs.
- [x] GREEN существующие legacy tests и новые N2 tests; кандидат/экспорты N2 отдельно,
  B1/B2/B3 coverage не ослаблять. Старые private dev flows остаются прежними.

## Task 3 — восстановление lifecycle DB остаётся fail closed

**Files:** V9 prepare/require restore SQL functions, afterMigrate callback,
restore.py/restore_review.py, Python tests и реальные PostgreSQL restore IT.
**Interfaces:** optional lifecycleRecoveryRequired=true в quarantine state;
SQL prepare_lifecycle_restore(restoreId), require_lifecycle_restore_prepared(restoreId),
owner-only EXECUTE. Restore auto-detects activated snapshot; old snapshots opt in.

- [x] RED tests genuine old-schema restore/upgrade, old password/cookie refusal,
  stale codes/grants cleared, missing preparation rejects approval, repeat migrate
  does not reblock an already recovered user; previous legacy restore stays compatible.
- [x] Реализовать preparation под quarantine и привязать к restoreId. Неполная схема
  остаётся закрытой до миграции. Activated DB не стартует в legacy mode. Runtime ACL
  получает только нужные таблицы/SELECT state; functions не доступны runtime/PUBLIC.
- [x] GREEN SQL/Python tests и полный B4 rehearsal, без потери прежних gates.

## Task 4 — кандидат контракта и воспроизводимая закрытая среда

**Files:** scripts/lifecycle/render_test_contract.py + tests, infra/lifecycle-test/
compose.yaml, prepare/start instructions, test HTTP exporter/CI step.

- [x] RED test renderer: exact5 routes, required bool/new403 matrix только candidate,
  никакой записи в canonical/extra refresh/public private drift.
- [x] Реализовать генерацию candidate0.2-test из canonical0.1.1, OpenAPI validation
  и fresh N2 HTTP validation. Добавить CI checks без изменения существующих B1/B2/B3.
- [x] Закрытые PostgreSQL/Mailpit ports только loopback, ephemeral synthetic secrets
  вне Git. Дать точные launch commands без реальных паролей/ключей и runtime activation
  на production. Интеграционная матрица передаётся другу на review, не считается accepted0.2.

## Task 5 — полная проверка, review и отчёт

- [x] Maven verify/dependency:list, contract13/fixtures, Python B4/security/renderer,
  fresh legacy + N2 HTTP; packaged B1/B2/B3, SMTP real transport/inbox; B4 restore.
- [x] OSV по новому runtime graph, Gitleaks всех changed/new files, scope/links/whitespace;
  тестовые процессы UID1000, без отключения проверок. Один final read-only reviewer,
  meaningful fixes RED→GREEN/full suite, без commit/push/PR.
- [x] Обновить собственный STATUS_BACKEND, DECISIONS/PRIVACY и N2 report/runbook;
  дать точные add/commit/push/PR команды, отметить stack над ещё открытым N1.

## Rulings / pre-flight

- Вопрос strict0.2 задан как недостающее совместное решение; ответ друга подтвердил
  test-only candidate и PASSWORD_RESET_REQUIRED только login. Это обязательная граница.
- Legacy guards/settings и serializer должны быть backward-compatible при disabled;
  полноту нового runtime проверять только отдельным source/profile, без partial activation.
- Source state в V9 отличает lifecycle DB от legacy snapshot; preparation marker
  защищает restore/approve и повторные миграции. Mail worker не стартует на migrator.
- User-owned commits: планы/ledger живут в repo/ignored target, tasks не коммитить.
