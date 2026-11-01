# Mobile auth UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Execute sequentially, one fresh final review, user commits/pushes.

**Goal:** Make the existing opaque-session client usable from Android/iOS.
**Architecture:** One process-owned AuthStore/client per REMOTE source, manual DI,
shared Compose forms and native secure-store factories.
**Tech Stack:** Existing Kotlin/Compose/Ktor/coroutines; no added dependencies.
**Spec:** `docs/superpowers/specs/2026-11-01-mobile-auth-ui-design.md`.

## Global Constraints

- Starter API unchanged, no JWT/refresh; no backend/infra/design asset changes.
- Preserve approved M1 visual system. Catalogue/guest bookmarks remain public/local.
- No saved sync/import/profile editing/reminders. No commit/push/PR by agent.
- No credentials in saved UI state/logs/artifacts. iOS acceptance requires Mac.

## Review Focus

1. Background/recreation while submitting: no password Bundle; one owner; test delayed submit.
2. Offline restore/storage failure: no fake signed-out success; retry and logout available.
3. Logout/account change: no previous private profile; guest data stays explicitly local.
4. Expired/revoked session: foreground/profile check produces repeat-login; no refresh.
5. Narrow/large-font keyboard UI: fields/actions reachable; validate screenshots and scrolling.

### Task 1: Process-owned auth state and validation

Files: new `mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/auth/{AuthStore,AuthForm}.kt`
and `commonTest/.../ui/auth/AuthStoreTest.kt`.
Consumes: AuthSessionClient register/login/restore/logout and state.
Produces: AuthStore.state, start(), refresh(), submit(mode,email,password,name),
logout(), dismissFeedback(); AuthFormMode and validateAuthForm().

- [x] Write tests first: validation/password preservation, restore not a login wall,
  double submit one request, errors 401/403/409/429/network/storage, foreground
  recheck, local-only logout, clear failure, account switch, canceled UI observer.
- [x] Run `:shared:testDebugUnitTest --tests '*AuthStoreTest*'`; expected RED absent APIs.
- [x] Implement a UI-owner operation guard, typed feedback and state, no credentials retained.
- [x] Run same command; expected PASS. Leave changes for user commit.

### Task 2: Forms/profile and native ownership

Files: new `ui/auth/AuthScreen.kt`, `ui/auth/AccountProfileScreen.kt`; modify App.kt,
ProfileScreen.kt, strings.xml, AppState.kt, CatalogRuntime.kt, HackAtlasApplication.kt,
MainActivity.kt, MainViewController.kt and minimal Swift host lifecycle hook.
Consumes Task1 API; runtime exposes optional auth only for REMOTE.

- [x] Add bootstrap tests for independent auth initialization/guest preservation.
- [x] Run focused tests; expected RED missing host integration.
- [x] Wire one native auth owner; add forms/back/mode switching, secure ephemeral
  inputs, background clearing, real profile, explicit local-saved label and logout confirmation.
- [x] Run full shared tests + assembleDebug/lintDebug/verifyReleaseNoDemoFixtures;
  expected PASS. No auth requirement for catalogue or guest storage.

### Task 3: Live acceptance and final review

Files: `mobile/scripts/android_auth_smoke.py`, `mobile/artifacts/m3-auth-ui/`,
`mobile/AUTH_UI.md`, `docs/STATUS_MOBILE.md`.

- [x] Start isolated dev backend/DB as needed; no changes to existing user database.
- [x] Android live B2 register/login, wrong password, restart/restore, logout and
  account switch, offline logout; verify device-local bookmark preservation.
- [x] Capture screenshots normal/narrow/large text; fix observed UI defects.
- [x] One fresh final review of whole diff; important fixes RED→GREEN, full suite PASS.
- [x] Record exact evidence/limits, status, ready PR description and commit/push commands.


Execution note: all Android/common deliverables verified; iOS source lifecycle fix is reviewed against Apple docs, native RED/GREEN and compile/runtime remain open on Windows. Review Minor is explicitly deferred in REVIEW.md. No staging/commit/push; ignored ledger retained for continuity.
