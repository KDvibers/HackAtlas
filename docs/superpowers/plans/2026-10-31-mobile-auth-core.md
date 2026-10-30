# M3 auth core implementation plan

> REQUIRED: superpowers:executing-plans, один исполнитель + одно итоговое read-only review. Commit/push пользовательские.

**Goal:** B2 verification, source-scoped native credential store, isolated Ktor auth client and MockEngine regression tests.
**Architecture:** SecureTokenStore + AuthSessionClient with explicit endpoints/state/errors, platform engine/store factories, no App integration.
**Tech stack:** existing Kotlin/Compose/Ktor, AndroidKeyStore AES-GCM, iOS Security framework, no new production dependency.
**Spec:** ../specs/2026-10-31-mobile-auth-core-design.md; api/openapi.yaml + api/README.md.

## Constraints / review focus

1. Token/password must not escape via redirect/public client/log/error/toString; test requests and transport failures.
2. Cancellation, concurrent login/logout/401 and store failure must not revive a previous account; mutex and non-cancellable local commits, race tests.
3. Corrupt/inaccessible native storage must report Storage, with no plaintext or missing-session fallback; Android instrumentation, iOS source/checklist.
4. Expiry and revoked token => sign-in required, failed login401 does not erase existing credentials; MockEngine tests.
5. Offline logout clears local storage but never claims confirmed server revocation; MockEngine + cancellation tests.

## Task 1: contract + session client

Files commonMain data/auth/{AuthModels,SecureTokenStore,AuthSessionClient}.kt, commonTest data/auth/AuthSessionClientTest.kt; network AuthHttpEngine expect/actual.
- [x] Write tests and observe RED: login/register wire contract, bearer me/logout, restore, expiry/401, malformed response, HTTP429/503, redirects, cancellation, store failure, serialization/redaction, source isolation, races.
- [x] Implement explicit client/state/errors/local commits; GREEN + full existing suite.

## Task 2: platform stores

Files Android/ios data/auth SecureTokenStore adapters. Public AndroidSecureTokenStore(context, source), IosSecureTokenStore(source); common createAuthSessionClient(source,store).
- [x] Add Android instrumentation for actual Keystore encryption/restore/update/clear, corrupt ciphertext, source separation. Observe RED before adapter exists.
- [x] Implement Android AES-GCM atomic no-backup store and iOS CF/SecItem adapter; compile Android and exercise device tests; Mac checklist remains open.

## Task 3: acceptance

- [x] B2 Maven verify with actual HTTP Testcontainers + validate B2 exported responses; never print issued secrets, do not touch existing dev DB.
- [x] Android unit suite, build/lint/release gate and diff check. One fresh final reviewer; fix substantial findings with RED→GREEN, no repeat reviewer.
- [x] STATUS_MOBILE, mobile README/TOOLCHAIN, reports and exact commit/push/PR commands; stop before screens/sync.
