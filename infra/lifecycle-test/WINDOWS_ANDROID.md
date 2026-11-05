# Закрытый N2: Windows + Android Studio Emulator

5 ноября 2026. Backend-база `fb05a31` (merged PR29), ветка подготовки
`chore/n2-android-handoff`. Флаг `serve --demo` и IPv4 launcher добавляются этим PR:
для повторения нужна эта ветка либо main после её merge, а не только старый fb05a31.
Канонический API остаётся0.1.1. Lifecycle проверяется только по отдельному кандидату
0.2.0-test.1 и [матрице интеграции](../../docs/superpowers/specs/2026-11-05-account-lifecycle-client-matrix.md).

Друг проверяет Android16/API36 x86_64 Pixel8 и Android14/API34 с прежней tzdata.
Native iOS проверяется отдельно на Mac. Поддержка новых мобильных экранов и cache
policy реализуется другом; эти команды не добавляют её в текущий клиент.

## 1. Где поднимать backend

Рекомендуемый вариант — собственный стенд на Windows-компьютере друга:
backend/PostgreSQL/Mailpit запускаются в его WSL2, Android Emulator — в Windows.
Наш localhost не является удалённым адресом для другого компьютера. Staging,
публичный tunnel и LAN bind для этого теста не нужны.

Нужны установленный WSL2/Linux, JDK21, Python3.12+ и работающие Docker/Compose
из обычного WSL-пользователя. Можно использовать существующую Docker Desktop WSL
integration или уже настроенный Linux daemon. Не нужны реальные пароли/SMTP provider.
Конфигурацию Docker/WSL не менять молча при ошибке; сначала проверить доступ.

В терминале **WSL**, в checkout нужной ветки:

```bash
java -version
python3 --version
docker version
docker compose version
git status --short --branch
git rev-parse HEAD

cd backend
bash ./mvnw --batch-mode --no-transfer-progress verify
cd ..

mkdir -p "$HOME/.local/share"
python3 scripts/lifecycle/local.py prepare --state-dir "$HOME/.local/share/hackatlas-n2-android"
python3 scripts/lifecycle/local.py up --state-dir "$HOME/.local/share/hackatlas-n2-android"
python3 scripts/lifecycle/local.py serve --state-dir "$HOME/.local/share/hackatlas-n2-android" --demo
```

`prepare` выполняется один раз для нового state-dir; существующие settings/key он
не перезаписывает. `up` запускает только PostgreSQL/Mailpit, `serve` — backend в
текущем терминале. Flyway автоматически применяет V1–V9; DEMO seed выполняется при
старте с явным `--demo`. Отдельные команды migrate/seed не нужны. Без этого флага
DEMO выключен. State-dir с синтетическим паролем и32-byte key находится вне Git.

Clock остаётся **системным**: fixed clock остановил бы OTP expiry/cooldown и мешал
recovery после смены credentials. Поэтому не подставлять demo.as-of ради каталога.
Сроки/registrationStatus DEMO зависят от текущей даты; прошедшее событие остаётся
пригодным для проверки details/saved/import. Не фильтровать только OPEN для этой проверки.
Все seed-события имеют isDemo=true; не считать их реальными объявлениями.

## 2. Адреса и проверка слоя Windows →WSL

| Назначение | Windows | Android Emulator, обычный маршрут |
|---|---|---|
| Полный API base URL | `http://127.0.0.1:8081/api/v1` | `http://10.0.2.2:8081/api/v1` |
| Health | `http://127.0.0.1:8081/actuator/health` | `http://10.0.2.2:8081/actuator/health` |
| Mailpit inbox | `http://127.0.0.1:18025` | При необходимости браузер эмулятора: `http://10.0.2.2:18025` |

