# Диагностика saved sync после входа — 7–8 ноября 2026

База main0de9776 (merged PR34), ветка `chore/n2-sync-diagnostics`.
**Статус исходного наблюдения PR32: OPEN_NOT_REPRODUCED. Исправление приложения
не подтверждено и не заявляется.** [Обезличенные результаты](2026-11-07-saved-sync-diagnostics.json).

## Исходное наблюдение

На первом локальном API36-прогоне после входа из гостевой сохранённой карточки
GameDev Jam показался диалог «Не удалось синхронизировать» с общим текстом
«Синхронизация не завершена. Сервер мог принять изменение…». Затем обновление,
личная отметка, профиль и перезапуск прошли. Старый PNG визуально перечитан:
диалог настоящий; его существование не выводится только из UI XML.

Для того события не были сохранены HTTP status/Problem.code/X-Request-Id,
duration и тип SavedFailure/AuthError. Из одного текста нельзя установить причину.
В [App.kt](../../mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/App.kt)
generic text соответствует SavedFailure.Auth или Catalog; Storage/ReadOnly/Busy
имеют другие тексты. Это статическое сужение, не доказательство конкретного subtype.
Сообщение используется также для ошибки refresh/read и само по себе не доказывает,
что клиент отправлял изменение или сервер его принял.

## Условия повторной проверки

- Android16/API36 x86_64, AVD HackAtlas_N2_API36, tzdata2025a,2048MB/2cores.
- Закрытый dev+lifecycle-test, PostgreSQL/Mailpit, system Clock,5 DEMO events.
  Backend/SMTP запускались обычным WSL UID1000; данные прежнего стенда не удалялись.
- Текущий mobile source идентичен dec07cc8, на котором возникло наблюдение;
  backend src/main и pom.xml идентичны f9ba707. Это проверено git diff --quiet.
- Новый synthetic account в КАЖДОМ случае: emailVerified=true получен реальным SMTP
  proof из Mailpit через HTTP, savedCount0/appliedCount1 как в исходном контексте.
  Никаких SQL verified/reset overrides и реальных пользовательских credentials.
- Продуктовый mobile-код не менялся. В ignored копии проекта первые три cases
  использовали только safe println типов/status/state; четвёртый — исходный код
  без этих вставок. ApplicationId изменён для отдельных диагностических пакетов,
  поэтому установка/выход не трогали dev.hackatlas.mobile.debug и его данные.

Это нынешний legacy-клиент с VERIFIED аккаунтом на закрытом backend N2, а не
реализация нового pending/verification/recovery UI или приёмка новых правил кеша.

## Сценарий

1. Поднять N2 по [Windows runbook](../../infra/lifecycle-test/WINDOWS_ANDROID.md).
2. Подготовить новый тестовый verified account с одной личной отметкой и пустым saved.
3. В гостевом режиме сохранить GameDev Jam, открыть «Сохранённое» и его snapshot.
4. Нажать «Войти, чтобы отметить», войти, дождаться возврата к подробностям.
5. Наблюдать не только отсутствие диалога: проверить authenticated control и
   завершённую saved synchronization — «Сохранить хакатон», без «Сохраняем изменение…».
   В каждом случае сохранён UI snapshot после окна наблюдения не менее18с.
6. Выполнить локальный выход; гостевая закладка остаётся. Для следующего случая
   использовать другой synthetic account, а не сбрасывать пользовательский app data.

## Результаты четырёх native cases

| Case | Подключение | Диагностика в копии | Результат |
|---|---|---|---|
|1| emulator10.0.2.2:8082 → local trace proxy → backend8081 | Safe type/state events | Вход/возврат успешны, saved завершён, диалога нет |
|2| Тот же proxy, повторный сценарий после выхода | Safe type/state events | То же, диалога нет |
|3| Прямой emulator10.0.2.2:8081, без proxy | Safe type/state events | То же, диалога нет |
|4| Прямой8081, отдельный чистый пакет | Исходный mobile source без println | То же, диалога нет |

