# M3 block 2 — auth UI and host integration

User authorized this block after the proposed sequence on 2026-11-01. The existing
Starter, ROADMAP, M1 visual approval and auth-core design remain authoritative.

## Goal and boundary

Make register/login/profile/restore/logout usable from Android/iOS, with a public
guest catalogue. Preserve M1 tokens, fonts, artwork and tab navigation. No saved
sync, guest import, profile editing, reminders, account deletion, verification or
password reset. No backend/API changes, dependencies, commit or push.

## Approach

Use the established manual DI: one process-owned AuthSessionClient and AuthStore
per fixed REMOTE source. Shared Compose forms; native hosts supply the existing
Keystore/Keychain stores. Alternatives (screen-owned clients, new DI/navigation
framework) add session races or unnecessary migration; the existing runtime is
the appropriate owner. No duplicate implementation/design approval is needed:
the user explicitly requested execution of this previously proposed block.

AuthStore holds only session state and operation/error/notice, never credentials.
Form values use ephemeral remember, never rememberSaveable, and clear on submit,
mode change, leaving the form or backgrounding. Login password length 1..256;
registration 12..256; displayName trimmed 1..60; email trimmed, plausible address,
maximum 254. Password is never trimmed. Server remains final validator.

Only signed-out/expired states offer forms; uncertain restore or storage failure
offers retry and explicit logout. Operations cannot be double-submitted. Failed
restore never silently becomes signed out. Errors are fixed user copy (including
400/401/403/409/429/network/storage), no raw server body. Logout hides the private
profile immediately, reports local-only revocation honestly and blocks a success
claim when storage clear fails. Foreground and opening profile recheck the session.

Profile uses actual ProfileDto fields, server stats and avatar choice, with no
invented numbers or demo labels. Guest bookmarks/calendar remain device-local,
explicitly labelled, unchanged through login/logout/account changes. No account
snapshot/cache is persisted; account profile lives only in memory and disappears
on logout/401/storage failure. Single auth owner per source is mandatory.

## Visual implementation

Forms use a 20dp screen margin, 14dp dark fields with violet outline, readable
Inter labels, Press Start 2P title, existing 52dp NeonButton and restrained cyan.
Scroll/IME insets preserve usability on narrow screens and large text. Use real
Compose text fields, password masking, show/hide, keyboard actions, accessible
labels, errors, progress and disabled states. Existing DEMO profile remains DEMO
and cannot log into an unspecified API.

## Acceptance

MockEngine tests cover validation, duplicate submit, typed errors, restore,
foreground, 401, offline/local-only logout, storage failure and account switch.
Run full shared suite, assembleDebug, lint and release-no-demo gate. Run Android
live B2 with disposable accounts, process restart, guest preservation and offline
logout; keep credentials out of artifacts. Capture forms/profile plus narrow and
large-text screenshots. One final independent review. Mac/iOS compile/runtime and
Keychain acceptance remain explicitly open on Windows.
