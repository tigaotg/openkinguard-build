# OpenKinGuard S6 / 0.9.0：Android 健康设备本机预警试验

日期：2026-11-01。源代码版本：`0.9.0-heart-pilot`，`versionCode=35`。

## 实际开发范围

1. 在 Android 无关的 `:core:engine` 新增 `HeartRatePilotEvaluator` 纯 Kotlin 可测试状态机；设备数据必须是本次连接的实时 BLE 特征 `0x2A37` 心率值。普通血氧脉率、历史、未知私有心率不参加。
2. 默认关闭；用户开关独立存本机 `SharedPreferences`，阈值低/高分别默认 50/120 次/分；持续时间默认 3 分钟，可选 1/3/5 分钟。**这些是软件演示参数，绝不代表医学建议。**
3. 必须连续至少两条真实新数据，并且越界保持所选时间；相邻读数间隔超过 90 秒即中断连续计时。单次异常不触发；恢复正常会结束本轮；新一轮须重新达到持续时间。每轮最多通知一次。重复时间、时间倒退、数值无效均拒绝；开关、阈值改变、断连都清空判断。
4. 每 5 秒在前台 UI 检查最近一条读数新鲜度；超过 90 秒显示过期，不把旧值作为实时正常状态。**不能用本机定时器凭空延长时间证明连续异常，必须有持续采样。**
5. 使用独立 Android 高优先级本机实验通知类别；包括「测试通知」按钮，模拟通知内容不会伪装成设备检测结果。通知可能被 Android 13+ 权限、系统后台策略、免打扰或用户关闭类别拦截；界面反馈是否成功提交给 NotificationManager，但不承诺弹窗。
6. 扫描列表支持人工「给设备起备注」和「修改本机备注」，优先于系统别名。备注存 `SharedPreferences`，索引为随机本机盐 + 蓝牙标识的 SHA-256，不存明文地址；厂商使用随机可变 BLE 地址时不能保证下次识别。备注不是身份认证依据，不会改变绑定公钥或健康授权。
7. 维持所有 0.8.0 现存功能：只读 GATT 清单、华为 FE86 私有接口识别、公开厂商/型号读取、标准健康测量解析、AES-GCM 本机历史（另行选择开启）、CSV 手动导出。

## 不可误认为已完成

- **不具有**华为 Band 8 私有协议心率订阅、连续后台 BLE 采集、跌倒检测、医学级异常识别、自动远程求助、自动短信、拨号、已授权家属同步。
- **不具有**两台手机跨运营商 NAT/P2P 的稳定业务数据通道；公网 Nostr 仍只用于地址信令实验，绝不将求救/GPS暗中改发到公共节点。
- 无法保证前台 App 一直在运行、传感器定期上传、系统允许通知，也不保证设备真实精度。即使发生异常也应由用户手动核实并联系正规紧急救援服务。
- `SharedPreferences` 中的心率阈值设置不是医疗隐私测量历史；真实健康记录必须另行开启本机加密历史，默认关闭。

## 构建与签名隔离

0.8 的独立实验包 `...healthpreview` 的私钥不在此源代码中，**禁止**新生成的签名冒充同签名覆盖安装。新包改用 `...healthpilot`，可和旧版并存，但没有旧版数据或关系的迁移。

本机 `private-signing/openkinguard-health-pilot-dev.keystore` 是单独的研发签名文件，不应上传公开 Git 仓库、打进源码 ZIP 或用来签商业版。仅在为此实验包准备好固定测试私钥后运行：

```sh
./gradlew :core:engine:test :security:protocol:test :app:assembleHealthPilot
```

安装前核对包名确为 `io.github.tigaotg.openkinguard.debug.healthpilot`。不要卸载现有已配对的 S1/0.8 版；开启新研发包会创建另一份 Android Keystore 身份/独立配对数据。

## 双机与真实 BLE 测试顺序

- [ ] 在 Android 10 和 Android 17 各装一次 **新包**，旧包仍然存在且能打开。
- [ ] 检查默认实验开关为关；扫描未知设备仍能展示 RSSI、设备类型和厂商广播编号，已配对名称会显示。
- [ ] 把一台可以确认身份的设备命名为「我的手环」，关闭/重开 App 后仍显示备注；清空备注后恢复系统昵称或未命名状态。
- [ ] 断开手机/手环连接，UI 显示「设备未连接」，以前的测量不能继续当实时值。
- [ ] 在取得用户同意后开启试验，进入页面设置阈值；**没有真实标准心率数据时** UI 显示等待，不能凭空发送阈值通知。
- [ ] 使用提供标准心率 GATT 的设备实际发送连续数据，低/高持续达阈值时本机最多通知一次；单个尖峰和恢复正常不会误报。
- [ ] 测试通知按钮在授予系统通知权限后能显示测试通知，且明确标记「手动测试」；没有发送短信和远端请求。
- [ ] 强制断开后，检查过期/断连信息，不再触发新的连续预警。进入后台后的功能不具备可靠性，勿做真实监护。
- [ ] 保留旧版 0.8.0 BLE GATT 服务读取、会话 CSV、独立加密历史功能正常。

## 来源、许可与第三方

- Android BLE GATT / NotificationManager / SharedPreferences / Android Keystore：Android 平台公开 API，不是额外复制的第三方代码。Android Open Source Project 由 Google 和开源贡献者维护，平台主体 Apache-2.0；具体 API 使用受 Android SDK 许可约束。
- Kotlin：JetBrains 及贡献者，Kotlin 编译工具 Apache-2.0；核心判断逻辑由 OpenKinGuard 自行实现，不复制私有厂商代码。
- AndroidX Jetpack Compose/Activity：Google / Android Open Source Project 贡献者，主要 Apache-2.0；通过 Maven 依赖使用。
- ZXing + ZXing Android Embedded：ZXing 作者团队 / JourneyApps 及贡献者，Apache-2.0；已有二维码功能通过 Maven 二进制引用。
- OkHttp：Square, Inc. 及贡献者，Apache-2.0；原有网络 WebSocket 功能。
- Python websockets：Aymeric Augustin 及 contributors，BSD-3-Clause；仅在可选自建中继内使用。
- pyca/cryptography：Python Cryptographic Authority 与贡献者，Apache-2.0/BSD；仅在可选自建中继内使用。
- Bouncy Castle：Bouncy Castle 项目团队，Bouncy Castle License；原有部分 Nostr 签名实现使用其发布的二进制库。此轮未新添第三方依赖。

更全面的上游链接和确认状态见 `docs/THIRD_PARTY_COMPONENTS.md`；请在正式公开发布前根据实际 Gradle 依赖锁文件复核传递依赖作者与 LICENSE，不要把候选 WebRTC/MapLibre 说成已经采用。