В **Windows PowerShell**, пока serve работает:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8081/actuator/health'
$N2Page = Invoke-RestMethod 'http://127.0.0.1:8081/api/v1/hackathons?size=1'
$N2Page.items | Select-Object id, title, isDemo
Invoke-RestMethod 'http://127.0.0.1:18025/api/v1/messages' | Select-Object total
```

Health должен вернуть UP, каталог — DEMO, Mailpit API — ответ с числом сообщений.
Если Windows не видит backend, не переходить сразу к диагностике Android.
По [документации WSL](https://learn.microsoft.com/en-us/windows/wsl/networking)
Linux-сервис обычно доступен Windows через localhost. Здесь фактически выявлен и
исправлен случай Java IPv4-mapped IPv6: локальный launcher задаёт
`-Djava.net.preferIPv4Stack=true`, не меняя bind127.0.0.1. SMTP остаётся на loopback.
Не заменять его на0.0.0.0 и не отключать firewall/TLS/security checks.

SMTP sink11025 и PostgreSQL55432 нужны только backend/оператору. Приложение обращается
только к API; не подключать его к БД и не давать ему доступ к private settings/key.
Mailpit читает разработчик в Windows browser; его креды/письма не включаются в приложение.

## 3. Настройки Android debug-сборки

Адрес10.0.2.2 — [специальный alias loopback хоста](https://developer.android.com/studio/run/emulator-networking-address).
127.0.0.1 внутри эмулятора означает сам эмулятор, если adb reverse не настроен.
Для первого теста можно открыть health в его браузере; это проверяет транспорт,
а не поддержку OTP/session/cache в приложении.

Существующие Gradle properties, из каталога `mobile` в Windows PowerShell:

```powershell
.\gradlew.bat :androidApp:assembleDebug -Phackatlas.debugSource=REMOTE '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8081/api/v1' -Phackatlas.allowDebugHttp=true
```

Это задаёт REMOTE и HTTP только для указанного debug-host. Новый required test mode
и lifecycle flow друг реализует по матрице; не выдумывать уже существующий Gradle flag
для них. Текущий legacy клиент не считается совместимым с pending/recovery только
из-за успешного подключения или ignoreUnknownKeys.
Release остаётся HTTPS/без debug HTTP. При смене base URL менять source scope с
правилами очистки по матрице; нельзя переносить Bearer/private cache между адресами.

### Альтернатива: точечный adb reverse

Если стандартный emulator alias не работает, после успешного Windows health можно
проверить [adb reverse](https://developer.android.com/develop/ui/views/layout/webapps/access-local-server).
Указать фактический SDK path из Android Studio и serial из devices, особенно если
API34/API36 запущены одновременно:

```powershell
$N2Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $N2Adb devices -l
$N2DeviceSerial = 'emulator-5554'
& $N2Adb -s $N2DeviceSerial reverse --no-rebind tcp:8081 tcp:8081
& $N2Adb -s $N2DeviceSerial reverse --list
```

Для этого маршрута app base URL — `http://127.0.0.1:8081/api/v1`: пересобрать debug с
этим debugApiBaseUrl, чтобы generated cleartext allowlist также соответствовал host.
Mailpit можно оставить в Windows browser. После теста удалить только своё правило:

```powershell
& $N2Adb -s $N2DeviceSerial reverse --remove tcp:8081
```

Не применять remove-all к чужим mappings. На каждом AVD reverse задаётся отдельно.

## 4. DEMO и сценарий приёмки

Для кеша/import нужен опубликованный isDemo=true ID из реального каталога этого
стенда. Проверять details по полученному ID; page size1 позволяет проверить вторую
страницу. В фактическом запуске каталог содержал5 опубликованных событий; проверенный
DEMO ID: `ff4ed922-d2c6-5e7e-b7f3-c532efe4b34d`. Пользователей создавать синтетических, например
новый уникальный адрес на example.com для каждого независимого прогона.

1. Сохранить событие гостем. Register →pending: GET /me доступен,9 guarded operations
   возвращают403 EMAIL_NOT_VERIFIED; guest bookmarks остаются.
2. Request verification без тела, код из Mailpit →confirm204 →GET /me true. Код —
   строка8 цифр; не логировать/сохранять password/code/grant. TTL сессии не продлевается.
