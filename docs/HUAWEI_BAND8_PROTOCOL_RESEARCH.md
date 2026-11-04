# Huawei Band 8: independent transport research (2026-10-31)

## Scope and confirmed facts

- The user's wearable is **HUAWEI Band 8**, as identified by the Huawei Health screenshot.
- OpenKinGuard 0.7.0 successfully received true standard BLE Heart Rate Measurement notifications when the wearer manually enabled the band's heart-rate broadcast.
- That broadcast mode can be exited on the band, so it cannot support reliable unattended vital-sign monitoring on its own.
- Gadgetbridge officially lists Huawei Band 8 as *mostly supported* and provides proprietary Huawei pairing, activity, HR, sleep, and other device-dependent sync functionality.
- The `xiaoyou5602/band-health-sync` Gadgetbridge fork periodically requests **already recorded** Huawei HR history every 1, 3, 5, or 10 minutes. Its author reports actual validation on Band 10, **not Band 8**. This is not on-demand sensor activation, beat-to-beat streaming, or a medical-grade alert.

Primary sources:

- https://gadgetbridge.org/gadgets/wearables/huawei-honor/
- https://gadgetbridge.org/basics/topics/huawei-honor/
- https://github.com/xiaoyou5602/band-health-sync
- https://github.com/xiaoyou5602/band-health-sync/blob/master/docs/GUIDE.md
- https://github.com/xiaoyou5602/band-health-sync/blob/master/docs/PATCHES.md
- https://consumer.huawei.com/en/support/content/en-us15827504/

## Reverse inspection of the user-supplied Huawei Health APK

The uploaded Huawei Health 17.0.8.300 Android package contains multiple DEX files and (among many other packages):

- `com.huawei.hwbtsdk.*` (Bluetooth device/data/transport components)
- `com.huawei.wearmgr.*` (wearable device manager)
- `com.huawei.security.deviceauth.HichainAuthManager`
- `com.huawei.hwbtsdk.btcommon.EncryptionBase`
- `lib/arm64-v8a/libhichain_sdk_jni.so`

This identifies relevant module boundaries; it **does not** demonstrate a separable, redistributable driver. Do not copy Huawei proprietary classes or native binaries into the open-source app. No on-device account keys, pairing state, or historical medical data were accessed.

## 0.7.1 diagnostic milestone

The Health BLE section now enumerates GATT **metadata only** (service UUIDs, characteristic UUIDs, and property flags) following ordinary, user-initiated Android GATT discovery, and offers a clipboard export. It does not initiate vendor authentication, read characteristic values, write vendor commands, or trigger bonding or factory reset. It is a safe step toward identifying Band 8 transport endpoints but **not** a functioning Band 8 private-protocol driver.

The diagnostic keeps the former standard BLE HR mode fully intact. Any service enumeration may still fail if another companion app owns the connection, the band restricts access, or Android prevents connecting.

## Required next gates for a functional driver

1. Gather *redacted* Band 8 GATT service/characteristic inventory from the owner's hardware. Exclude device MAC address, Huawei cloud cookies, authentication tokens, keys, and actual health readings.
2. Define an independently implemented, authenticated Huawei session with explicit, consent-based pairing and no factory reset. Use public protocol descriptions for behavior, not copied proprietary code.
3. Determine whether the actual Band 8 firmware supports retrieving recorded HR, step count, sleep and SpO2 independently; preserve timestamps, origin and freshness.
4. Move the validated connection into a background foreground-service/companion-device lifecycle. Never promise unattended operation until Android 10 and 17 physical-device tests pass including reboot, airplane mode, cellular switches and battery optimization.
5. Keep measurement freshness and connection availability separate from health thresholds. Never replace stale or missing values with 0 or infer an absence of danger.
6. Test against an unpaired *optional spare device* or an explicitly user-approved migration plan; never silently unpair Huawei Health or reset the band.

### Licensing

Gadgetbridge and the fork are AGPLv3. OpenKinGuard's chosen app license is GPLv3. Borrow functionality as protocol research only unless the project owner explicitly revisits code reuse, license scope, and source obligations. This milestone contains no copied third-party protocol implementation.
