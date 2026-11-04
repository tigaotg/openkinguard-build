# S0 验收与剩余工作

日期：2026-10-30（北京时间）。依据统一需求总册 `2026-10-29`，未变更129项原文。

## 本次范围（原需求编号）

| 原需求 | S0 实施 / 验证方式 | 状态 |
|---|---|---|
| #017 Android兼容 | minSdk 29，目标 Android 10+；无 GMS SDK 强制依赖 | APK已编译，真机待测 |
| #018 / #045 完全开源及许可证 | 本地 GPLv3 声明、第三方许可清单 | 初版完成；发布前须附完整许可证 |
| #020 统一客户端 | 单 APK，无固定角色开关；模拟自己SOS及收到他人SOS | 本机模拟完成 |
| #056 本地化 | 中文、英文资源及国际化界面 | 首批 S0 资源完成，后续扩展 |
| #068 分阶段闭环 | S0 限实验按钮；不直接触达网络和真实应急服务 | 完成 |
| #069 技术栈 | Kotlin / Compose / Gradle | 工程已建立 |
| #072 网络候选 | WebRTC 和 libp2p 仅列候选，须真机比较 | 研究中，未选定 |
| #038 消息认证 | 纯模型和认证边界注明；尚未实现身份密钥/验签/抗重放 | **不能用于真实通信** |
| #090 双时钟 | 显式 `ClockReading`、样本单调时钟、事件时间字段 | 基础模型完成；时钟可信度仍待补 |
| #097 逻辑消息ID | `EventId`、`MessageId`、`AttemptId`、`MessageEnvelope` | 结构完成；真实持久去重/ACK待S1-S2 |
| #077 构建和签名 | Wrapper + 自动测试/Debug APK 流程 | APK已本地构建及调试签名，CI与正式签名待测 |
| #080 诊断日志 | `DiagnosticLog` 脱敏契约 | 契约完成；真实存储待后续 |
| #073 社区协作 | README、CONTRIBUTING | 本地草案完成，**尚未公开 GitHub 仓库** |
| #074 / #075 名称、ID | 英文名暂定；实验包名 `.debug` | 按用户基准配置，正式发布前核验 |

## 安全测试的判断原则

- A帮助B 与 B帮助A 是两条独立、不可互相授权的有向关系。
- A帮助B/C/D 时 B的心率、GPS、接手记录不应从 C/D 关系读取。
- SOS独立于 GPS、BLE、网络；没有外部授权联系人的时候只能明确本地提示。
- 每个事件只有一个 `EventId`，重复输入不应重新发送通知意图。
- 多人接手只有被求助人设备审核的第一位有效帮助者胜出。
- 正常心率、断网、采集失败均不能自动结案。
- 网络动作没有确认就不能显示为“已通知他人”；S0 没有真实通知。

## 仍需明确的验收阻塞

1. 最低 Android 10 与主线最新 SDK 的原厂设备安装测试。
2. 两台手机的真实身份、扫码双向授权、网络签名防重放、消息持久化 —— S1。
3. Android后台、SMS/电话权限、P2P穿透及网络切换的实测 —— S0 研究继续，功能在 S2。
4. 必须将真实 `MessageEnvelope` 的安全序列化与认证绑定到设备密钥；当前结构**并不等于安全协议已完成**。
5. 高风险报警阈值与医疗误报风险不通过单元测试就获得医学有效性。

## 构建执行记录

- 使用 Gradle 9.5.0（官方分发包 SHA-256：`553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`），Eclipse Temurin JDK 17。
- 执行 `:core:engine:test`：**16项通过、0失败、0跳过**；见 `core/engine/build/test-results`（构建产物不包含在源码归档中）。
- 执行 `:adapters:mock:test`：**5项通过、0失败、0跳过**；模拟网络始终不发送、错误设备回执拒绝、重复逻辑消息不重新入队。
- `:adapters:mock:compileKotlin` 成功。
- **Android 调试 APK 已成功生成**：用户于2026-10-30明确表示已理解并接受 Android SDK License，并授权在本次环境中代为确认；通过官方 SDK Manager 交互提示单次输入 `y`，安装 Platform 37.0、Build Tools 36.0.0，Gradle 命令 `:app:assembleDebug` 返回 `BUILD SUCCESSFUL in 1m 4s`（44项任务）。
- `aapt` 核验 APK：包名 `io.github.tigaotg.openkinguard.debug`，版本 `0.0.1-s0`，minSdk 29 / targetSdk 36 / compileSdk 37，入口 `MainActivity`。
- `apksigner verify` 通过：APK Signature Scheme v2 已签名，签名主体为 Android Debug；**不是正式分发证书**。
- `aapt dump permissions` 仅列出 AndroidX 自有的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；未申请实际短信、电话、定位、联网或蓝牙等权限。
- **尚无真机安装、交互、通知送达或后台稳定性验收结果。** GitHub CI 尚未实际运行。
- `git init -b main` 已完成；本地尚未创建远程 GitHub 仓库，尚未签署正式版 APK。

上述测试和 APK 构建证明**工程在本次构建环境中可以编译并通过单元测试**，但不代表真实设备兼容性或真正的安全监护能力。

## 用户补充验收（2026-10-30，后续记录）

用户随后明确报告：原始 S0 APK 已分别在 **Android 10 和 Android 17** 两台真机安装并实际使用，首页、本地 SOS、假想帮助关系、模拟收到远端 SOS 均通过，未发现上述测试场景中的 Bug。具体机型、系统版本细号、日志与72小时后台稳定性报告未提供，故本记录标记为“用户报告通过 S0 指定交互场景”，不将其扩展为真实远端通信或长时间监护验收。
