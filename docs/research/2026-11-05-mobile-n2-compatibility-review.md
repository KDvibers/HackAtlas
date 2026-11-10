# Mobile review: матрица N2 и совместимость с API 0.1.1

Обзор начат 5 ноября 2026. База `origin/main=dec07cc8e015c203aadbbfa5f4473b189b988940`
(merged PR #31); mobile commit `cdcc7b7`. Ветка обзора: `chore/mobile-n2-compat-review`.
Задание: документы/матрица/текущий клиент, без изменения кода или принятия strict 0.2.

## Вывод

**Текущий клиент совместим с legacy API 0.1.1 на уровне проверенного контракта,
но не реализует обязательный lifecycle N2. Подключать его к N2 как готовый клиент
нельзя.** Дополнительный JSON он разбирает, однако pending, verification и recovery
не поддерживает. Это ожидаемая граница предложения, а не найденный обход серверной
защиты или регрессия действующего legacy-режима.

Матрица подходит для дальнейшего согласования. Это не её утверждение, не смена
канонического API и не разрешение реализации/rollout. Обычный source остаётся
legacy; N2 работает отдельно по `0.2.0-test.1`. Результаты прежней Android-приёмки
личных отметок не являются приёмкой N2.

## Проверенные источники

- [Матрица 5 ноября](../superpowers/specs/2026-11-05-account-lifecycle-client-matrix.md),
  [N2 report](2026-11-05-account-lifecycle-n2.md),
  [Windows/Android handoff](../../infra/lifecycle-test/WINDOWS_ANDROID.md),
  [локальный стенд](../../infra/lifecycle-test/README.md).
- Канонические [OpenAPI](../../api/openapi.yaml), [README](../../api/README.md),
  [план совместимости](../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md) и примеры.
- Текущие DTO и auth-клиент, состояния входа, оба private-хранилища и экраны;
  renderer кандидата, runtime filter, AuthService и AccountDeletionService.
  Проверка серверного кода была ограничена утверждениями матрицы, не полным аудитом N2.

## Совместимость по сценариям

| Область | Факт текущей реализации | Следствие для N2 |
|---|---|---|
| Opaque Bearer | Тот же envelope `tokenType/accessToken/expiresAt/user`; JWT/refresh нет | Совместимая основа, замена авторизации не нужна |
| Profile.emailVerified | Поля нет в DTO, unknown keys игнорируются | false/true/missing не дают разных состояний; required mode отсутствует |
| Pending/verified | AuthState имеет Authenticated, без email access-state | Успешный pending login воспринимается как обычный вход |
| Новые ошибки | AuthError.Http хранит status и Retry-After, не Problem.code | EMAIL_NOT_VERIFIED и PASSWORD_RESET_REQUIRED превращаются в generic 403 |
| Закладки/отметки | Store-функция verified означает authenticated/error-free, не проверенную почту | Нужен общий доступ для обоих stores и UI-проекций; серверные guards остаются обязательны |
| Кеш/календарь | Owner-scoped копия доступна после cold offline start | Предложенное N2 поведение строже: без свежего /me после cold start private скрыт |
| Guest import | Явный batch, guest bytes не удаляются; ошибка прерывает batch | Нужен запрет начала/продолжения при pending; существующая обработка 403 не меняет auth access-state |
| Verification/reset/delete | Методов и экранов клиента пока нет | Новые flows нельзя принять по одному успешному подключению к 8081 |
| TTL | Клиент читает expiresAt, не требует ровно 30 дней | Pending 30 минут разбирается; после confirm срок не продлевается, обычный login выдаёт новую сессию |
| Logout/protected 401 | Локальная сессия очищается, stores скрывают/очищают private; guest отдельно | Основа пригодна; исключение delete-password401 потребует отдельного разбора |
| Generation | Есть session mutex и owner/epoch защиты, local identity = userId+expiresAt | Это не доказательство выполнения нового требования same-user/new-session generation |

Основания: [ProfileDto/codec](../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/ContractDtos.kt#L157),
[AuthState/Http](../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/auth/AuthModels.kt#L12),
[protected/error handlers](../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/auth/AuthSessionClient.kt#L138),
[saved guard](../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/saved/AccountSavedStore.kt#L52),
[applications guard](../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/applications/ApplicationStore.kt#L56),
[видимость кеша](../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/App.kt#L80).
Same-user races с новым lifecycle flow здесь не воспроизводились; наличие старых
epoch-тестов не выдаётся за проверку будущей generation-модели.

## Что блокирует активацию и что уточнить в документах

1. **Блокер активации: новые ответы не являются контрактом 0.1.1.** Сопоставлены
   22 операции матрицы и 9 pending guards. Все 9 ответов EMAIL_NOT_VERIFIED и login
   PASSWORD_RESET_REQUIRED (403) принимаются кандидатом и отвергаются схемами старых
   операций 0.1.1. Это ожидаемо и корректно изолировано renderer; включать enforcement
   на общем source до отдельного совместного PR нельзя. Canonical Problem и Bearer
   renderer не меняет. Пять новых операций остаются planned в canonical.

2. **Блокер клиентской приёмки: parsing не даёт lifecycle-поведения.** JVM replay
   текущих скомпилированных неизменённых DTO подтвердил, что false/true, а также
   невалидные для API null/строка/число в неизвестном поле не меняют ProfileDto.
   Новые Problem.code декодируются строкой, но auth HTTP-handler их не использует.
   Новые режим/состояния, маршрутизация ошибок и secret cleanup ещё нужны;
   добавление только boolean в DTO недостаточно.

3. **Холодный запуск без сети.** Первое ревью выявило различие с принятым M3.
   Для закрытого N2 пользователь утвердил скрытие частных копий до онлайн-проверки
   `GET /me` при холодном запуске. Потеря сети после проверки в том же процессе
   позволяет читать копию до истечения сессии. В legacy 0.1.1 действует правило M3
   о чтении своей копии после перезапуска.

4. **Нужно уточнить старую формулировку 401, не менять серверный status.** В
   [совместимости, строка 45](../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md#L45) стоит
   общее «401 означает отзыв/expiry». Новая матрица верно вводит исключение: неверный
   пароль в delete-account возвращает 401 INVALID_CREDENTIALS при живом Bearer.
   [AccountDeletionService](../../backend/src/main/java/com/hackatlas/auth/AccountDeletionService.java#L24)
   это подтверждает. Сейчас клиент delete-account не вызывает, поэтому это требование
   будущего адаптера, а не текущая воспроизведённая ошибка logout. В одном согласованном
   документе нужно различить login401, delete-password401, protected AUTH_REQUIRED
   и public recovery401; не превращать любой 403 в logout или pending.

5. **Обновить явный охват после PR #31.** Раздел 8 матрицы ссылается преимущественно
   на saved. Теперь есть отдельные ApplicationCache/ApplicationStore. В сценариях
   pending/401/logout/смены source/session/ошибки физической очистки следует прямо
   указать оба private-кеша, detail отметки, saved/calendar и профиль/editor/import.
   Таблица HTTP уже содержит applications; это уточнение клиентской приёмки, не новая API-операция.

6. **До strict rollout остаются нерешённые вопросы миграции.** Сам N2 report отмечает
   legacy accounts без delivery address и отсутствие согласованного перехода старых
   клиентов. Нужны реальный путь восстановления таких пользователей и план обновления/
   исключения старых клиентов. Это не блокирует закрытый синтетический стенд и не
   разрешает автоматически добавлять смену email, capabilities endpoint или public signup.

## Выполненные проверки и пределы

- `python -X utf8 scripts/validate_contract.py` — PASS (6 legacy + 7 lifecycle fixtures).
- `python -X utf8 -m unittest discover -s scripts -p test_contract_transition.py` — 13 PASS.
- `python -X utf8 -m unittest discover -s scripts/lifecycle -p 'test_*.py'` — 11 PASS.
- Read-only schema audit: 22 метода/пути, 9 guards, 10 различий 403; canonical bytes
  и вход renderer неизменны, исходный Problem/securitySchemes совпадают.
- Одноразовый JVM replay на existing debug classes с текущими Kotlin stdlib2.2.21 /
  serialization1.9.0: 6 Profile + 3 Problem cases PASS. Это проверка codec, не новых
  AuthSessionClient/stores/UI. JVM heap ограничен 256MB, Gradle не запускался.
- Gitleaks изменённых документов/JSON: 0 находок. Первое совпадение оказалось
  обычной строкой перечня классов/хранилищ; формулировка заменена, исключения и правила
  сканера не менялись. Финальный scope/ссылки/JSON/git diff check — PASS.

Логи/одноразовые диагностические файлы: ignored `mobile/build/n2-compat-review/`.
Сводное доказательство: [verification.json](../../mobile/artifacts/n2-compat-review/verification.json).
Docker, сервер, SMTP, Android/iOS не запускались. Серверные 115 HTTP/24 Windows checks
и 47+309 Maven tests — сообщения в документах бэкендчика, здесь не повторены.
Прежние 268 mobile tests и 10 Android сценариев относятся к legacy M3, не к N2.
Нет принятия strict0.2, разрешения выпуска, новой реализации или изменения дизайна.

## Ответ бэкендчика и утверждённые правила кеша

После слияния PR #32 Android-приёмка закрытого N2 запланирована на компьютере
бэкендчика. Он сообщил, что подготовлены API 36 и Mailpit. Канонический контракт
остаётся 0.1.1, а интеграция email verification и recovery относится к отдельному
закрытому N2 с кандидатом 0.2.0-test.1.

Бэкендчик подтвердил девять серверных ограничений для pending. Публичные детали
события доступны, а личная отметка внутри них относится к частному состоянию.
Правила доступа охватывают кеши закладок и отметок, сохранённое, календарь,
редактор профиля и импорт. Гостевые закладки сохраняются при всех переходах.

Ошибка `401 INVALID_CREDENTIALS` при удалении аккаунта сохраняет текущий Bearer
и кеш. `401 AUTH_REQUIRED` на защищённой операции завершает совпадающую текущую
сессию. `403 EMAIL_NOT_VERIFIED` на одной из девяти операций переводит её в pending
и сохраняет Bearer. Обычный `403 FORBIDDEN` отклоняет операцию. Ошибка
`PASSWORD_RESET_REQUIRED` относится к login и открывает путь к recovery без Bearer.
Обработчик различает операцию, статус и код ошибки. Ответы прежней сессии
игнорируются после смены её локального поколения, даже при том же userId.
Выход, подтверждённое удаление аккаунта и завершение сессии очищают оба её частных кеша.

| Решение | Утверждённый вариант для закрытого N2 | Рассмотренная альтернатива |
|---|---|---|
| Сервер подтвердил pending | Скрывать оба частных кеша и сохранять копии для того же API, аккаунта и сессии. Остановить запись и импорт. После `GET /me` с `emailVerified=true` восстановить доступ для той же сессии | Удалять копии при переходе в pending. Хранится меньше частных данных, после подтверждения требуется повторная загрузка |
| Холодный запуск без сети | Скрывать частные данные до онлайн-проверки `GET /me`. При временной потере сети в том же уже проверенном процессе разрешать чтение своей копии до истечения сессии | Открывать копию по сохранённому подтверждению предыдущего запуска до истечения сессии. Офлайн удобнее, но приложение может показать данные после отзыва сессии на сервере |

Пользователь утвердил оба правила 7 ноября 2026. Они зафиксированы в разделах 1 и 2
матрицы. Для legacy 0.1.1 действует прежний сценарий чтения своего кеша после
перезапуска. Пользователь также подтвердил signed-out recovery и отдельные
поколения для сессий и попыток recovery. Эти правила записаны в разделах 5 и 6
матрицы. Общий переход на strict согласуется отдельно.

Новая регистрация всегда создаёт pending-профиль. Получен актуальный архив
`HackAtlas_N2_HTTP_Examples_127_2026-11-07.zip` с 127 обезличенными ответами.
Репозиторный `validate_responses` проверил весь набор по кандидату 0.2.0-test.1
и отдельно четыре ответа из `delete-password-same-session.json`. Все четыре
совпадают с записями полного набора по индексам. В обеих последовательностях
`401 INVALID_CREDENTIALS` следует `GET /me 200`. Поле `emailVerified` равно
`false` для pending и `true` для verified. Факт использования одной сессии указан
в метаданных экспорта интеграционных тестов.

Дополнительно сверены значимые заголовки, `requestId`, покрытие девяти pending guards
и тестовые заполнители в полях токенов. При использовании в MockEngine ответ 204
передаётся с пустым телом, а часы для проверки срока действия сессии согласуются
со временем выбранного примера. Проверка выполнена по сохранённым HTTP-ответам.
Нативные прогоны N2 остаются отдельным пунктом приёмки.

Рабочего recovery для старых аккаунтов без `email_delivery_address` пока нет.
Это блокирует общий strict rollout. Минимальную поддерживаемую версию клиента
определят после приёмки закрытого N2. Обе задачи ведутся как условия общего перехода.

## Вопросы первого ревью

> Посмотрели матрицу N2 по текущему Kotlin-клиенту после PR #31. API 0.1.1 оставляем
> legacy; N2 — отдельный закрытый кандидат 0.2.0-test.1. Текущий клиент разбирает JSON,
> но pending/verification/reset не поддерживает. Новый Problem.code сейчас теряется
> в generic HTTP-ошибке, а оба private stores не различают emailVerified.
>
> Для совместного согласования нужны ответы/уточнения:
>
> 1. Какой путь предусмотрен для legacy-аккаунтов без delivery address, особенно
>    после restore с PASSWORD_RESET_REQUIRED? Это нужно определить до strict rollout.
> 2. Пришли обезличенные фактические N2 HTTP exports/примеры для будущих MockEngine:
>    pending/verified register/login, verification202/204, reset verify/confirm,
>    EMAIL_ACTION_INVALID400, EMAIL_NOT_VERIFIED403, PASSWORD_RESET_REQUIRED403,
>    delete-password401 с последующим GET /me200,429 Retry-After и503. Без реальных
>    токенов, паролей, OTP и reset grants; нужны также статусы и значимые headers.
> 3. Дополним клиентскую матрицу после PR #31: правила pending/cleanup проверяем для
>    account-saved и account-applications, включая detail, календарь, editor и import.
>    Серверные 9 guards при этом остаются прежними.
> 4. В ACCOUNT_LIFECYCLE_COMPATIBILITY.md общее правило401 нужно уточнить по новой
>    матрице. Неверный delete-password сохраняет действующую сессию; менять HTTP401
>    на другой статус или глобально завершать сессию не требуется.
> 5. Какой план перехода/минимальная поддерживаемая версия старых клиентов будет
>    принят перед общим strict source? Сейчас enforcement на нём не включаем.
>
> Отдельно должны совместно утвердить продуктовую политику: pending bytes сохранить
> и скрыть; required cold start offline скрывает private до проверки сервера.
> Второй пункт меняет уже принятый mobile offline-сценарий, поэтому автоматически
> согласованным его не считаем. В legacy0.1.1 текущий offline остаётся прежним.
> TTL pending30мин, сохранение прежнего expiresAt после email confirm и отсутствие
> refresh поняты; reset grant не auth token, успешный reset ведёт к обычному login.
>
> После ответов и утверждения матрицы можно отдельно поручить план клиентской
> интеграции. Этот review не принимает strict0.2 и не включает rollout.

## Ручное сохранение обзора

Из текущего checkout, после просмотра diff:

```powershell
git add -- docs/STATUS_MOBILE.md docs/research/2026-11-05-mobile-n2-compatibility-review.md docs/superpowers/specs/2026-11-05-account-lifecycle-client-matrix.md mobile/artifacts/n2-compat-review/verification.json
git diff --cached --check
git commit -m "docs(mobile): review N2 lifecycle compatibility"
git push -u origin chore/mobile-n2-compat-review
Start-Process 'https://github.com/KDvibers/HackAtlas/compare/main...chore/mobile-n2-compat-review?expand=1'
```

При необходимости PR в main: заголовок `docs(mobile): review N2 lifecycle compatibility`;
описание: «Сопоставлены матрица N2, API0.1.1 и текущий Kotlin-клиент. Зафиксированы
границы wire/behavior compatibility, решения по offline/cache и вопросы бэкендчику.
24 Python tests,9 DTO cases и schema audit PASS. Только документация/результат обзора,
без изменений mobile/backend/API/infra и без утверждения strict0.2».
Создание PR — вручную после push; commit/push/merge агент не выполнял.
