# OpenKinGuard 0.9.16 Pilot V3 构建验收

- 构建日期：2026-11-04
- 构建环境：Android SDK 37、Gradle 9.5、JDK 17。
- 命令：:core:engine:test :security:protocol:test :app:testDebugUnitTest :app:assemblePilotV3 --offline --no-daemon --console=plain --max-workers=4
- 状态：BUILD SUCCESSFUL。
- 测试报告合计：180 个案例，覆盖核心状态机、签名协议、现有公共节点编码、新增远程邀请链接格式与过期验证等。
- APK package：io.github.tigaotg.openkinguard.debug.pilotv3
- versionCode：51
- versionName：0.9.16-public-invite-pilot-pilot-v3
- Android minSdk：29（Android 10），targetSdk：36
- APK v2 签名验证成功。
- 证书 SHA-256：d100a7eee2e1765aa7e9292304f1d7e536569cd8f713218f5279f8c207bba624。
- 源码 ZIP 不含签名 keystore、私有密码、Gradle 编译缓存和实际设备历史数据。

限制：没有两台真实手机的互联测试证据，尚不能证明实际公共 Nostr 节点支持自定义收件索引或在移动网络下可靠转发签名邀请。不能将编译通过等同于远程绑定验收通过。