В cases1–2 непосредственно захвачены native login200 и GET applications/saved200.
Private lists отвечали за31–32мс, login —62мс; saved page0/size50/total0/pages0,
applications total1/pages1. Metadata вынесены в JSON, без тел и credentials.
Для cases3–4 независимый wire capture отсутствует: успех основан на native UI и
завершении saved control, а не на выдуманных HTTP status/duration.
Все четыре cases прошли проверку свежести XML, retry count0 внутри сценариев.

Четыре успешных повтора не доказывают отсутствие редкого дефекта. Временные
условия первого boot/network/нагрузки исходного события полностью не восстановлены.
Ни network timeout, ни backend failure, ни DTO race не объявлены установленной причиной.

## Отдельно подтверждён дефект локального проверочного helper

При первоначальном запуске диагностического пакета uiautomator вернул
`ERROR: null root node returned by UiTestAutomationBridge.` Старый ignored helper
не проверил результат dump и прочитал существующий XML из прежнего фиксированного
пути. Он также не проверял package; вывод мог относиться к другому приложению.

В локальном `backend/target/android-local-control.py` исправлено:

- новый уникальный remote XML path при каждом dump;
- проверка сообщения успешного dump и совпадения пути до cat;
- проверка ожидаемого package; отказ/ограниченный retry вместо старого XML;
- удаление только собственного временного remote-файла после чтения.

Три изолированных tests: failed dump не читает previous XML, wrong package
отклоняется, успешный own-package dump читается. До исправления2 failures,
после —3 PASS. Скрипт/tests остаются ignored локальными инструментами, не product code.
При подготовке будущего mobile smoke нужно сохранить эти freshness guards.
**Этот дефект диагностики не установлен как причина старого диалога синхронизации.**

## Артефакты и проверки

Локально в ignored backend/target: sync-probe-run (4 JSON/UI/PNG cases, APK),
sync-probe-http.jsonl (route/status/duration/type metadata), n2-sync-*-build.log,
n2-sync-backend.log, helper freshness RED/GREEN logs и отдельная копия проекта.
В Git переданы только этот отчёт, metadata JSON и backend STATUS. Пароли, Bearer,
reset grants, OTP, request/response bodies, private .env/keys в них не записаны.
Полные UI snapshots содержат только тестовые данные и не добавляются в репозиторий.

Три debug APK сборки успешны (instrumented proxy/direct и clean direct). Не запускались
новый Maven verify или общий Kotlin unit suite: backend/mobile product source прежний;
это не утверждение, что прежние suite повторно прошли. Native iOS/API34/full N2 E2E
не выполнялись. Контракт/Python/security проверки этого отчёта отражены в STATUS.

Начальное состояние было «backend/AVD/контейнеры остановлены». После диагностики
Java process/proxy/AVD и только два поднятых HackAtlas containers остановлены;
containers/volumes/БД/старое приложение не удалялись. Созданы4 новых synthetic accounts
в локальной тестовой БД; прежние аккаунты не изменялись. Два отдельных probe APK и
ignored artifacts оставлены для локального review; обычный debug APK не заменён.

## Передача другу и следующий шаг

Сейчас нет доказанного backend defect для безопасного исправления. Исходный ticket
оставить открытым. Mobile Task1 уже предусматривает сохранение Problem.code/requestId;
нужно получить evidence при следующем реальном сбое, а не менять обработчик наугад.

Минимальный безопасный набор для повторного события: failure kind, HTTP status/code
(если HTTP вообще был), X-Request-Id, route/duration, source/local generation и
текущая фаза store — без Bearer/password/OTP/body. Если HTTP200, отдельно проверять
DTO/domain validation; если transport failure, status/code не выдумывать.
Поздние callbacks проверять по согласованному [плану N2](../superpowers/plans/2026-11-07-n2-mobile-integration.md).
Не объявлять исходный дефект исправленным по одному внедрению нового error mapping.
