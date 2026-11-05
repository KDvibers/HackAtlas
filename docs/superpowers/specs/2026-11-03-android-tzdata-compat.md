# Android EventTime tzdata compatibility

User scope: catalogue must open with old Android tzdata; API unchanged, sequential
execution and one final review; user commits/pushes. No new M3 features.

Baseline f72e248, API0.1.1. Native RED already observed in API34/tzdata2023a: valid
AI fixture05:00Z maps to11:00+0600 instead of server10:00+0500 and rejects the page.
The source equality check is unchanged at baseline. Existing invalid-MINUTE test
must keep passing; DATE must not acquire a time/countdown.

Only EventTime.MINUTE resolution gets a platform seam. Android chooses the newer
rule version between OS Android ICU and bundled ThreeTenBP1.7.5 (TZDB2026egtz,
515351bytes, BSD3). Normalize global-tz suffix for comparison. Equal/unknown
comparable versions prefer OS; unavailable OS zone may use known bundled rules.
No global provider replacement, clock change, network tzdb download, or payload
fallback. Unknown zones/invalid date/time/instant and real mismatches remain errors.
iOS actual delegates to existing kotlinx.datetime behavior; no native-build claim.

Alternatives: keeping OS-only reproduces defect; removing tuple consistency would
accept false exact times; TickTock is archived/global java.time replacement.
ThreeTenBP is scoped to Android API EventTime, not a replacement of the app stack.
Updates remain release work: if both rule sources are older than a future change,
strict validation still needs a rules update. Snapshot display in device-local
time and Calendar.today keep their existing OS behavior.

Proof: resource/selector/strict tuple tests RED->GREEN; full shared tests, Android
debug build/lint/release gate; original and fixed APK comparison on old API34,
fixed APK on current API36, catalogue/detail/exact time/guest offline snapshot.
No original AVD wipe; read-only loopback HTTP fixtures and task-only AVDs, no backend/DB. Preserve design.