3. Проверить saved/import после verified и неверный delete-password:401
   INVALID_CREDENTIALS не должен завершить действующую сессию (GET /me200).
4. Явно выйти для native recovery; guest сохранить. Request/verify/confirm reset
   без Authorization →204 без autologin. Старые tokens должны получать401, новый
   обычный login с новым паролем —200.
5. Проверить400 неверного кода,429 с Retry-After, потерянный ответ, background,
   cold start offline, два аккаунта/source и поздние ответы по принятой матрице.

**Общий recipient cooldown:** запрос verification и следующий password-reset для
того же email используют общую паузу60с. Немедленный переход может вернуть429;
уважать Retry-After, не отключать лимитер и не замораживать clock. Адреса unknown/deleted
получают тот же202 при принятой задаче, но письма может не быть. Inbox не гарантирует
получение нового кода после любого повторного запроса: сверять актуальный challenge.

## 5. Что записать другу по каждому AVD

- Commit backend и mobile, выбранные mode/base URL, serial и build fingerprint.
- API level, ABI, модель AVD; для API34 фактическую tzdata, а не только номер SDK.
- Health/API и Mailpit reachable; конкретные PASS/FAIL по разделу4 и матрице.
- Сценарии cache/session/guest с ожидаемым и фактическим результатом, screenshots без
  codes/tokens/passwords/реальных адресов. Серверный requestId допустим.

Команды чтения build/tzdata для каждого serial (такой путь уже использует существующий
mobile tzdata smoke, без изменения данных устройства):

```powershell
& $N2Adb -s $N2DeviceSerial shell getprop ro.build.version.sdk
& $N2Adb -s $N2DeviceSerial shell getprop ro.build.fingerprint
& $N2Adb -s $N2DeviceSerial shell head -c 11 /apex/com.android.tzdata/etc/tz/tzdata
```

Если путь недоступен, записать это и использовать существующую диагностику друга;
не считать API34 автоматически доказательством старой tzdata.

## 6. Завершение

Остановить serve через Ctrl+C. В том же WSL checkout:

```bash
python3 scripts/lifecycle/local.py down --state-dir "$HOME/.local/share/hackatlas-n2-android"
```

Down удаляет только контейнеры этого state-dir; PostgreSQL/Mailpit tmpfs исчезают.
State-dir с key/settings остаётся для оператора, не отправляется в Git/чат. Ручное
удаление этого каталога — после проверки его точного пути и окончания испытания.
Восстановление БД из backup и публичный deployment в этот handoff не входят.

## 7. Фактически выполнено здесь

Maven verify:47 unit+309 integration,0 failures/errors/skipped. Lifecycle Python11
PASS, включая наблюдавшийся RED для нового --demo и последующий GREEN.
Фактический native Windows Python probe через127.0.0.1 выполнил24 HTTP проверки:
health/Mailpit,3 public GET, pagination, marked DEMO, pending403, real SMTP verification,
verified saved, wrong delete-password401 с последующим GET /me200, recovery с
Retry-After, reset grant не Bearer,204 без autologin, old token/password refusal и
новый login. AsOf продвинулся: clock не фиксирован. Это server/transport evidence,
не проверка мобильного cache/UI.

Обнаруженный transport RED (WSL API работает, Windows8081 timeout при tcp6 mapped
listener) сменился GREEN после preferIPv4Stack в local launcher. Системные WSL/firewall
настройки не менялись. Backend и subprocesses выполнялись UID1000; Compose user999/1000,
опубликованные ports127.0.0.1 инспектировались. Own containers/processes/state secrets
после прогона удалены. Журналы/санитизированный отчёт остаются только в ignored target.

SDK/adb в доступном окружении не обнаружены: **API36 Pixel8 и API34 old tzdata — не
проверены**, их прогоны выполняет друг. **Native iOS — не проверен, нужен Mac.**
Наличие поддерживаемого адреса не считается Android device PASS.
