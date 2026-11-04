# OpenKinGuard 0.9.11 Pilot V3 构建与验证记录

日期：2026-11-04

## 新 APK 的身份

- 应用包名：`io.github.tigaotg.openkinguard.debug.pilotv3`
- `versionCode=46`
- `versionName=0.9.11-relay-device-manager-pilot-v3`
- 最低 Android 10（API 29），目标 API 36，编译 API 37（Android 17）
- APK SHA-256：`40efcbfc9141cad874ad4d144f53ce6807c4e9666e3d1b930c7a63016fa3f513`
- APK 文件大小：39,796,957 字节
- 独立 Pilot V3 PKCS12/RSA-3072 签名证书 SHA-256：
  `D100A7EEE2E1765AA7E9292304F1D7E536569CD8F713218F5279F8C207BBA624`
- `apksigner verify`：APK Signature Scheme v2 验证通过。

这个新身份可以与原有 Pilot V2、NewPilot、QR Pilot 等版本并存。
旧版本的 Android Keystore 身份和本地配对、历史记录不会迁移。
不能用新密钥覆盖旧包名的已安装应用。

## 云端编译环境

- Gradle 9.5.0
- Android Gradle Plugin 9.3.1
- Kotlin Gradle Plugin 2.4.21
- Android SDK Platform 37.0
- Android SDK Build-Tools 36.0.0/37.0.0
- Java 17（含系统已有编译器、jlink、jmod 模块）
- `:app:assemblePilotV3`：`BUILD SUCCESSFUL`，46 项任务。

镜像仓库已加进 `settings.gradle.kts` 以便在常见受限网络中解析依赖；
原本的 Google Maven、Maven Central 和 Gradle Plugin Portal 仍保留为回退源。

## 自动测试

- `core:engine`：68 项，通过
- `security:protocol`：67 项，通过
- `adapters:mock`：5 项，通过
- `app:testDebugUnitTest`：34 项，通过
- Android/JVM 测试合计：174 项，0 失败，0 错误。
- 配套 OpenWrt 家庭中继 0.3.2 的 Python 单元测试：27 项，通过。
- 中继 LuCI JavaScript 语法检查：通过。

本记录**不表示**已完成 Android 10/17 手机与实际 OpenWrt 路由器的互操作实测；
SOS、心率和远程通信不得仅凭自动测试就视为可靠紧急保障。

## Windows 上的后续更新编译

1. 完整解压本源码包，不要将私钥提交到公开仓库。
2. 从单独的私钥备份包中取出 `openkinguard-pilot-v3.p12`，放到源码根目录的 `private-signing/`。
3. 使用 Windows PowerShell 运行 `Build-PilotV3-Windows.ps1`。
4. 脚本会安全询问单独保存的签名密码，并执行单元测试及 `assemblePilotV3`。
5. 将这一份 PKCS12 私钥和密码保存好；未来同包名更新必须继续使用它们。

源码归档不包含私钥或密码，签名材料请单独保密备份。
