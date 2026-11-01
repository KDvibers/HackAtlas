# Account Saved Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans; sequential implementation, one final reviewer, user commits/pushes.

**Goal:** Server-confirmed saved changes, isolated offline account cache and explicit guest copy.
**Architecture:** Existing AuthSessionClient owns credentials; a process-owned AccountSavedStore stages/cache-commits per source+owner. Atomic native file adapters; Compose reuses current lists/calendar/cards.
**Tech Stack:** Existing Kotlin/Compose/Ktor/serialization/coroutines; no dependencies.
**Spec:** `docs/superpowers/specs/2026-11-01-mobile-account-saved-design.md`.

## Global Constraints

- Starter only, unchanged API/backend/infra; opaque sessions, no refresh.
- User chose server mutations, read-only offline cache; no outbox.
- Guest bytes never deleted; import explicit per account/source, partial progress preserved.
- Cache/private navigation cleared on logout/switch; stale completions never cross owner.
- Preserve M1 visual system. No further M3 features. Native iOS remains unverified here.
- No staging/commit/push/PR by agent; keep unrelated .agents/.interface-design untouched.

## Review Focus

1. Logout/switch during disk write or HTTP: cleared cache cannot be resurrected.
2. Offline cold restore: local identity gives only matching cache, not confirmed login/write access.
3. Partial/ambiguous import: no lost guest data, false completion or silent cross-account retry.
4. Changed/archived/cancelled events and pagination races: no stale active CTA or destructive partial replacement.
5. Storage errors/401 from saved: feedback reaches auth UI; no fallback or private detail after logout.

### Task 1: Authenticated saved gateway and local identity

Modify `data/auth/AuthModels.kt`, `AuthSessionClient.kt`, `ui/auth/AuthStore.kt`.
Test `commonTest/.../data/auth/AccountSavedClientTest.kt`, extend AuthStoreTest.
Produces listSaved(expectedUserId,page,size):CatalogPage, save/unsave(expectedUserId,id):Unit;
LocalSessionIdentity and AuthUiState.localIdentity. Token stays private.

- [x] Write MockEngine tests first for exact header/URL/query,204,401,expiry,owner mismatch,
  malformed page/error/cancellation and local identity during offline restore.
- [x] Run focused tests; expected RED absent methods, then implement and observe GREEN.
- [x] Observe client invalidation in AuthStore without resurrecting profile during logout;
  test external saved401 and all existing auth tests remain green.

### Task 2: Cache, generation-safe coordinator and guest copy

Create `data/saved/AccountCache.kt`, Android/iOS adapters and `ui/saved/AccountSavedStore.kt`.
Native factory consumes fixed CatalogSource.Remote; cache body validates owner/source.
Store consumes AuthStore, AuthSessionClient, public CatalogRepository and guest repo;
exposes state(mode/owner/entries/pending/sync/error/import progress), refresh/change/import/cleanup retry.

- [x] Tests RED: owner/source mismatch, corruption/write/clear failure, delayed write/logout,
  account switch/cancel, all-pages refresh, offline cache, partial import+retry and guest bytes unchanged.
- [x] Implement atomic file read/write/clear, mutex/epoch guarding, staged refresh and durable import progress.
- [x] Focused and full shared suite expected PASS. No offline writes or auto-import.

### Task 3: Android/iOS hosts and Compose integration

Modify AppState/Runtime/native factories, App, Saved/Profile/Calendar/DetailStore and resource strings;
new shared saved status/confirmation surfaces. Reuse current components and pixel typography.

- [x] Add ownership/detail-reset regression tests before wiring; expected RED then GREEN.
- [x] Select account or guest data explicitly, mark offline freshness/read-only, confirm import,
  prevent writes while ownership is uncertain, clear private detail/navigation at owner change.
- [x] Full shared test suite + assembleDebug/lintDebug/verifyReleaseNoDemoFixtures PASS.

### Task 4: Live acceptance and handoff

- [x] Run real local B2 on disposable DB; Android two accounts, saved isolation, explicit guest
  import, guest preservation, server mutations, offline cold cache and logout/401 cleanup.
- [x] Capture normal/narrow/large-text states. One final independent review; important fixes
  covered by regressions and full suite. iOS native acceptance remains open.
- [x] Update STATUS_MOBILE, usage/verification/review artifacts, PR description and commit/push commands.


Handoff: common/Android verified; iOS native source only. Three Important fixes covered by regressions and final211 suite; native main7+review2 groups PASS. No staging/commit/push. Details/limits in mobile/ACCOUNT_SAVED.md and artifacts/m3-account-saved/REVIEW.md.
