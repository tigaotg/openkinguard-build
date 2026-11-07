# OpenKinGuard OpenWrt family relay 0.3.7

- Installed version is stored in `plugin-files/usr/lib/openkinguard-relay/VERSION` and displayed on the LuCI plugin page.
- Pending requests are bounded per sender and per recipient. Offline queue delivery respects recipient capacity and resumes when capacity is freed.
- Transient approved enrollment cache entries are bounded to 128; durable UCI authorization remains authoritative.
- Revocation stops the relay before changing the allowlist, invalidates temporary approvals, updates device metadata, and restarts only after the new authorization state is applied. Failures leave the relay stopped or force a fail-closed stop.
- Restore and approval paths roll back newly added UCI authorization if the associated metadata/state write fails.
- Existing UCI configuration, TLS certificates, allowlist entries for other devices, and unrelated firewall rules are preserved.
- Real router installation and mobile-network interoperability remain unverified until tested on the user's own devices.
