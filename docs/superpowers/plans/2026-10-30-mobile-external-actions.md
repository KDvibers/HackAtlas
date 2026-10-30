# Mobile external actions implementation plan

> REQUIRED SUB-SKILL: superpowers:executing-plans. Один исполнитель, одно итоговое независимое review. Пользователь уже выбрал последовательную реализацию; commit/push/PR выполняет сам.

**Goal:** открыть официальную регистрацию и системный share из общих подробностей Android/iOS с сохранением дизайна и состояния.
**Architecture:** общая политика ссылок/доступности → существующие DetailScreen/App → platform адаптеры browser/share.
**Tech Stack:** существующие KMP/Compose/Ktor URL parser, Android Intents, UIKit; без новых зависимостей.
**Spec:** [design](../specs/2026-10-30-mobile-external-actions-design.md), Starter `api/README.md:74`, ROADMAP M2, STYLE_LOCK.

## Global Constraints

HTTPS only; DEMO только preview перехода и маркированный share; closed/cancelled/no-link не активная регистрация. API/backend/старые гостевые данные/ресурсы не менять. Из status-файлов только STATUS_MOBILE. Не заявлять iOS build без Mac. Использовать существующий worktree на новой ветке от main. Подтверждённая исходная проверка Gradle testDebugUnitTest BUILD SUCCESS (UP-TO-DATE).

## Review Focus

1. URL с userinfo, обратным слешем, пробелом/контролом или иной схемой не уходит ОС — Task1 invalid URL matrix.
2. Server DEMO в REMOTE не открывает фиктивную регистрацию; share явно DEMO — Task1 provenance + Task3 emulator preview.
3. CLOSED/CANCELLED/unknown или отсутствующий registrationUrl не превращаются в активную регистрацию через official/source fallback — Task1 status matrix.
4. Отмена chooser/возврат из browser и повторное действие не сбрасывают detail/catalog/calendar/saved — Task3 emulator smoke; iOS manual checklist.
5. Отсутствие обработчика или iPad share presentation не приводит к молчаливому успеху/crash — Task2 platform error paths/popover anchor, Task3 review + Mac checklist.

## Task 1: политика ссылок и payload

**Files:** commonMain `domain/ExternalLinks.kt`; commonTest `domain/ExternalLinksTest.kt`.
**Interfaces:** `SafeHttpsUrl.parse(raw: String?): SafeHttpsUrl?`, `EventLinkPolicy` с registrationState/url, officialUrl/shareUrl/isDemo; `eventLinkPolicy(detail: HackathonDetail, bundled: Boolean=false)`; `shareEvent(title: String, demoNotice: String): SharedEvent?` → subject/text.

- [x] Написать тесты normal path/query/fragment; malformed/HTTP/userinfo/controls; OPEN/CLOSED/CANCELLED/ENDED/UPCOMING/UNSUPPORTED; no-link; official/source fallback; bundled/server DEMO; real/DEMO payload. Увидеть RED отсутствующих API.
- [x] Реализовать чистые функции без сетевых вызовов; повторить `:shared:testDebugUnitTest --tests '*ExternalLinksTest' --offline --console=plain`, PASS.

## Task 2: подключение UI и платформ

**Files:** commonMain `platform/ExternalActions.kt`, androidMain/iosMain `platform/ExternalActions.*.kt`; commonMain `App.kt`, `ui/DetailScreen.kt`, `composeResources/values/strings.xml`.
**Interfaces:** `ExternalActions.openWebsite(url: SafeHttpsUrl, result: (Boolean)->Unit)`, `shareEvent(event: SharedEvent, result: (Boolean)->Unit)`; composable `rememberExternalActions()` expect/actual. App передаёт callbacks в текущий DetailScreen; policy единая для enabled-state и действия. False означает невозможность открытия, true — запуск/показ, не отправку.

- [x] Зафиксировать RED текущей заглушки в emulator smoke (нет browser/chooser).
- [x] Реализовать Android ACTION_VIEW+BROWSABLE / ACTION_SEND text/plain+chooser, ActivityNotFound/SecurityException→false, контекст host lifecycle.
- [x] Реализовать iOS UIApplication.open completion / UIActivityViewController, проверка presenter, iPad sourceView/sourceRect, отмена без изменения приложения.
- [x] Подключить HTTPS-policy, disabled states, DEMO preview, secondary official link, share payload и сообщения ошибки в прежнем стиле.
- [x] Build/test/lint/release gate PASS; iOS native compile на Windows не объявлять выполненным.

## Task 3: приёмка и передача

**Files:** `mobile/scripts/android_external_actions_smoke.py`, `mobile/artifacts/m2-external-actions/`, mobile README/TOOLCHAIN, STATUS_MOBILE.

- [x] Эмулятор: реальный ACTION_VIEW URL; chooser с нужным payload (ничего никому не отправлять); back/cancel и scroll; DEMO preview; closed/cancelled/no-link; offline saved detail; прежние guest bytes/settings восстановлены. Только тестовый origin для synthetic fixture сервера, не выдавать его за реальные события B1.
- [x] `:androidApp:assembleDebug :shared:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain`; Starter validator + git diff --check. Скриншоты и SHA APK, без изменения старых artifacts.
- [x] Одно итоговое review всей новой ветки, исправить существенные находки в одном проходе с RED→GREEN, без повторного review.
- [x] Записать факты/ограничения/iOS checklist; готовый PR-текст и точные команды stage/commit/push пользователю. Остановиться на этом этапе M2.
