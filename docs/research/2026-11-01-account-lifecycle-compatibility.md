# Проверка переходного account lifecycle контракта

1 ноября 2026. База — `7f6795530489e1be409c9457d10d83f4e5645761` / API 0.1.0.
Проверяется канонический **0.1.1 contract-only**, а не реализация новых операций.
[Семантика и rollout](../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md).

## Изменение и совместимость

Добавлены optional non-null emailVerified без default, 5 planned POST, новые schema
components/fixtures. Существующие request schemas, response status maps, AuthResponse
envelope и Problem с 9 кодами сохранены. Legacy fixture manifest остаётся из 6 entries.
LifecycleProblem изолирует будущие коды. Guard activation/новые 403 старых операций
отложены до отдельного согласованного изменения перед required режимом.

Это даёт совместимость нынешнего backend и JSON codec нынешнего клиента. Не означает
поддержку новым сценарием старого UI или универсальную совместимость любого SDK.
Отсутствие флага — UNKNOWN, а текущая закрытая legacy работа не доказывает verified.

## Oasdiff

Использован официальный [oasdiff v1.35.0](https://github.com/oasdiff/oasdiff/releases/tag/v1.35.0),
Linux amd64 tar SHA256 `a8023002fcb2eaa7f5758148a9303cb63d33b939886df312268bc0fc7b939140`.
Загружен только как локальный review tool, не зависимость приложения.

```bash
git show 7f6795530489e1be409c9457d10d83f4e5645761:api/openapi.yaml > backend/target/lifecycle-base-0.1.0.yaml
printf '{}\n' > backend/target/oasdiff-review.yaml
oasdiff --config backend/target/oasdiff-review.yaml breaking \
  backend/target/lifecycle-base-0.1.0.yaml api/openapi.yaml \
  --allow-external-refs=false --format json --fail-on WARN
```

**Финальный результат: exit 0, 0 breaking findings.** Пустая config, без ignore,
снижения severity, фильтрации endpoints или suppression.

Первый внутренний вариант расширял enum общего Problem: **255 ERR**
`response-property-enum-value-added`, exit 1. Это распространялось на старые ответы
всех операций. Исправлен сам контракт: старый Problem/status maps сохранены,
будущие ошибки вынесены в LifecycleProblem. Новый regression test наблюдал RED
на 12 вместо прежних 9 codes, затем GREEN. Эти findings не объявлялись ложными.
Oasdiff проверяет правила wire changes, не runtime availability/security.

## Реальный текущий Kotlin codec

JVM probe компилирует **неизменённый** `mobile/.../data/ContractDtos.kt`:
SHA256 `31204596aa476eb28bfb30cc7fb9756383673ff2bc3825269af3cbf61a5dbc8a`.
Kotlin compiler 2.2.21 и serialization 1.9.0 совпадают с выбранным mobile toolchain.
Compiler zip из [официального release](https://github.com/JetBrains/kotlin/releases/tag/v2.2.21),
SHA256 `a623871f1cd9c938946948b70ef9170879f0758043885bbd30c32f024e511714`;
два serialization JVM jars сверены с опубликованными Maven SHA256.

- Profile без поля, с false и с true декодируется в прежний ProfileDto — **3 PASS**.
- Три future code payloads декодируются в ProblemDto.code как String — **3 PASS**.
- Файл mobile не изменился; AuthSessionClient по прочитанному исходнику очищает
  сессию на protected 401, прочие ошибки generic Http. Новые ветки UI он не реализует.

Это только JVM serialization, не Android/iOS/KMP build, native storage, UI или live
Ktor lifecycle integration. Build dependencies/source mobile не менялись.
Probe/compiler/выходной jar только в ignored target/пользовательском SDK cache.

## Остальные проверки

| Область | Фактический результат |
|---|---|
| OpenAPI 3.1 / references / 6 legacy + 7 lifecycle fixtures | PASS |
| Transitional validator regression tests | **13 PASS**, RED→GREEN; CI contract job дополнен ими |
| Существующие Python B4/security tests | **49 + 27 PASS**, без skips |
| Maven verify + runtime dependency list | **BUILD SUCCESS, 282 = 21 unit + 261 IT**, 0 failures/errors/skips; 1:37 |
| Fresh Maven HTTP exports → canonical 0.1.1 | **139 + 345 + 106 = 590 PASS** |
| OSV Maven audit | **106 packages, 0 affected** |
| Packaged B1 / Bruno / Schemathesis | PASS, 201 generated cases passed |
| Packaged B2 и B3 / Bruno | PASS, обе временные БД очищены |
| HTTPS/restore rehearsal | **PASS, 143.325 с**, archive/extract/quarantine/replay/rollback/V6→V7/cleanup |
| Финальный scope/синтаксис/ссылки/Gitleaks | **PASS**, 22 изменённых/новых файла, 163 локальные ссылки, без suppression |

Основные выполненные команды (Linux/WSL, подготовленный venv; Maven из backend/):

```bash
python scripts/validate_contract.py
python -m unittest discover -s scripts -p 'test_contract_transition.py'
python -m unittest discover -s scripts/b4 -p 'test_*.py'
python -m unittest discover -s scripts/security -p 'test_*.py'
cd backend
bash ./mvnw --batch-mode --no-transfer-progress verify dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
```

Три HTTP exports проверены `backend/src/test/resources/validate_http_contract.py`
с `--schema api/openapi.yaml` (пути относительно корня) и `--responses` соответственно
`backend/target/catalog-contract-responses.json`, `backend/target/b2-contract`,
`backend/target/b3-contract`. Затем из корня запускались
`scripts/b1/run_contract_checks.py`, `scripts/b2/run_contract_checks.py`,
`scripts/b3/run_contract_checks.py` с `--jar backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar`.
Для B4 — `scripts/b4/run_rehearsal.py` с тем же jar и
`--output-dir backend/target/canonical-lifecycle-rehearsal`. Повторная репетиция
требует нового output directory. OSV — `scripts/security/check_maven_dependencies.py`
с `--dependencies backend/target/runtime-dependencies.txt --output backend/target/canonical-osv-audit.json`.

Все test processes UID 1000; Docker доступен через временную supplementary group
106 после root-launcher setpriv. Тесты не выполнялись с UID 0, постоянные группы
и socket permissions не менялись. Native Python 3.14/JDK 21.0.12.1/Node 24.19.0;
GitHub использует Python 3.12/JDK 21.0.13+9. Результат будущего удалённого CI не заявляется.

Git HTTPS account повторно проверен через API: **yergaliuly**. Отдельно проверена
commit identity: **softache <itsyergaliuly@gmail.com>**. Index пуст; commit/push/PR
не выполнялись. Семантическое сравнение подтвердило неизменность всех прежних
операций, request/response schemas и status maps после удаления только объявленных
добавлений. Шесть legacy fixtures/manifest совпали с Git-базой побайтно, прежние CI
steps сохранены. Gitleaks проверил все изменённые/новые файлы с полным redaction.

Логи ignored `backend/target/canonical-{transition-*,lifecycle-validator,kotlin-compatibility,
oasdiff-*,lifecycle-maven,other-python,http-validation,osv-audit,b1-runtime,b2-runtime,
b3-runtime,lifecycle-rehearsal}*`. Собственные containers/volumes/secrets/backup files
очищены. SDK caches и images оставлены; реальные dev volumes не запускались/не мигрировались.

Новые HTTP lifecycle calls, почта, migrations, enforcement, forced password recovery
и mobile verification не выполнялись/не реализованы. RESTIC/container scans не
повторялись, прежние blockers не исправлены. Никакого разрешения публичного выпуска
или доказательства надёжной доставки писем эти проверки не дают.
