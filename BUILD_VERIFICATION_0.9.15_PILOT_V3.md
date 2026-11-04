# Pilot V3 构建与验证记录 — 0.9.15

日期：2026-11-04

- Gradle：9.5；JDK：17；Android SDK：37；离线依赖缓存构建。
- 运行命令：`:core:engine:test :security:protocol:test :app:testDebugUnitTest :app:assemblePilotV3 --offline --no-daemon --console=plain --max-workers=4`。
- 结果：`BUILD SUCCESSFUL`；三个模块的测试报告合计 177 个测试案例（含本版新增的局域网单轮授权策略测试）。
- APK 包名：`io.github.tigaotg.openkinguard.debug.pilotv3`。
- 版本：`versionCode=50`；完整 `versionName=0.9.15-one-round-lan-pairing-pilot-v3`。
- `minSdk=29`（Android 10）；`targetSdk=36`。
- `apksigner verify`：APK Signature Scheme v2 验签成功。
- 签名证书 SHA-256：`d100a7eee2e1765aa7e9292304f1d7e536569cd8f713218f5279f8c207bba624`，与此前 Pilot V3 一致。
- 源码归档不包含 `private-signing/`、本地编译缓存、APK 或任何密码。

限制：自动化测试和验签不等于真机完整验证；Android 10 和 Android 17 之间的局域网自动绑定、取消/重试以及 SOS 演练仍需用户现场测试。新增远程首次绑定尚未开发。
