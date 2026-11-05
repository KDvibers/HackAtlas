# M3 Application Marks Implementation Plan

> Use superpowers:executing-plans inline and sequentially, TDD per task,
> one fresh read-only final review. User commits/pushes/creates PR.

**Goal:** Explicit self-reported application marks in event detail, server-confirmed
mutations and private cached membership after restart offline.
**Architecture:** Extend existing owned AuthSessionClient; separate applications
cache/store sharing atomic platform storage with a closed cache-kind discriminator.
Shared Compose status control, existing Android/iOS runtime factories.
**Tech Stack:** Existing KMP/Compose/Ktor/MockEngine, no new dependency.
**Spec:** docs/superpowers/specs/2026-11-05-mobile-application-marks-design.md (approved).
**Base:** c1c371816dba3b7fb0a317a7237ae033f6178cec.

## Global Constraints
API0.1.1/README/backend unchanged; opaque sessions only. No queue/reminders/list
screen/new M3 flows. No optimistic success; guests sign in. Saved/guest files and
schema remain unchanged; newer caches separate, source+user isolation/cleanup.
Preserve approved M1 design. No native iOS build claims on Windows.

## Review Focus
- Incomplete/changed pagination must not turn unknown membership into false absence.
- Confirmed mutation followed by cache/profile failure must not erase confirmed state.
- Logout/switch/source replacement during HTTP or atomic write must not leak old owner.
- Ambiguous transport result requires explicit reconciliation before a next mutation.
- Cache failure/unknown mark JSON cannot display another user's private state.

### Task1: protected application HTTP client
Files: data/auth/ApplicationMarks.kt; AuthSessionClient.kt;
commonTest/data/auth/ApplicationClientTest.kt.
Interfaces: listApplications(expectedUserId,page=0,size=50):ApplicationPage;
markApplied(expectedUserId,id):ApplicationMark; removeApplication(expectedUserId,id):Unit.
DTO: hackathonId,status,selfReported,markedAt; page fields exactly canonical schema.
- [x] Write MockEngine tests, observe RED missing methods/types.
- [x] Implement via same session mutex/ownedSession/requireProtected; validate UUID,
  APPLIED,true,Instant,requested id/page/size,totalPages/hasNext/overflow/duplicates.
- [x] Tests: GET auth/query, PUT200/idempotent returned time, DELETE204, invalid mark,
  bad page, wrong owner/no session,401 clears,429 retry-after, cancellation.
- [x] Run :shared:testDebugUnitTest --tests '*ApplicationClientTest' -> PASS.

### Task2: isolated cache and lifecycle store
Files: data/applications/ApplicationCache.kt; ui/applications/ApplicationStore.kt;
data/saved/PrivateCacheKind.kt; AndroidAccountCacheStorage.kt/IosAccountCacheStorage.kt;
commonTest/data/ApplicationCacheTest.kt; ui/ApplicationStoreTest.kt.
Interfaces: ApplicationCache.open(userId),write(doc,current),clear; ApplicationStore
state/refresh/change(id,remove)/retryCleanup; immutable state with mode/owner/marks/
cachedAt/known/writable/pending/error. Existing AccountCacheStorage interface reused.
- [x] Cache tests RED -> GREEN: disk roundtrip/empty known snapshot, bad/source/owner
  document, duplicate/invalid marks, stale writer and cleanup/cancellation.
- [x] Store tests RED -> GREEN: paginated GET only commits complete membership;
  explicit mark/remove confirmed before UI/disk, duplicate guard, uncertain reply
  forces GET before retry, offline/restart cached membership, no false absent.
- [x] Tests for stale HTTP/cache after account switch/logout/401, cleanup failure,
  profile-refresh failure after success, saved/guest independence and pagination drift.
- [x] Focused cache/store tests -> PASS. Ledger any API/interface adjustment.

### Task3: shared detail control + hosts
Files: ui/applications/ApplicationStatus.kt; DetailScreen.kt; AppState.kt;
CatalogRuntime.kt; ../App.kt (root shared App.kt);
android/iOS runtime construction; composeResources/values/strings.xml.
Interfaces: Runtime owns optional ApplicationStore, detail consumes immutable state
and onRefresh/onChange/onSignIn callbacks; no token exposed.
- [x] Wire closed PrivateCacheKind.APPLICATIONS to separate no-backup/protected file,
  default SAVED paths preserved. Factory absent for bundled DEMO.
- [x] Add existing-style detail status: loading/retry/guest sign-in/applied+date/
  remove/offline-copy/storage/error, mutation disabled while pending/unknown/offline.
  Registration/share never call mark methods; refresh server profile after success.
- [x] Add integration/state tests where needed for route/owner/browser independence.
- [x] Full shared tests + assembleDebug + lintDebug + verifyReleaseNoDemoFixtures,
  contract validator -> PASS. Required flags/logs recorded in artifacts.

### Task4: Android acceptance and final handoff
Files: mobile/artifacts/m3-applications/*, mobile/APPLICATION_MARKS.md,
mobile/README.md, docs/STATUS_MOBILE.md; scoped native smoke helper if needed.
- [x] Verify available Docker/backend/AVD; isolated native acceptance when available:
  guest sign-in, mark/repeat/time, remove, restart/offline, network/401/account switch,
  bookmark independence, browser no mark,360dp/font130. Verify installed APK SHA.
- [x] Inspect captured screenshots against unchanged design. Label backend vs fixture
  evidence and record any native platform/environment limitation honestly.
- [x] One fresh read-only final review of all uncommitted source/tests; address
  Important/Critical with regression tests and only justified reruns.
  Result: 0 Critical/Important/Minor. Native acceptance remains a separate gate.
- [x] Update status/plan/reports/PR body/commit-push commands, git diff --check,
  source scope/index/HEAD audit; clean only task-owned resources. No agent commit.

Native acceptance:10/10PASS, API36/hostNVIDIA/Vulkan-off, same frozen APK.4failed attempts retained. Process cleanup done; local AVD/env deletion blocked by automatic policy, retained in ignored storage. No agent commits/push.
