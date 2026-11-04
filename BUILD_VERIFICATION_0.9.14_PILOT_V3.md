# OpenKinGuard 0.9.14 Pilot V3 构建校验

日期：2026-11-04。

- 源码版本：`0.9.14-network-menu-lite` / `versionCode=49`。
- 构建目标：`:core:engine:test :security:protocol:test :app:testDebugUnitTest :app:assemblePilotV3`，Gradle 9.5.0，离线依赖，Android SDK 37，JDK 17。
- 结果：`BUILD SUCCESSFUL`；JVM 单元测试 172 项通过，0 失败、0 错误。
- APK 包名：`io.github.tigaotg.openkinguard.debug.pilotv3`；最低 Android SDK 29（Android 10）；目标 SDK 36。
- APK v2 签名已通过 `apksigner verify --verbose --print-certs`。
- Pilot V3 证书 SHA-256：`d100a7eee2e1765aa7e9292304f1d7e536569cd8f713218f5279f8c207bba624`（保持原证书）。
- 本次仅修改设置导航、布局、说明、单元测试和版本号，没有更改通信协议与个人身份存储逻辑。
- 尚未运行两台 Android 真机 UI 及覆盖升级测试；用户的移动网络 SOS、报平安成功反馈属于修改前版本，不能代表新版已通过真机验证。

编译所用的 Pilot V3 签名文件和密码仅保存在独立安全备份中，不打包进对外源码 ZIP。
