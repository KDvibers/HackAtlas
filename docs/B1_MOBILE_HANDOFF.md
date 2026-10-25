# Подключение публичного каталога к Kotlin-клиенту

Обновлено 26 октября 2026 при подготовке B4. Проверенная актуальная `main` — `c8b6d1895253320879d6bb3a902750d9d15a1012`: B3 PR #8, mobile HTTP-client PR #6 и state stores PR #7 объединены. Backend CI main прошёл. Публичный каталог по-прежнему работает без авторизации по [OpenAPI](../api/openapi.yaml) и [API README](../api/README.md). Локальный комплект B4 в `chore/backend-release` не нужен для подключения к main; внешнего адреса он пока не создаёт.

## Base URL

Общего staging/публичного URL сейчас нет. Сервер разработчика запускается для локальных проверок и после них останавливается. Для независимой работы поднимите PostgreSQL и backend из `main` на своём компьютере.

| Где клиент | Base URL с `/api/v1` |
|---|---|
| На том же компьютере, где JVM | `http://127.0.0.1:8080/api/v1` |
| Android Studio Emulator, если JVM доступна на компьютере-хосте | `http://10.0.2.2:8080/api/v1` |
| iOS Simulator, если JVM работает на том же Mac | `http://127.0.0.1:8080/api/v1` |
| Физический Android/iPhone или другой компьютер в той же LAN | `http://<LAN-IP-сервера>:8080/api/v1` |

Для LAN запускать backend с `--server.address=0.0.0.0`; PostgreSQL оставлять на loopback. Устройство должно иметь доступ к порту 8080; при необходимости настройте узкое разрешение для частной сети, не отключайте firewall целиком. Эти адреса — схема настройки, а не подтверждённый адрес уже работающего общего сервера.

Для Windows + WSL сначала проверьте запрос из Windows к серверу WSL. `10.0.2.2` у Android Emulator ведёт к Windows-хосту; он сам по себе не решает проблемы WSL forwarding. В серверной среде 26 октября Windows→WSL localhost не отвечал, а обращение Windows→IP WSL прошло на временном тестовом сервере. Это не проверка доступа с телефона или другой машины. Если localhost не работает, проще запустить JVM непосредственно в Windows с Docker Desktop/Linux containers либо использовать проверенный доступный адрес WSL/LAN. Удалённый компьютер не может подключиться к чужому `localhost`.

HTTP допускается для этого локального debug/dev сценария; настройки cleartext нужны только debug-клиенту. TLS validation не отключать. Для общего внешнего HTTPS адреса требуется отдельный согласованный deployment B4; туннель или staging не созданы.

## Запуск из main

Требуются JDK 21 и Docker с Linux containers, доступные тому же окружению, где запускается JVM. Maven уже включён в репозиторий как Wrapper. Обновите чистый рабочий клон до `main`; несохранённые изменения не сбрасывайте.

PowerShell, из корня клона:

```powershell
git switch main
git pull --ff-only origin main

if (-not (Test-Path -LiteralPath .\infra\.env)) {
    Copy-Item -LiteralPath .\infra\.env.example -Destination .\infra\.env
}
```

В локальном `infra/.env` задайте собственный случайный hex-пароль `HACKATLAS_DB_PASSWORD` (например, 32 случайных bytes), без кавычек. Не присылайте этот файл или пароль и не коммитьте его. Остальные dev defaults: пользователь/БД `hackatlas`, URL `jdbc:postgresql://localhost:5432/hackatlas`. Существующий `.env` не перезаписывать; смена пароля в файле не меняет пароль уже созданного PostgreSQL volume.

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml up --detach --wait --wait-timeout 120 postgres

Set-Location backend
.\mvnw.cmd --batch-mode --no-transfer-progress verify
java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --hackatlas.demo.enabled=true --hackatlas.demo.as-of=2026-10-12T10:00:00Z
```

Linux/WSL/macOS: из корня клона используйте условное копирование `if [ ! -f infra/.env ]; then cp infra/.env.example infra/.env; fi`, заполните локальный пароль, затем те же `docker compose` команды. В каталоге `backend` вместо Windows wrapper запустите:

```bash
sh ./mvnw --batch-mode --no-transfer-progress verify
java -jar target/hackatlas-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --hackatlas.demo.enabled=true --hackatlas.demo.as-of=2026-10-12T10:00:00Z
```

Запускать Java именно из `backend/`: dev-профиль читает `../infra/.env`. Для телефона добавьте к команде Java `--server.address=0.0.0.0`. Если 5432 или 8080 заняты, сначала определите владельца порта; не останавливайте чужую БД.

## Проверка трёх endpoint

Опубликованное DEMO-событие: **`39287368-5259-5235-a1c4-25b4e1e8421e`** (AI Sprint).

Во втором терминале на компьютере с сервером:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:8080/api/v1/hackathons
Invoke-RestMethod http://127.0.0.1:8080/api/v1/hackathons/39287368-5259-5235-a1c4-25b4e1e8421e
Invoke-RestMethod http://127.0.0.1:8080/api/v1/metadata/filters
```

В bash замените `Invoke-RestMethod` на `curl --fail`. Ожидаются readiness `UP` и HTTP 200 у всех трёх GET. Они проверены реальными HTTP-тестами с PostgreSQL; [CI свежей main после PR #6](https://github.com/KDvibers/HackAtlas/actions/runs/40225964082) прошёл, B1 regression-проверка включала Bruno 10/10 и Schemathesis 201/201. Доступ с вашего устройства требуется проверить отдельно.

## DEMO и ограничения

- По умолчанию seed выключен, каталог свежей БД пуст. `--hackatlas.demo.enabled=true` включает канонические fixtures только dev/test без prod. Все эти события имеют `isDemo=true`; URL на example.com и призы не являются реальными сведениями.
- Для воспроизводимого непустого активного каталога используйте указанный фиксированный clock. Без него старые DEMO-события могут исчезнуть из списка из-за завершения; детали опубликованного события по ID при этом доступны. Seed добавляет отсутствующие записи, не перезаписывая ручные правки в существующей dev-БД.
- Все три GET публичные, Authorization не нужен. Регистрацию аккаунтов включать не требуется. Гостевые сохранения сейчас храните на клиенте; `/me/saved` относится к B2 и не нужен этому этапу интеграции.
- Расхождений реализации B1 с согласованным контрактом по выполненным проверкам не обнаружено. Параметры поиска/фильтров/пагинации, nullable/unknown поля, DATE/MINUTE и формат ошибок сохраняются. Нет отдельного endpoint календаря, автосбора реальных событий или fallback в DEMO при ошибке сети.
- Время только дня не превращать в 23:59 или countdown. Сбой сети не заменять DEMO; локальный snapshot показывать с его свежестью. Изменения API не требуются.

Для остановки приложения — Ctrl+C в его терминале. PostgreSQL можно остановить из корня клона командой `docker compose --env-file infra/.env -f infra/compose.yaml stop postgres`; volume сохраняется. Полные инструкции: [backend README](../backend/README.md), [CATALOG](../backend/CATALOG.md), [infra](../infra/README.md).
