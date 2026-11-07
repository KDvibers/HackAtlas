# N2: фактические HTTP-примеры для мобильного клиента

7 ноября 2026. [responses.json](responses.json) содержит31 выбранный ответ из127
фактических HTTP-ответов Java integration tests с PostgreSQL и Mailpit. Это данные
закрытого кандидата **0.2.0-test.1**, а не изменение канонического API0.1.1.
Сборка выполнена от Linux UID1000; это не мобильный E2E и не принятие strict rollout.

## Состав

- Pending register201/login200 и Profile false; verified login200/Profile true.
- Verification request202, request204 для verified и confirm204.
- Reset request202, verify200 с reset-only grant, confirm204 без autologin.
- EMAIL_ACTION_INVALID400 для email confirm/reset verify/reset confirm.
- Все9 pending guards403 EMAIL_NOT_VERIFIED, включая saved и applications.
- Login403 PASSWORD_RESET_REQUIRED; protected GET/me401 AUTH_REQUIRED.
-429 RATE_LIMITED с фактическим Retry-After и503 SERVICE_UNAVAILABLE при полной очереди.
- Две последовательности wrong-delete-password401 INVALID_CREDENTIALS → GET/me200:
  pending и verified. В каждой исходный Java test использует тот же Bearer;
  сам Bearer и request bodies намеренно не экспортируются. Test names указаны в sequences.

**Verified register отсутствует по смыслу:** новая регистрация всегда pending.
Примеры verified получены после настоящего подтверждения через SMTP.

## Формат для MockEngine

Каждая строка responses имеет caseId, operationPath относительно `/api/v1`, method,
status, headers и body. headers — непосредственно захваченные response headers из
allowlist Content-Type/Cache-Control/Retry-After/X-Request-Id/WWW-Authenticate.
Отсутствующий header не добавляется. Content-Type/cacheControl/retryAfter также
сохранены в прежних flat fields для существующего validator; значения обязаны совпадать.
HTTP exporter проверяет отсутствие Set-Cookie во всех ответах; cookies не сохраняются.

Для HTTP204 body=null означает пустое тело: передать в MockEngine пустую строку,
а не строку `null`. Для остальных ответов сериализовать body как JSON и передать
сохранённые headers/status. Это parsed JSON capture: исходные whitespace/bytes и
полный набор wire headers не сохранялись. Даты наблюдались на контролируемом test
Clock и не являются текущими датами устройства. Для тестов времени использовать
соответствующий test clock; не продлевать TTL и не переписывать даты выдавая их за capture.

UUID, email и displayName обезличены с сохранением связей. X-Request-Id остаётся
валидным UUID и совпадает с Problem.requestId после обезличивания. Access tokens
`x`×43, challengeId `A`×43 и resetToken `R`×43 — нерабочие placeholders. Реальных
токенов, OTP, паролей, писем, Authorization, grants и .env здесь нет. Не отправлять
placeholders backend и не принимать resetToken за Bearer.

## Воспроизведение

Из backend, с Java21 и доступным Docker обычного пользователя:

```sh
bash ./mvnw --batch-mode --no-transfer-progress verify
```

Из корня, Python с уже используемыми `scripts/b1/requirements.txt` dependencies:

```sh
python scripts/lifecycle/render_test_contract.py
python scripts/lifecycle/validate_http.py --schema backend/target/lifecycle-test-openapi.yaml --responses backend/target/n2-contract
python scripts/lifecycle/export_mobile_examples.py --output docs/examples/n2-http/responses.json
python -m unittest discover -s scripts/lifecycle -p 'test_*.py'
```

Exporter ничего не запрашивает по сети и читает только test exports. Старые captures
без actual headers, неполное HTTP coverage, отсутствие одной из двух401/200 sequences
или неотредактированные proofs отклоняются. Selected fixtures проверяются по схеме
отдельно от полного runtime coverage:31 пример не заменяет127 полных HTTP-ответов.
Повторный capture может изменить наблюдаемые даты/порядок обезличенных UUID; diff
проверяется вручную. Не обновлять данные вручную для имитации отсутствующего ответа.

[Матрица клиента](../../superpowers/specs/2026-11-05-account-lifecycle-client-matrix.md)
и [предложение по legacy](../../superpowers/specs/2026-11-07-legacy-account-recovery-design.md)
остаются предметом совместного согласования. Pending/offline политики этим набором
примеров не принимаются; у legacy0.1.1 прежний offline-сценарий.
