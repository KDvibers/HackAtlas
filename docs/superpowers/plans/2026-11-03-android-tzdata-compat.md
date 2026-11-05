# Android tzdata compatibility implementation plan

> Required execution: superpowers:executing-plans, sequential; one final fresh
> reviewer for the whole fix. No agent commit/push.

**Goal:** Open valid catalogue/details on old Android tzdata without dropping
EventTime tuple validation or changing API0.1.1.
**Architecture:** internal expect/actual EventTime resolver; Android freshest OS/
bundled rules, iOS existing native resolver. Keep DTO/domain shape and UI.
**Tech stack:** existing KMP/Compose/Ktor plus Android-only ThreeTenBP1.7.5.
**Spec:** docs/superpowers/specs/2026-11-03-android-tzdata-compat.md.

## Constraints/review focus
API/backend/auth/visuals unchanged. Provider/resource failure must not silently
relax validation. Compare2026egtz vs2026f correctly; same version prefers OS.
Missing/unknown zones, DST gaps/overlaps, seconds/nanoseconds and saved snapshots
must preserve original semantics. No native build/iOS assertions on Windows.

### Task1: RED and platform resolver
- [x] Add Android resource regression; observe missing bundled-data RED.
- [x] Extract internal resolver with old behavior and test OS/bundle selection
  (old/new/equal/global-tz/unknown/missing-zone), observe old-OS selection RED.
- [x] Pin Android-only1.7.5, implement Android newest rules and iOS native actual.
- [x] Test strict false exact MINUTE/DATE, DST gap and both overlap instants.
- [x] Shared suite/debug/lint/release gate GREEN; check packaged data/license.

### Task2: native regression
- [x] Task-owned API34/2023a: original APK RED, fixed APK catalogue/detail and exact
  source times GREEN, bookmark+restart/offline snapshot; OS tzdata unchanged.
- [x] Same fixed APK flow on API36; screenshots and SHA/readback evidence.
- [x] Clean own resources, leave original AVD/user files intact.

### Task3: review and handoff
- [x] One fresh read-only final review; fix Important findings with regressions.
- [x] Repeat only checks justified by fixes; STATUS_MOBILE, dependency rationale/
  license, usage, native reports/screens, PR body and manual commit/push commands.
