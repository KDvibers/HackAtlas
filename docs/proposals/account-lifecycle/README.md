# Для совместного review: почта и восстановление пароля

**DRAFT, 1 ноября 2026. Не утверждено и не реализовано.**

Предложение для backend/mobile; [полный дизайн](../../superpowers/specs/2026-11-01-account-lifecycle-design.md).
Действующие `api/openapi.yaml`, `api/README.md`, fixtures и приложение не менялись.
Этот каталог содержит только delta к будущему контракту. Он не заменяет Starter.

## Предложение, которое нужно обсудить

Сохранить register → 201 AuthResponse и opaque Bearer. В Profile добавить обязательный
`emailVerified`. До true аккаунт получает ограниченные права; гостевой каталог и
локальные сохранения доступны. Подтверждать почту кодом из 8 цифр. Reset состоит
из запроса письма, обмена кода на короткий reset-only grant и установки нового пароля.
После reset нужен обычный login; все старые native/admin sessions отзываются.

Почему этот вариант: меньше переделок уже слитых M3 core/UI, не требует домена и
deep links для первого сценария. Цена — pending state/guard, ограничения попыток и
HMAC key. Альтернатива — email-first без сессии до подтверждения; она сильнее меняет
нынешний registration flow. Длинные ссылки вместо кодов тоже допустимы после выбора команды.

## Предлагаемый API

Все пути относительно `/api/v1`. **Сейчас этих операций на backend нет.**

| Метод и путь | Вход | Успех |
|---|---|---|
| POST `/auth/email-verification/request` | Bearer, без тела | 202 challenge; 204, если уже verified |
| POST `/auth/email-verification/confirm` | Bearer + `{challengeId, code}` | 204 → обновить GET /me |
| POST `/auth/password-reset/request` | `{email}`, без Bearer | одинаковый 202 для существующего/неизвестного адреса |
| POST `/auth/password-reset/verify` | `{challengeId, code}`, без Bearer | 200 `{resetToken, expiresAt}` |
| POST `/auth/password-reset/confirm` | `{resetToken, newPassword}`, без Bearer | 204, без автоматического login |

Challenge response: `{challengeId, expiresAt, resendAfterSeconds: 60}`. Принятие
запроса не означает, что аккаунт существует или письмо доставлено. В examples все
IDs/tokens искусственные; время задано для reference clock 12:00 UTC, не для live API.

Pending allowlist: GET `/me`, POST `/auth/logout`, POST `/me/delete-account` с паролем
и два verification endpoint. Другие account reads/writes и admin → 403
`EMAIL_NOT_VERIFIED`. Нельзя импортировать guest saved до подтверждения почты.

Новые коды Problem:

- `EMAIL_NOT_VERIFIED` / 403 — сессия есть, нужны verification actions; не стирать её.
- `EMAIL_ACTION_INVALID` / 400 — code/grant недействителен, включая expiry/replay;
  опечатка в коде не вызывает global logout.
- `PASSWORD_RESET_REQUIRED` / 403 из login — после restore нужен recovery, Bearer не выдан.

401 сохраняет значение недействительного **приложенного Bearer**. Public reset
должен пользоваться HTTP engine без Authorization; тогда неверный code/grant → 400.
429 имеет Retry-After. Все lifecycle ответы no-store; credentials не логируются.

## Существенные условия

- Предлагается: code 10 минут / 5 неверных попыток, grant 5 минут, pending Bearer
  30 минут. Code — строка с ведущими нулями. Новый пароль 12–256, без trim.
- Verification требует Bearer того же аккаунта: получатель письма не должен
  случайно активировать pre-created аккаунт с паролем злоумышленника.
- Публичный request не отменяет старые рабочие коды/grants и не блокирует login.
  Attempts сохраняются при restart и при неудачном ответе. Reset отзывает и admin cookies.
- Старый backup может вернуть старый пароль. Предлагается forced email reset всех
  восстановленных accounts до входа и очистка всех восстановленных challenges/grants.
  Это новый recovery guard, а не уже существующая гарантия B4.5.
- Для первой доставки предлагается bounded best-effort executor и SMTP, без durable
  outbox. При restart задача может потеряться; есть resend, честный 202 и monitoring.
  Если нужна гарантированная постановка задач — отдельно согласовать durable scope.
- `Profile` и enum `Problem.code` закрытые: изменение **не полностью обратно совместимо**.
  Старые DTO/flows не объявляются совместимыми автоматически. Текущий register 409
  остаётся и сохраняет существующий риск enumeration; generic reset его не устраняет.
- SMTP provider/domain/секреты не выбраны. Реальное письмо и Android/iPhone ещё
  предстоит проверить; никакого «письмо отправлено» по mock-сценарию.
- Для SMTP предлагается хранить исходный delivery email отдельно от нынешнего
  lowercase matching key. Адрес из reset request не выбирает получателя. Старым
  accounts нельзя выдумывать потерянный регистр или автоматически ставить verified.

