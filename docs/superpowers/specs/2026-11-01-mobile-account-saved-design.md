# M3 block 3 — account saved and confirmed guest copy

User requested the next block after merged PR20. Chosen explicitly: mutations
require server availability; offline is read-only cached viewing. No offline
outbox. Existing Starter, ROADMAP and approved M1 system remain binding.

## Scope and approach

GET /me/saved, PUT/DELETE /me/saved/{id} share the one process-owned auth client.
No token is exposed to UI/repositories. Every saved call also checks the expected
user UUID before sending: stale work never targets the next account. Protected401
clears the credential and prompts re-login. Public detail/catalogue remain separate.

Alternatives considered: server-confirmed changes + offline cache (chosen), or
offline mutation outbox (more conflict/retry policy, excluded). Manual DI and
atomic snapshot files continue; no DB/library/API/backend change is required.

## Identity and cache

Expose nonsecret LocalSessionIdentity(userId,expiresAt) after validation of secure
storage, including a network-failed restore. It is not server-confirmed login and
does not permit login replacement or claim a fresh profile. Its purpose is to
identify the correct read-only cache while offline after process restart. Expiry,
401, storage errors and logout clear identity. AuthStore observes client state so
saved401 reaches auth UI even outside an auth-screen operation.

One private cache file per normalized API URL, with version/source/owner UUID,
capture timestamp, full SavedGuestEvent snapshots and confirmed import keys.
Different owner/source documents are never displayed. No profile/password/token
in this file. Native app-private, excluded-from-backup storage. Logout/account
switch clears the file and in-memory private state; clear failure blocks saved
access and offers retry, never claims cache erasure. A mutex orders writes/clear
and an owner generation rejects late results. The existing guest file is untouched.

Refresh stages all pages and public details before replacing a complete cache;
partial/malformed/changing pagination keeps the previous copy plus an error.
An event detail must match ID/isDemo. No fabricated details or silent DEMO fallback.
PUT success followed by local-cache failure is explicitly a failed synchronization,
not proof the server ignored the mutation; retry is idempotent. Private snapshots
are shown with cache freshness; account saved/calendar reflect account copies.

## Guest copy

An explicit action shows count and confirmation for the current account. Capture
the guest list at confirmation. Check public ID/classification, PUT each event,
record each confirmed success before continuing. Partial failure reports progress
and permits explicit retry; idempotent PUT makes a lost response safe to repeat.
Never delete guest bytes, even after success. No auto-import, no cross-source import,
no silent copy into another account. Each account requires its own confirmation.

## UI/lifecycle

Keep four tabs/fonts/palette/artwork. Account saved list/calendar/profile are
distinct from guest saved. Unverified local identity shows cache plus a session
warning and retry/logout; no invented real profile. Bookmark writes are disabled
while startup ownership is uncertain or session unverified. Logout/owner changes
discard private detail/nav state; public catalogue remains available.

No profile editing, application mark, reminders, deletion/reset/verification or
release/deployment in this block. User commits/pushes. iOS source adapters may be
prepared on Windows; native compile/runtime/Keychain acceptance remains open.

## Verification

MockEngine: saved URLs/headers/pagination/204/401/expected owner/no retry/errors.
Unit tests: cache ownership/atomic failure, offline restart identity, partial import
and repeat, guest preservation, logout/switch vs delayed writes/results, fresh cache
replacement/removal/changed or cancelled events. Full shared tests/Android build/
lint/release gate + Starter validator. Live isolated B2 with two accounts, guest
copy, partial error where feasible, offline read-only restart, logout cleanup and
Android screenshots. One final independent read-only review; no agent commit/push.
