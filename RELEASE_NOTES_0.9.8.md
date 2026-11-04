# OpenKinGuard 0.9.8 · 公共节点与 P2P 轻量设置

本次更新以用户上传的 **0.9.7 完整源码** 为基础，保留 0.9.7 的分级网络菜单、就地反馈、经典轻量主题、扫码绑定、历史记录及求助逻辑。`versionCode` 从 42 增至 43。历史安装通道为 `qrPilot`，包名 `io.github.tigaotg.openkinguard.debug.qrpilot`；由于原签名私钥未保存，本次改为另行安装 `newPilot`，不能用其他签名直接覆盖旧应用。

## 新签名独立安装包（用户已选择重新开始）

0.9.7 的原始 `qrPilot` 私钥丢失，无法继续用该证书无损覆盖升级。因此另建 `newPilot` 构建类型：

- 新包名：`io.github.tigaotg.openkinguard.debug.newpilot`
- 应用桌面名称：**OpenKinGuard 新测试版**
- 新签名：`private-signing/openkinguard-new-pilot-v1.p12`（单独备份，不在源码 ZIP 中）
- 签名密码：构建时从环境变量 `OKG_NEW_PILOT_SIGNING_PASSWORD` 获取，严禁写进源码或公开发布文件
- 新包可以与 0.9.7 的 `qrPilot` **同时安装**，不会覆盖或清除旧应用的数据。

**重要**：Android 按包名隔离数据和 Android Keystore 身份。新包不能继承原来已经绑定的家人手机、私人节点审批和历史记录；两台手机需要重新完成安全绑定，OpenWrt 路由器需要再次核对并批准新身份。测试时建议停止旧 APP 的后台监听，但不要卸载旧版，以便保留回退通道。

## 新界面

1. 设置首页单列「连接家人和手机」，扫码与互助关系入口不再与快速开始的功能介绍混在一起。
2. 「本机权限与功能检测」改为「应用所需权限与功能检测」，解释通知、位置、扫码相机、蓝牙附近设备、联网与后台连接各自的用途；保留原先在按钮下方展示本机检查结果的方式。
3. 「公共节点与 P2P」主区分为 **公共信令是否打开/已确认**、**P2P 是否已完成验签直连** 两种状态。节点在线不再被误读为 SOS 已送达。
4. 原重复的独立 STUN/NAT 检测和公共/局域网握手按钮移除。一个「生成完整诊断」收集设备型号、Android API、网络类型、IPv4/IPv6 是否存在、节点状态、UDP 端口、候选数、最后错误和按需 STUN/NAT 结果。报告默认隐藏 STUN 公网 IP 与端口，可手动复制或保存 TXT。
5. 节点 URL 编辑、详细技术状态、隐私开关与更换候选节点折叠到高级信息。
6. 新增轻量桌面组件：单独 SOS、单独报平安、只显示验签在线数量的隐私组件。总计五种。仍使用原生 `RemoteViews`，不增加背景动画，不在桌面直接发送 SOS。

## 隐私与通信限制

- 诊断只在用户点按钮时运行 STUN；不自动上传或向任何公共节点发送报告。
- STUN 会使第三方服务器看到本机连接时的公网映射，公共信令节点可以看到连接时间与公开身份元数据；这里仍只用公共节点交换加密候选，不转发 SOS 正文。
- 文本报告不包含私钥、病历、家人手机号、准确 GPS 和完整身份公钥。使用 IP 地址配置的自定义公共节点可能在节点状态中出现地址，分享前仍需人工检查。
- 手机之间是否真正能传消息需要接收方签名回执；双方 NAT 不兼容时仍可能无法直连。不能把本应用当作可靠的正式急救服务。

## 构建验证和升级

Android 10（荣耀 7X）与 Android 17（Redmi Note 12 Turbo）的真机验证尚未进行。只有用安装在两台手机上的同一 `qrPilot` 包名、证书及更大版本码重新签名，才可无损覆盖升级。不要卸载现有 0.9.7；不能用新造的调试证书冒充升级包。

2026-11-02 已在临时构建环境用官方 Android SDK 37.0、Build-Tools 36.0.0、Temurin JDK 17 运行成功：`compileDebugKotlin`、`compileQrPilotKotlin`、`testDebugUnitTest`、`assembleNewPilot`。共 **29 项单元测试，0 失败**，其中新增的 `PublicP2pDiagnosticsTest` 六项全部通过；原有通讯和签名测试保持通过。新 APK 已通过 `apksigner verify` 与 `zipalign` 检查，最低兼容 API 29（Android 10）。平台编译命令：

```bash
./gradlew :app:testDebugUnitTest :app:compileQrPilotKotlin
```

若缺 `private-signing/openkinguard-qr-pilot-dev.keystore`，不要运行覆盖安装打包。可先恢复原签名文件并校验证书，再运行：

```bash
./gradlew :app:assembleQrPilot
```

随后使用 `apksigner verify --print-certs` 比对新旧 APK 签名；只有完全一致才安装。

当前独立新版的构建命令（需拥有单独保存的 PKCS12 和口令）为：

```bash
export OKG_NEW_PILOT_SIGNING_PASSWORD='自己保存的强密码'
./gradlew :app:assembleNewPilot
unset OKG_NEW_PILOT_SIGNING_PASSWORD
```

新包的签名证书 SHA-256 为：`4b7034a953ccd309cdb74f10b89651497914231f7d15c2d642fd4697541c9c7b`。
