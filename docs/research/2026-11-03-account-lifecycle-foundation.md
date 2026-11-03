# Account lifecycle foundation N1: проверка реализации

3 ноября 2026. База — main `2d05c6fab434829b5c34f52af2bd2b947ed22e36`, merged PR #23.
GitHub Backend CI базы `41631949529` завершился success. Ветка
`feat/account-lifecycle-foundation`, исходно чистая; работа одним исполнителем и
одним независимым read-only reviewer для crypto/concurrency. Commit/push/PR не выполнялись.

## Результат и границы

[Реализация N1](../../backend/ACCOUNT_LIFECYCLE.md) добавляет V8, Java JCA crypto,
transaction-bound JdbcTemplate store и сохранение delivery spelling новых email.
Canonical 0.1.1, пять planned routes, mobile, dependencies, CI, runtime ACL и
существующий протокол авторизации не менялись. Реальные пользовательские dev volumes
не запускались/не мигрировались; проверки использовали disposable PostgreSQL.

Новые flags не доказывают готовность lifecycle: profile по-прежнему не выдаёт флаг,
store не меняет пароль/verified и не является Spring bean. SMTP, HTTP, IP/email
admission, native/admin credential-version checks и restore forced recovery ещё
предстоит реализовать. Никакого публичного signup/deployment этим этапом нет.

## Проверки

| Область | Фактический результат |
|---|---|
| Maven verify / dependency:list | **BUILD SUCCESS, 318 = 31 unit + 287 IT**, 0 failures/errors/skips, 1:52 |
| Новые проверки | **36**: crypto 10, migration 1, store 24, registration metadata 1 |
| V7→V8 на настоящей PostgreSQL 17.11 | PASS, credentials/session сохранены, legacy false/0/null, repeat migrate/validate, constraints/CASCADE/deny ACL |
| Crypto vector | PASS, independent Python stdlib HMAC/SHA256 expected values, binding/key rotation/ASCII/redaction |
| Store concurrency/transactions | PASS: one-winner consume/exchange/grant, max attempts/resend, rollback, delete/recreate, same-DataSource transaction requirement |
| Canonical validator / fixtures | PASS, 6 legacy + 7 lifecycle examples; canonical не изменён |
| Python contract / B4 / security | **13 + 49 + 27 = 89 PASS** |
| Fresh HTTP exports по canonical | **139 + 348 + 106 = 593 PASS** |
| OSV resolved runtime graph | **106 packages / 0 affected**, свежий запрос API |
| Packaged B1 / Bruno / Schemathesis | PASS, 201 generated cases passed |
| Packaged B2 / B3 / Bruno | PASS, 26/24 Bruno requests, disposable accounts/DB очищены |
| HTTPS/backup/restore | **PASS, 150.597 с**, точный свежий JAR, V6→V7→V8, quarantine/approval/replay/rollback/cleanup |
| Финальный review файлов | **18 файлов / 185 локальных ссылок PASS**, UTF-8/Python AST/whitespace/scope/Gitleaks |

Все тестовые Python/Java процессы UID1000. Root launcher только вызывает setpriv,
сбрасывая uid/gid/capabilities до запуска тестов; Docker group106 добавляется временно.
Постоянные группы/socket permissions не менялись. Native Python3.14/JDK21.0.12.1/
Node24.19.0, не идентичный образ GitHub runner. Будущий удалённый CI не заявляется.

## Выявленные ошибки и review

- Crypto tests сначала не компилировались из-за отсутствующего компонента.
  Migration test наблюдал RED: expected8/actual7, затем GREEN на реальной V7 БД.
- Store no-op scaffold/metadata test: **21 failures из 23 IT, 0 errors**; после
  реализации PASS. Registration ожидала исходное написание адреса, получала NULL.
- Собственная проверка выявила pre-lock clock read. Реальный pg_blocking_pids test
  удерживал account lock до expiry: RED accepted=true вместо false. Проверка времени
  перенесена после lock; новая полная suite GREEN.
- Независимый reviewer нашёл **один Important/P2**: кандидаты из T0 после задержки61с
  обходили cooldown, основанный на created_at. Новый test наблюдал RED на втором
  кандидате. Добавлен accepted_at под lock для cooldown, прежние created_at/expiry
  сохраняются. Финальная полная suite **318 PASS** после этого fix pass.
- Critical и Minor reviewer не сообщил. Второй reviewer/повторное делегирование
  не использовались. Принятые границы: malformed shape не тратит guesses; rotation
  аннулирует code, а отдельный grant живёт до своего короткого expiry. Будущий worker
  обязан проверить account/expected version под lock перед выдачей: N1 не содержит
  jobs и не объявляет candidate доказательством полномочий.

## Команды и evidence

Основная сборка из backend/:

```bash
bash ./mvnw --batch-mode --no-transfer-progress verify dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
```

Из корня в подготовленном Python venv:

```bash
python scripts/validate_contract.py
python -m unittest discover -s scripts -p 'test_contract_transition.py'
python -m unittest discover -s scripts/b4 -p 'test_*.py'
python -m unittest discover -s scripts/security -p 'test_*.py'
python scripts/security/check_maven_dependencies.py --dependencies backend/target/runtime-dependencies.txt --output backend/target/lifecycle-foundation-osv.json
```

Fresh HTTP: backend/src/test/resources/validate_http_contract.py с --schema
api/openapi.yaml и --responses backend/target/{catalog-contract-responses.json,
b2-contract,b3-contract}. Затем scripts/b1/run_contract_checks.py,
scripts/b2/run_contract_checks.py, scripts/b3/run_contract_checks.py с
--jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar. Rehearsal:
scripts/b4/run_rehearsal.py с тем же --jar и
--output-dir backend/target/lifecycle-foundation-rehearsal. Повтор требует нового
output directory. Прежние проверки/coverage не отключались и не сужались.

Логи и JSON только в ignored backend/target/lifecycle-foundation-*.
Собственные containers/volumes/temporary secrets/backups очищены; build images
и SDK cache оставлены. Полные KMP/Android/iOS builds, устройства, настоящий SMTP/inbox,
публичная нагрузка и новые lifecycle HTTP calls не проверялись. Trivy scans не
повторялись; прежние release blockers этим этапом не закрываются.

HTTPS Git credential API подтвердил **yergaliuly**; отдельно commit identity —
**softache <itsyergaliuly@gmail.com>**. Финальный Git/scope/Gitleaks review отражён
в [статусе backend](../STATUS_BACKEND.md).

Первый Gitleaks проход классифицировал формулировку пункта плана как возможный
ключ. Проверены файл и строки из redacted JSON: это обычный английский текст,
не credential. Пункт переформулирован по-русски с сохранением смысла. Финальный
скан всех изменённых/новых файлов прошёл с прежними правилами, без allowlist,
inline исключений или подавления findings. Git index остался пустым.
