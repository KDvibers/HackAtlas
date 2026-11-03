# M3 profile editing

Authorized 2026-11-01: profile editing, preserved M1 design, sequential execution
and one final review; user commits/pushes. Exclude application marks/reminders.

Current Starter remains canonical. message(18).txt is an unapproved backend draft
already present in PR22; it does not approve emailVerified/pending/reset endpoints
or applying its JSON patch. Record a mobile review separately, do not implement it.

## Implementation decisions

One shared Compose editor for authenticated REMOTE users; manual DI and current
native hosts. Reuse existing field/button/icon tokens. Alternative all-fields PUT/
patch would overwrite unrelated edits; use changed fields only in PATCH /me.
No new dependencies, API/backend/assets/fonts changes.

Edit displayName(1..60), city/roleLabel(nullable,100), countryCode(nullable, uppercase
2ASCII letters), avatarKey(HOODED/INITIALS), interests(up to20unique metadata IDs).
Empty nullable text means explicit null; unchanged values omitted. Interests sent
only if set differs; absent metadata never erases existing interests, retry loads
available choices. Existing IDs not in metadata remain visible/removable.
Email, roles/admin permissions, counters are read-only and never in patch.

Auth client PATCH accepts expected owner, allowlisted body and contract validation;
200 Profile identity must match before publishing. No retries/optimistic success;
401 clears session, other failures fixed safe messages. After ambiguous network
failure the server may have saved it: show this and allow reload/retry.

Editor stores process-memory draft, baseline and owner; no rememberSaveable/disk.
Rotation keeps it, discard requires confirmation when dirty. Save is guarded against
double-submit and continues under process ownership. UI close does not roll back a
server request. Logout/owner invalidation clears draft and rejects late UI result.
Late server profile publish is serialized with logout by existing credential mutex.
Foreground GET me does not overwrite an in-progress draft: changed-only PATCH avoids
overwriting unrelated latest fields. No offline write queue. Confirm success only
after real200 and valid ProfileDto. Public catalogue and saved ownership unchanged.

## Verification

MockEngine exact PATCH/header/absent-null/allowlist,401/400/network/malformed owner,
expected-user rejection; form validation/diff, duplicates, cancel, failed retry,
session switch/logout vs delayed save. Full shared/Android build/lint/release gate,
Starter fixtures, real disposable B2 Android profile edit/clear/cancel/restart/error
and preserved saved. Screenshots normal/360dp/font130. One independent final review.
iOS shared source only on Windows; Mac/Xcode/Keychain/UI acceptance remains open.
