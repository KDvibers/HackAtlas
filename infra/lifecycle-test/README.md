# N2: закрытый локальный стенд

Этот стенд проверяет почту и восстановление пароля только на синтетических аккаунтах.
Он не является staging и не меняет согласованный мобильный API 0.1.1.
Кандидат 0.2.0-test.1 создаётся отдельно в ignored `backend/target/`.

Windows + Android Emulator: [адреса, команды и проверка подключения](WINDOWS_ANDROID.md).

## Запуск в Linux / WSL

Нужны Java 21, Docker Compose и Python с зависимостями
`scripts/requirements-contract.txt`. Команды выполняются от обычного пользователя,
имеющего доступ к Docker, из корня checkout:

```bash
cd backend
bash ./mvnw --batch-mode --no-transfer-progress verify
cd ..
python scripts/lifecycle/local.py prepare --state-dir "$HOME/.local/share/hackatlas-n2-local"
python scripts/lifecycle/local.py up --state-dir "$HOME/.local/share/hackatlas-n2-local"
python scripts/lifecycle/local.py serve --state-dir "$HOME/.local/share/hackatlas-n2-local"
```

Родительский каталог `~/.local/share` должен существовать. `prepare` создаёт новый
приватный каталог 0700 с файлами 0600: случайный пароль БД и raw HMAC key 32 байта.
Повторный `prepare` откажется перезаписывать их. Не отправляйте эти файлы в Git/чат.
Launcher очищает унаследованные Spring/HackAtlas overrides, использует
`dev,lifecycle-test`, явно закрытый bind и выделенную БД. Flyway применяет V1–V9
автоматически; runtime помечает эту БД как lifecycle. Запуск её в legacy запрещён.
Стенд использует owner БД для dev-миграций; это не production схема ролей.

| Сервис | Адрес на этой машине |
|---|---|
| API | `http://127.0.0.1:8081/api/v1` |
| Почтовый ящик Mailpit | `http://127.0.0.1:18025` |
| SMTP sink | `127.0.0.1:11025` |
| PostgreSQL | `127.0.0.1:55432`, БД `hackatlas_lifecycle_test` |

Все опубликованные порты привязаны к 127.0.0.1. При занятых портах освободите их
для этого стенда; не меняйте bind на внешний адрес. В контейнере Mailpit работает
от UID1000, PostgreSQL — UID999; read-only root, сброшенные capabilities и tmpfs.
Docker daemon требует обычных прав Docker, но backend/скриптам root не нужен.
Mailpit не имеет relay, forwarding или внешнего провайдера. Для воспроизводимого
SMTP отключён только reverse DNS lookup (`MP_SMTP_DISABLE_RDNS`), как рекомендует
[документация Mailpit](https://mailpit.axllent.org/docs/configuration/runtime-options/).
Короткие SMTP timeouts backend остаются включены.

## Ручной сценарий

1. Зарегистрируйте синтетический адрес `tester@example.com`: ответ содержит pending
   Bearer с TTL30 минут и `user.emailVerified=false`.
2. С этим Bearer вызовите `POST /auth/email-verification/request` **без тела**.
   Скопируйте код из Mailpit, отправьте `challengeId` и `code` в confirm.
   `GET /me` вернёт true; срок уже выданной сессии не продлевается.
3. Без Bearer вызовите password-reset/request с email, затем verify с challenge/code.
   Полученный resetToken годится только для confirm с newPassword, не для GET /me.
4. Confirm вернёт204 без autologin. Старые native/admin sessions, остальные proofs
   и прежний пароль недействительны. Выполните обычный login с новым паролем.

Для неизвестного/удалённого адреса request также вернёт202, но письма не будет.
202 означает приём фоновой задачи, не обещание доставки. При заполнении очереди
все адреса получают одинаковый503. Для попыток действуют отдельные квоты/cooldown.
Не передавайте в этот стенд реальные адреса, пароли или восстановленные реальные БД.
Без дополнительного флага DEMO seed выключен. Для теста каталога/guest import
можно запустить `serve --demo`: включаются только маркированные isDemo=true события,
системный clock сохраняется. Отдельной команды seed нет, он выполняется при старте.
Фиксированный clock не подходит для живой проверки OTP: он остановил бы cooldown/expiry.

Из-за консервативной проверки времени задача с timestamp, равным моменту смены
credentials, отбрасывается. При ручном фиксированном Clock для следующего запроса
после reset нужно продвинуть clock; штатный системный Clock этого не требует.

## Завершение и данные

Остановите `serve` через Ctrl+C, затем:

```bash
python scripts/lifecycle/local.py down --state-dir "$HOME/.local/share/hackatlas-n2-local"
```

PostgreSQL и inbox живут в tmpfs, при удалении контейнеров их данные исчезнут.
В Mailpit максимум100 сообщений; queue16 задач только в памяти и теряется при
остановке. Приватный каталог settings/key остаётся у оператора; удалите его после
испытания, когда он больше не нужен. Скрипт не удаляет произвольные каталоги.
Expiry запрещает применение codes/grants; автоматическая очистка строк по сроку
хранения в N2 не реализована. Сроки хранения, durable queue, внешний SMTP и защита
публичного signup должны быть согласованы до внешнего rollout.

## Контракт и тесты

```bash
python scripts/lifecycle/render_test_contract.py
python -m unittest discover -s scripts/lifecycle -p 'test_*.py'
python scripts/lifecycle/validate_http.py --schema backend/target/lifecycle-test-openapi.yaml --responses backend/target/n2-contract
```

Последняя команда требует HTTP exports из успешного Maven verify. B1/B2/B3 по-прежнему
проверяются отдельно по canonical0.1.1. Матрица для обсуждения с мобильным разработчиком:
[N2 report](../../docs/research/2026-11-05-account-lifecycle-n2.md).
