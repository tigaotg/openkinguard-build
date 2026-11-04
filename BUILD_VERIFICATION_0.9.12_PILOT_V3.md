# OpenKinGuard 0.9.12 Pilot V3 编译验证

日期：2026-11-04

- 结果：`:app:assemblePilotV3` 成功（Gradle 9.5 / AGP 9.3.1 / Android SDK 37）。
- `:core:engine:test` 和 `:app:testDebugUnitTest` 成功，包含新增的离线设备归类与网络三级菜单回归测试。
- `versionCode=47`；APK 内版本名 `0.9.12-menu-offline-home-pilot-v3`。
- 最低 Android 10（API 29）；编译目标 SDK 37，target SDK 36。
- 包名 `io.github.tigaotg.openkinguard.debug.pilotv3`。
- `apksigner verify` 成功，APK Signature Scheme v2 有效；新旧 Pilot V3 证书 SHA-256 一致：
  `d100a7eee2e1765aa7e9292304f1d7e536569cd8f713218f5279f8c207bba624`。
- 新 APK SHA-256：`bdff6b2c0ad4fb62375e6ab8f5189ff7eb008763b665635ef098b91c854e1112`。

本次交付源码包**不包含私人签名证书、签名密码、Gradle 缓存或编译中间产物**。用户应保存此前单独交付的 Pilot V3 签名备份，后续升级沿用相同证书。

尚未通过 Android 10/17 双机实测，特别是签名心跳的真实在线/离线变化、页面触控、滚动、色彩对比与公网 SOS 送达。
