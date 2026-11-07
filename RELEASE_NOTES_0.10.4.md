# OpenKinGuard Android 0.10.4 — pairing and background-readiness hardening

## Changes
- Persist the selected family-relay peer with the in-flight signed pairing session. An interrupted family binding can resume after Android kills the app process.
- Resolve the active relay slot again by the authenticated peer fingerprint before retrying a pairing frame. A slot reorder no longer strands a session.
- Keep the existing signed QR frame format and explicit consent steps unchanged.
- Include battery optimization, background-location and exact-alarm gaps in the readiness report when the related feature is actually in use.
- Add a settings route for background location and exact alarm access; Android permissions are never silently granted.
- Isolate optional home-screen widget refresh errors from the core background listener heartbeat.
- App version: 0.10.4, versionCode 74.

## Validation
- New unit tests cover readiness warnings and persisted family-relay peer validation.
- The CI-produced `FamilyRelayTest` APK is a one-off signed, version-specific fresh-install package (`.familyrelaytest104`); it does not upgrade an older PilotV4 or earlier one-off test installation.
- The in-place PilotV4 signing workflow was not able to run because the repository's `OKG_PILOT_V4_SIGNING_PASSWORD` / `OKG_PILOT_V4_KEYSTORE_B64` secrets are unavailable. The PilotV4 APK is therefore unsigned; do not attempt to install it as an update.
- Real Android 10/17 devices, carrier changes, public IPv6, and cross-network SOS/P2P are not validated by unit tests.
