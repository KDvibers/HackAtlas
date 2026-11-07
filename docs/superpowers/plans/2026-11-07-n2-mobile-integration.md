# Closed N2 Mobile Integration Plan

> **For agentic workers:** use superpowers:executing-plans inline, по одному заданию.
> Реализация требует отдельного поручения мобильному разработчику. Этот PR только
> фиксирует правила/план; product code не менять. Commit/push/PR выполняет пользователь.

**Goal:** подключить Kotlin-клиент к закрытому N2: pending/verification/recovery,
поколения сессии и защита saved/applications при сохранении legacy0.1.1 поведения.

**Architecture:** явный opt-in REQUIRED_N2 для отдельного debug REMOTE source;
LEGACY по умолчанию. Session client владеет одной authoritative access/scope snapshot,
public recovery transport отделён от Bearer. Оба stores используют одинаковый gate
и generation fencing. Compose UI остаётся общим для Android/iOS.

**Tech Stack:** существующие Kotlin/Compose Multiplatform, Ktor MockEngine,
kotlinx.serialization/coroutines/datetime, Android Keystore/iOS Keychain,
PostgreSQL+Mailpit через готовый закрытый backend. Новые библиотеки не нужны.

**Spec:** [согласованные4 правила](../specs/2026-11-05-account-lifecycle-client-matrix.md#0-согласованные-правила-закрытого-n2--7-ноября-2026),
[совместимость](../../../api/ACCOUNT_LIFECYCLE_COMPATIBILITY.md),
[31 фактический пример](../../examples/n2-http/README.md).
База main `c543eb0` (PR33); backend test candidate0.2.0-test.1, canonical0.1.1.

## Global Constraints

- Opaque Bearer; без JWT, refresh endpoint и refresh-token flow.
- LEGACY0.1.1 сохраняет нынешний offline read-only сценарий обоих private stores.
- Pending N2 сохраняет Bearer/оба private caches, скрывает private, блокирует writes/import.
- Restored N2 cold start требует GET/me true; warm offline читается только в том же
  процессе/сессии до expiresAt. Никаких прав по cached emailVerified.
- Новая успешно принятая login/register-сессия всегда получает новую generation;
  неудачный login сам по себе не очищает прежнюю сессию. Guest bytes сохраняются.
- Recovery доступен signed-out без Authorization; grant не Bearer; reset204 без autologin.
- PASSWORD_RESET_REQUIRED только login403; delete-password401 INVALID_CREDENTIALS
  сохраняет Bearer; поздние ответы старого scope не меняют новый.
- Canonical api/openapi.yaml, api/README.md, canonical fixtures и backend/SQL не менять.
- Не добавлять новые M3 формы reports/suggestions/delete-account, reminders, SMTP
  provider, legacy enrollment, общий strict source или capabilities endpoint.
- Дизайн [STYLE_LOCK](../../STYLE_LOCK.md) и общие компоненты M1 сохраняются.
- Сборка JDK21; текущие Kotlin/Compose/AGP/Gradle pins из mobile/TOOLCHAIN.md и
  mobile/gradle/libs.versions.toml не обновлять ради N2. Shared JVM target17 прежний.
- Authenticated recovery entry ещё не согласован: здесь только кнопка на signed-out
  login. Не добавлять автоматический logout или кнопку recovery в active profile.
- Названия новых классов/Gradle property ниже — техническая структура будущей
  реализации, а не уже доступные функции main. Общий rollout/minimum builds не приняты.

## Current code map

Пути ниже относительно репозитория. Для краткости:
`C = mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile`,
`T = mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile`.

| Существующая часть | Что требуется дополнить |
|---|---|
| C/data/ContractDtos.kt: ProfileDto | Нет emailVerified; missing/null нужно различать до typed decode |
| C/data/auth/AuthModels.kt | AuthError.Http теряет Problem.code; LocalSessionIdentity имеет только userId/expiresAt |
| C/data/auth/AuthSessionClient.kt | Mutex/secure store уже есть; requireProtected очищает на любой401; нужен context+scope fence |
| C/ui/auth/AuthStore.kt | Process-owned coordinator; finally/error callbacks тоже должны проверять generation |
| C/ui/saved/AccountSavedStore.kt | verified(user) означает Authenticated, не проверку почты |
| C/ui/applications/ApplicationStore.kt | То же; visibleFor проверяет владельца, но не email/access state |
| C/ui/CatalogRuntime.kt, C/ui/AppState.kt | Владение clients/stores/lifecycle hooks; один process runtime |
| C/App.kt | cacheVisible/detail/profile/saveable state и обе private поверхности |
| Android HackAtlasApplication.kt / iOS MainViewController.kt | Wiring режима/общих stores; lifecycle hooks уже существуют |

StoredSession и persisted cache headers уже source/user-scoped. Generation —
локальная metadata, не новый server field и не cached verification proof.

## Review Focus

1. Missing emailVerified отличается от explicit null; legacy остаётся рабочим,
   required не делает fallback. Tests в Task1.
2. Повторный login того же UUID с тем же expiresAt всё равно новый scope;
   поздний401, logout-finally или disk write не стирает новую сессию. Task2–3.
3. Pending/Unknown не показывает private ни на один frame через один из двух
   stores, detail/calendar/editor/counters/import. Task3/5.
4. Публичный recovery без Bearer; background/новый flow уничтожает RAM proofs;
   потерянный consuming response не вызывает автоматический повтор. Task4/5.
5. Cold offline N2 и warm offline различаются, legacy offline прежний; failure
   очистки не открывает старого владельца. Task3/6.

## Task 1 — mode, DTO, Problem и MockEngine fixtures

**Files:** create C/domain/AccountLifecycleConfiguration.kt,
C/data/auth/LifecycleDtos.kt; modify C/data/ContractDtos.kt, C/data/auth/AuthModels.kt,
C/data/auth/AuthSessionClient.kt, mobile/shared/build.gradle.kts;
create T/data/auth/N2ContractTest.kt; extend T/data/auth/AuthSessionClientTest.kt.

**Interfaces:** AccountLifecycleMode { LEGACY, REQUIRED_N2 };
readEmailVerification(profile: JsonObject, mode): EmailVerificationStatus
{ UNKNOWN, PENDING, VERIFIED }; AuthError.Http сохраняет status/retryAfterSeconds
и добавляет nullable problemCode/requestId. Текущие default constructors остаются LEGACY.
Lifecycle DTO точно повторяют EmailActionChallenge, EmailCodeRequest,
PasswordResetRequest/Grant/ConfirmRequest из существующего контракта.

- [ ] RED: MockEngine register/login/me принимает missing flag только LEGACY;
  false/true типизированы, explicit null/string/number дают InvalidResponse,
  missing в REQUIRED_N2 даёт UnsupportedServer без downgrade.
- [ ] Читать presence из raw JsonObject ДО typed ProfileDto decode, в том числе
  вложенного AuthResponse.user. Одного Boolean?=null недостаточно для null/missing.
  Новый flag не становится default true/false; legacy gate его не навязывает.
- [ ] RED: valid delete-password401 сохраняет смысл INVALID_CREDENTIALS; login401
  не отзывает активную identity; protected AUTH_REQUIRED401 очищает только matching
  scope; generic403 не logout; PASSWORD_RESET_REQUIRED применим только к login.
- [ ] Ввести operation/status/code classifier. Problem.status должен совпадать с
  HTTP status; не разбирать localized detail. Неизвестный/невалидный protected401
  fail-closed для текущего matching scope, без угадывания delete-password exception.
  Retry-After сохранить в секундах; ошибок/тел DTO не выводить в логи/toString.
- [ ] Добавить отдельный generateN2TestFixtures по аналогии с existing canonical
  generator: читать docs/examples/n2-http/responses.json ТОЛЬКО в commonTest.
  Не менять root canonical fixtures/manifest и не упаковывать N2 samples в commonMain/APK.
- [ ] MockEngine helper выбирает caseId, передаёт status/headers, для204 body="",
  для остальных сериализует body. Часы теста согласовать с captured expiresAt,
  не подменять даты в фактическом fixture. Negative mutations помечать как synthetic tests.
- [ ] GREEN: все новые tests + существующие ContractParsing/AuthSessionClient tests.

## Task 2 — authoritative session snapshot и поколение

**Files:** create C/data/auth/SessionScope.kt, C/data/auth/AccountAccess.kt;
modify C/data/auth/{AuthModels,AuthSessionClient,AuthHttpEngine}.kt,
C/ui/auth/AuthStore.kt, C/ui/CatalogRuntime.kt;
create T/data/auth/SessionScopeTest.kt; extend T/ui/auth/AuthStoreTest.kt.

**Interfaces:** SessionScope(apiBaseUrl: String, userId: String, generation: Long);
SessionGenerations.next(): Long (suspend/thread-safe allocation);
SessionSnapshot(scope, auth, access, expiresAt) как ОДИН authoritative StateFlow.
AccountAccess = Unknown/Pending/Verified/Unsupported; signed-out/storage state
не приравниваются к Pending. AuthStore публикует snapshot, а не независимо
собранные фрагменты, способные временно показать старый profile с новым owner.

- [ ] RED: два успешных login одинакового UUID/expiresAt/fixture token дают разные
  generation. HTTP failure/invalid auth response не заменяет прежнюю сессию.
  Secure storage failure отдельно ведёт в StorageUnavailable, без private UI.
- [ ] Counter принадлежит process owner и не сбрасывается при смене клиента/source;
  при restore выделить свежий bootstrap scope, но не менять generation на каждом GET/me.
  После проверки и успешного persistence новой сессии publish происходит атомарно.
  Для нового login правдивый AuthResponse может задать Pending/Verified; при restore
  только GET/me true открывает N2 private. У legacy сохраняется прежняя семантика.
- [ ] Session-bound APIs принимают expectedScope вместо одного expectedUserId:
  profile/updateProfile/listSaved/save/unsave/listApplications/markApplied/removeApplication.
  Проверять expectedScope ДО выбора Bearer/отправки и ПЕРЕД применением результата.
  Старый intent того же userId не должен случайно отправиться с НОВЫМ Bearer.
- [ ] clearIfCurrent(scope) и secure-store writes сравнивают/commit-ят scope под
  тем же serialization fence, что session replacement. Простого if перед await IO
  или cancellation недостаточно; NonCancellable не разрешает stale commit.
- [ ] RED: поздние401/200/204, прежний logout-finally и auth error не меняют новую
  snapshot/notice/busy/storage. Смена source туда-обратно тоже не оживляет старый scope.
  Использовать CompletableDeferred/контролируемую задержку commit; не убирать Mutex
  ради искусственного race. Проверять effects, а не только номер generation.
- [ ] Cold N2 starts Unknown. Warm offline не сбрасывает Verified той же сессии;
  local expiry/достоверный текущий revoke закрывают доступ. Profile-check/verification
  request epoch дополнительно не даёт старому GET/me откатить новое состояние.
- [ ] GREEN: SessionScope/AuthStore/старые auth tests; Bearer не входит в public identity/toString.

## Task 3 — оба private stores и все производные

**Files:** modify C/ui/saved/AccountSavedStore.kt,
C/ui/applications/{ApplicationStore,ApplicationStatus}.kt,
C/ui/profile/ProfileEditStore.kt, C/ui/AppState.kt, C/App.kt;
extend T/ui/{AccountSavedStoreTest,ApplicationStoreTest,ApplicationVisibilityTest,
ProfileEditStoreTest,SourceNavigationTest}.kt; create T/ui/N2PrivateAccessTest.kt.

**Interfaces:** один access gate для обоих stores:
canReadPrivate(snapshot, mode, now), canWritePrivate(snapshot, mode, now).
LEGACY повторяет существующие правила; N2 использует текущий scope/server proof.
Существующие cache.write(..., shouldCommit) guards учитывают scope И access epoch.
Persisted schemas/path ownership не меняются; cached flag не хранит разрешение.

- [ ] RED: Pending и Unknown сохраняют Bearer/bytes, но публичное UI-state обоих
  stores не выдаёт entries/marks/counters. Не превращать pending в owner=null:
  existing transition(null) удаляет cache и нарушит retention.
- [ ] Pending/Unknown не отправляют private GET/PUT/DELETE/import: проверить нулевое
  число таких вызовов, включая CatalogRuntime.onForeground. Ожидаемое ограничение
  показывается как access state, не как generic ошибка синхронизации.
- [ ] При переходе в Pending закрыть visibility и writes/import синхронно до
  асинхронных работ. При новой generation инвалидировать RAM callbacks/derived UI;
  same-user retained bytes можно открыть только по актуальному gate. Не стирать
  pending bytes только из-за новой same-user generation.
- [ ] RED: saved snapshot detail/календарь/editor/import/profile counters/application
  control не показывают старое account state. Public catalogue/detail и отдельный
  guest store доступны; скрытый account snapshot не выдаётся за public fetch/guest.
- [ ] RED: stale disk write после смены generation не перезаписывает новый cache;
  late saved/application success не восстанавливает private в Pending/после logout.
  После текущего protected401/local expiry/logout очистить ОБА private stores,
  editor/import RAM, navigation/saveable state; guest bytes неизменны.
- [ ] Wrong delete-password401 не выполняет cleanup; проверить classifier на fixture
  без добавления нового delete-account UI. Cleanup failure → BLOCKED/private hidden,
  повторная очистка, без активации нового private owner поверх старых данных.
- [ ] RED: N2 cold offline hidden; после GET/me true warm offline read-only до
  прежнего expiry; после force-stop снова hidden. LEGACY cold offline saved и
  applications остаются доступными как в принятых tests PR31.
- [ ] GREEN: оба store suites + offline/visibility/profile/navigation regressions.

## Task 4 — verification и публичный recovery

**Files:** extend C/data/auth/AuthSessionClient.kt;
create C/data/auth/RecoveryClient.kt, C/ui/auth/VerificationStore.kt,
C/ui/auth/RecoveryStore.kt; wire C/ui/{CatalogRuntime,AppState}.kt;
create T/data/auth/{VerificationClientTest,RecoveryClientTest}.kt,
T/ui/auth/{VerificationStoreTest,RecoveryStoreTest}.kt.

**Interfaces:** requestVerification(scope): Challenge? (204 -> null),
confirmVerification(scope, challengeId, code): Unit;
RecoveryClient.request(email): Challenge,
verify(challengeId, code): ResetGrant, confirm(resetToken, newPassword): Unit.
RecoveryStore — отдельный source/flow-generation owner RAM; нет userId/Bearer.
VerificationStore связан с SessionScope + собственным challenge/request epoch.

- [ ] RED/GREEN MockEngine: все5 POST используют точные payload/status из контракта.
  Verification request не отправляет body; request204 и confirm204 требуют GET/me
  перед открытием private. Подтверждение не переписывает stored expiresAt.
- [ ] Recovery имеет отдельный client/engine из existing no-redirect/no-retry
  factory; Authorization отсутствует на всех трёх public operations, даже если
  позже в приложении появилась сессия B. Ошибка public flow не очищает её.
- [ ] Проверять challenge/resetToken по соответствующим schemas (43 URL-safe chars),
  code8 ASCII digits, expiresAt и resendAfterSeconds60; новый пароль по Register
  constraints12–256, без trim/изменения исходной строки. Grant не принимается как session.
- [ ] RED: wrong/expired proof400,429 Retry-After,503/timeout/offline сохраняют
  корректный state. Не обещать доставку по202, не раскрывать существование аккаунта.
  Нет автоматических retry consuming confirm/verify POST: lost response означает
  неизвестный результат, явный новый flow/login по матрице, без ложного success.
- [ ] Reset204 очищает flow RAM и показывает обычный login, не создаёт StoredSession
  и не сохраняет новый пароль для autologin. Server revocation старых sessions
  подтверждается отдельными HTTP checks в E2E, а не догадкой по локальному UI.
- [ ] RED: background/закрытие/новый recovery/source change очищают OTP/newPassword/
  resetToken/challenge context; late verify response не возвращает grant. Переход
  к login B инвалидирует recovery A; его поздние ответы не меняют B. Если сервер
  действительно отозвал текущую сессию, последующий её protected401 обрабатывается
  в собственном scope; generation не отменяет уже выполненную server mutation.
- [ ] Никаких secrets в rememberSaveable/settings/private snapshots/logs/toString.
  Все новые tests + legacy auth regressions GREEN.

## Task 5 — общий UI, wiring и закрытая конфигурация

**Files:** modify C/ui/auth/{AuthScreen,AccountProfileScreen}.kt, C/App.kt,
C/ui/saved/AccountSavedStatus.kt, C/ui/applications/ApplicationStatus.kt;
create C/ui/auth/{VerificationScreen,RecoveryScreen}.kt;
modify mobile/shared/src/commonMain/composeResources/values/strings.xml,
mobile/androidApp/build.gradle.kts,
mobile/androidApp/src/main/kotlin/dev/hackatlas/mobile/HackAtlasApplication.kt,
mobile/shared/src/iosMain/kotlin/dev/hackatlas/mobile/MainViewController.kt,
mobile/iosApp/iosApp/HackAtlasApp.swift; create T/domain/AccountLifecycleConfigurationTest.kt.

**Interfaces:** future debug property `hackatlas.debugLifecycleMode=REQUIRED_N2`
-> BuildConfig.ACCOUNT_LIFECYCLE_MODE -> CatalogRuntime mode. Default LEGACY;
REQUIRED_N2 разрешён только explicit debug REMOTE source. Unknown mode/configuration
недопустим, не маскируется fallback. В iOS добавить явный параметр и обновить Swift
call site; прежний LEGACY default сохраняется. Этих settings СЕЙЧАС в main нет.

- [ ] Pending profile содержит verification action, public/guest navigation и logout;
  private contents скрыты. Verification form поддерживает202/204/cooldown/400/429/503.
- [ ] Recovery entry только на signed-out login (включая предложение после
  PASSWORD_RESET_REQUIRED). Active profile entry/автоматический logout не добавлять.
  Unknown/unsupported показываются отдельно от guest/pending; нет silent mode downgrade.
- [ ] Entry использует signed-out + отсутствие busy auth operation: пока LOGIN/
  REGISTER/RESTORE выполняется, не открывать конкурирующий recovery. Новый login
  инвалидирует прежний recovery flow, поздние ошибки не возвращают закрытый экран.
- [ ] UI не сохраняет secrets; background hooks в CatalogRuntime очищают flow RAM,
  но не превращают warm offline в cold start и не продлевают expiry. Восстановленные
  routes/saveable state не показывают private до gate. Keyboard/Back/rotation проверить.
- [ ] Переиспользовать M1 components/fonts/colours. Error.text строится по code/context,
  не по server detail; показать requestId для диагностики без credentials.
- [ ] RED/GREEN mode tests: default/explicit LEGACY прежний; required debug+REMOTE
  работает; required+DEMO/release/неизвестный mode отклоняется. Никакого runtime
  включения по optional flag,403,404 или OpenAPI metadata.
- [ ] Native iOS только на Mac, с отдельно согласованным HTTPS dev source. Текущий
  MainViewController не имеет ATS exception: не отключать TLS/ATS/cert checks и не
  обещать доступ iOS к Android-only10.0.2.2 HTTP адресу.

## Task 6 — проверка и handoff на компьютере пользователя

**Files:** create mobile/scripts/android_n2_smoke.py и mobile/artifacts/n2/README.md;
обновить только docs/STATUS_MOBILE.md силами мобильного исполнителя. Backend STATUS
при реальном совместном запуске обновляет backend-разработчик. Старые smoke scripts
с портами8770/8774/fixtures/cleanup не запускать без адаптации на пользовательском стенде.

- [ ] Из корня повторить canonical validator и Python transition/lifecycle tests.
  Из mobile выполнить с ограничением памяти:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :shared:testDebugUnitTest :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures
```

- [ ] После Task5 собрать closed REMOTE APK с будущим mode property ниже. Проверить
  фактическое значение режима в сборке/санитизированном evidence: сегодня Gradle
  ещё не реализует этот property, и его передача сама не доказывает N2.

```powershell
.\gradlew.bat --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :androidApp:assembleDebug '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8081/api/v1' '-Phackatlas.allowDebugHttp=true' '-Phackatlas.debugLifecycleMode=REQUIRED_N2'
```

- [ ] Поднять собственный N2 по [Windows runbook](../../../infra/lifecycle-test/WINDOWS_ANDROID.md):
  host API127.0.0.1:8081/api/v1, emulator10.0.2.2:8081/api/v1, Mailpit127.0.0.1:18025.
  Системный Clock, explicit demo; отдельные synthetic accounts. Не менять пароль,
  email_verified или delivery address SQL на пользовательском аккаунте.
- [ ] Один AVD одновременно: основной Pixel8/API36 x86_64,2GB; regression API34
  со старой tzdata с фиксацией её фактической версии. Нужный старый образ не считать
  проверенным по одному номеру API. Не wipe/clear/stash чужие файлы/AVD/account data.
- [ ] E2E: register pending; guest доступен/private hidden; настоящий SMTP code;
  confirm -> GET/me true; expiresAt не продлён; оба private stores доступны после проверки.
- [ ] E2E: cold offline hidden, warm offline readable до той же expiry; pending
  bytes не удалены; guest bytes неизменны после logout/reset/revoked401/account switch.
- [ ] E2E: signed-out recovery через Mailpit, reset204, старые credentials/Bearer
  отклонены, новый login обычный. Новая same-user сессия и adverse completion schedules
  старых200/204/401 проверены common tests и доступными device сценариями.
- [ ] Сверить context errors/Retry-After/503 с fixtures. Для неверного delete-password
  использовать готовые HTTP fixtures/контрактную проверку, не расширять UI удаления.
- [ ] Evidence: commit/APK hash/mode/source/API/ABI/tzdata, expected/actual outcomes,
  screenshots без ввода паролей/OTP и payload logs, отсутствие Authorization/grants
  в экспортируемых материалах. Только тестовые идентичности; сохранить failure evidence.
- [ ] Mac/iOS compile/link/device/TLS/safe area/scene lifecycle — отдельный gate с
  фактическим результатом. На Windows не ставить iOS PASS и не объявлять полный M4.
- [ ] Обновить STATUS_MOBILE: выполненные команды/результаты/непроверенные пункты;
  оставить изменения для ручных commit/push/PR, без выполнения их за пользователя.

## Completion и отдельные решения

Готово к закрытой Android-приёмке, когда Tasks1–5 и Android/common часть Task6
подтверждены реальными tests/evidence. iOS часть остаётся открытой до Mac-прогона;
Android-only результат не закрывает план для двух платформ. Canonical0.2, внешняя почта/deployment,
minimum client version, legacy NULL-address recovery и общий strict rollout не
закрываются этой работой. Authenticated recovery entry остаётся отдельным решением.
Наблюдение о синхронизации PR32 не объявлять исправленным без воспроизведения/проверки.

Этот план подготовлен для review; реализация НЕ выполнялась. В текущем docs-only PR
проверяются существующие контрактные/Python tests, ссылки, scope и секреты;
будущие Kotlin tests и native E2E выше ещё не написаны/не запускались.