## Артефакт контракта и база

[openapi.patch.json](openapi.patch.json) — JSON Patch (test/add/replace) к **копии**
канонического OpenAPI. Он не применяется к `api/` при чтении или запуске проекта.
Полная кандидатная копия генерируется только в ignored `backend/target` для review.

| Метаданные | Значение |
|---|---|
| Base commit | `991be66123b5f791d0ee9a33302b9d681170e345` |
| Base API version | `0.1.0` |
| Canonical JSON SHA256 | `f98f40105db4a1b701764b6735a2722c4b1080295e5388a8f7cc8862bd39e116` |
| Candidate version | `0.2.0-proposal.1` |
| Canonical/API change | **Нет** |

SHA256 вычислен по UTF-8 `json.dumps(yaml.safe_load(source), ensure_ascii=False,
sort_keys=True, separators=(',', ':'))`: не зависит от CRLF/LF. При изменении Starter
нужно заново сверить базу/patch; нельзя безусловно накладывать его на другой контракт.
Patch включает 5 новых POST, обязательный emailVerified, 3 кода ошибок, новый login
403 и verification guard/403 для 9 существующих операций.

Применять pending guard нужно в будущей реализации и для `/admin`, хотя HTML-admin
не входит в OpenAPI. Добавление allOf не может расширить прежний закрытый Profile;
patch меняет саму копию схемы. Текущие /api/v1 URL и Bearer protocol сохраняются.

## Вопросы другу перед одобрением

1. Подходит pending Bearer и allowlist или выбираем email-first?
2. Принимаем код вместо deep links, сроки и отдельный reset-only grant?
3. Поддерживаем forced reset после старого backup и отзыв всех admin/native sessions?
4. Достаточна best-effort доставка первого этапа с resend, или нужен durable mail job?
5. Какие изменения DTO/AuthState/UI/Problem handling нужны в M3 и как согласуем rollout?

После выбора обновить дизайн, затем отдельным совместно одобренным contract PR
менять canonical API/семантику/fixtures. Только потом поручать реализацию.
Сообщения другу инструментами не отправлялись.

## Текст для ручной передачи

> Привет! Подготовлен draft подтверждения почты и восстановления пароля. Предлагаем
> сохранить register/login с opaque Bearer, добавить emailVerified и pending state,
> использовать 8-значный email code. Reset: request → verify code → короткий resetToken
> → новый пароль → обычный login; старые native/admin sessions отзываются. Ещё предлагаем
> forced recovery после восстановления старого backup. Канонический API и код пока
> не менялись. Посмотри таблицу endpoint, ошибки и 5 вопросов выше; сначала согласуем
> контракт и rollout, затем приступим к реализации. SMTP provider пока не выбран.

[Фактическая проверка draft](VALIDATION.md) отделена от будущей приёмки работающего API.

## Как проверить кандидат отдельно от API

Linux/WSL или macOS, из корня checkout. `python` — активное окружение с
`scripts/requirements-contract.txt`. Команда меняет только ignored `backend/target`.

```bash
python - <<'PY'
from pathlib import Path
import copy, hashlib, json, yaml
from openapi_spec_validator import validate_spec
root = Path.cwd()
api = yaml.safe_load((root / 'api/openapi.yaml').read_text(encoding='utf-8'))
digest = hashlib.sha256(json.dumps(api, ensure_ascii=False, sort_keys=True,
                                 separators=(',', ':')).encode()).hexdigest()
assert digest == 'f98f40105db4a1b701764b6735a2722c4b1080295e5388a8f7cc8862bd39e116', 'Base contract changed'
candidate = copy.deepcopy(api)
patch = json.loads((root / 'docs/proposals/account-lifecycle/openapi.patch.json').read_text(encoding='utf-8'))
for action in patch:
    keys = [k.replace('~1', '/').replace('~0', '~') for k in action['path'].split('/')[1:]]
    parent = candidate
    for key in keys[:-1]:
        parent = parent[int(key)] if isinstance(parent, list) else parent[key]
    key, value = keys[-1], copy.deepcopy(action['value'])
    if action['op'] == 'test':
        assert parent[key] == value
    elif action['op'] == 'add' and isinstance(parent, list):
        parent.insert(len(parent) if key == '-' else int(key), value)
    elif action['op'] in ('add', 'replace'):
        if action['op'] == 'replace': assert key in parent
        parent[key] = value
    else:
        raise ValueError('Unsupported review patch operation')
validate_spec(candidate)
output = root / 'backend/target/account-lifecycle-openapi.candidate.yaml'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes(yaml.safe_dump(candidate, allow_unicode=True, sort_keys=False).encode())
print('PASS candidate OpenAPI 3.1; canonical API untouched:', output)
PY
```

Это только просмотр candidate. Не копировать результат в `api/` до совместного
одобрения и отдельного contract PR.
