# Profile Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans, sequential, one final reviewer; no agent commits/pushes.

**Goal:** Edit current Profile through Starter PATCH /me and preserve M1 style.
**Architecture:** Credential-owning client, process-owned editor, shared Compose route.
**Tech Stack:** Existing Kotlin/Compose/Ktor/serialization/coroutines, no additions.
**Spec:** `docs/superpowers/specs/2026-11-01-mobile-profile-edit-design.md`.

## Global Constraints

- Starter unchanged; message18/PR22 remain draft, no pending/email/reset implementation.
- Six editable fields only; partial patch, nullable clear, interests complete list.
- One session owner, no secret exposure, draft memory only; logout/switch discards it.
- Preserve design/fonts/artwork; no marks/reminders, dependencies/backend/API edits.
- User commits/pushes; iOS native remains unverified on Windows.

## Review Focus

1. Concurrent foreground/profile refresh and an old draft: omit unchanged fields.
2. Logout/account switch while PATCH awaits: no stale draft or wrong-profile publish.
3. Lost response/invalid body: no success claim, safe retry/reload with old draft.
4. Metadata missing/stale: never replace interests with accidental empty list.
5. Narrow screen/keyboard/large text and cancel dirty: accessible real controls.

### Task1: PATCH and process-owned editor

Files new `ui/profile/{ProfileDraft,ProfileEditStore}.kt`; modify AuthSessionClient,
CatalogRuntime/AppState; tests new `data/auth/ProfileClientTest.kt`, `ui/ProfileEditStoreTest.kt`.

- [x] Write tests for changed-only body/null/empty interests, exact method/header/owner,
  200/401/400/network/wrong owner; validation, duplicate save, dirty/cancel, logout/late save.
- [x] Run focused tests RED, implement APIs and store; focused/full shared GREEN.
- [x] Produce draft+errors+busy+saved state, open/change/save/close/reload methods.

### Task2: Editor UI and profile entry

Files new `ui/profile/ProfileEditScreen.kt`; modify App, AccountProfileScreen,
AuthScreen shared field visibility, strings. Reuse metadata from CatalogStore.

- [x] Add UI entry, text/avatar/interest controls, retry metadata, no-change disabled save,
  confirm discard, process-owned busy/error/success, account invalidation navigation.
- [x] Android assemble/shared tests/lint/release gate PASS; capture real UI in Task3.

### Task3: Native acceptance, review and handoff

- [ ] Real local B2/disposable DB Android: set/clear six fields, cancel, restart,
  offline error/retry, session invalidation; no saved/import changes.
- [ ] Capture normal/360dp/font130, one final independent review, important regressions
  RED→GREEN/full suite. iOS native remains open.
- [x] Update STATUS_MOBILE, profile usage, draft-review artifact, verification/screenshots,
  PR body and commit/push commands; leave changes unstaged.

Execution2026-11-02: Tasks1-2 complete,228tests/17new, build/lint/release PASS.
One final reviewer0Critical/2Important/0Minor; both fixed RED→GREEN. Native country
input and expired-notice regressions also fixed; full suite GREEN after last fix.
Task3 native boxes remain open: prior UI APK4/6PASS, final APK preflight blocked
by missing phone service on API37 and phone/SystemUI startup ANR on separate API36 AVD. Do not
carry older PASS to final APK. Repeat all6groups/360dp/font130 on healthy Android;
iOS native requires Mac/iPhone. No next M3 features until separately authorized.
