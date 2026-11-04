# Android 能力边界与验证矩阵（S0研究，非已测试证明）

分级：A 标准系统API可尝试 / B 用户明确授权、机型及系统策略决定结果 / C 仅前台或必须维持前台服务、仍可受限 / D 无法保证。每项最终都需在原厂系统、无GMS环境及国内网络测试。

| 能力 | 设计阶段 | 可实现边界 | 不能承诺 |
|---|---|---|---|
| 本地手动SOS | S1 | A：前台App按钮可触发本地事件 | 锁屏、电源键五击一定可被接管 |
| 本地通知提醒 | S1 | B：Android 13+ 通知权限及厂商通知管理 | 始终强弹窗、静音时肯定响 |
| 跨设备消息 | S1—S2 | B/D：LAN/P2P/可替换中继，需联网、授权、验签、回执 | 4G/5G移动切换后IP恒定、绝不丢消息 |
| 后台保活 | S2/S5 | B/C/D：前台服务类型、通知、电池优化及系统限制 | 安卓App永远在线、应用被强停后还能自启 |
| GPS按需定位 | S3 | B/C：细定位、后台定位有不同权限；提供精度、原始时间 | 无定位权限也有新位置、所有设备后台连续高频定位 |
| 标准BLE心率 | S4 | B/C：设备支持GATT心率服务且已配对/允许权限 | 所有品牌手环均有标准心率广播、无人值守持续有效 |
| 厂商健康记录 | S4/S6 | B/D：官方接口/Health Connect/同步延迟按设备限制 | 历史云端采样能作为实时急救证据 |
| SMS短信提醒 | S2 | B/D：运行时授权、硬件SIM、运营商、商店审核政策 | 普通Google Play应用可无限制后台自动收费发送 |
| 自动拨号 | S2 | B/D：通话权限、系统限制、用户授权和商店政策 | 第三方App可不受限制地替用户拨打紧急电话 |
| 查找手机外链 | S6 | A：官方网页/允许的APP Deep Link跳转 | 跨厂商直接读取厂商后台位置、免登录访问他人设备 |

政策变动频繁，开发每个真实模块前重新查阅：
- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/develop/sensors-and-location/location/permissions>
- <https://developer.android.com/develop/connectivity/bluetooth/bt-permissions>
- <https://support.google.com/googleplay/android-developer/answer/10208820>

原始 S0 AndroidManifest **未声明** GPS、蓝牙、SMS、电话、网络和后台定位权限。S1-02 增加手动二维码扫描所需的 `CAMERA`；S1-03-01 增加仅用于前台局域网签名测试的 `INTERNET`。目前仍未声明 GPS、BLE、SMS、电话、后台定位权限。

S1 一体化增加 `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE` 供前台 Android NSD/mDNS 服务发现（Android10 需要多播接收），以及 Android13+ 用户可选 `POST_NOTIFICATIONS`。仍不要求 GPS、短信发送、电话直拨或 GMS；所有电话和短信由用户手动打开系统应用。为兼容目前用户 Android17 真机，本版 targetSdk 仍为36；未来 targetSdk 提升到37须按官方文档适配 `ACCESS_LOCAL_NETWORK` 或系统服务选择器，不得在升级时静默失去局域网访问权限。

Android 17（API37）起，**目标 SDK 升至37及以上** 的本地网络访问需要另外声明并在运行时申请 `ACCESS_LOCAL_NETWORK`。当前实验 APK 的 targetSdk 为36，可借助 `INTERNET` 获得临时兼容访问；以后升级目标 SDK 时必须重新实施权限申请与拒绝处理。官方说明：<https://developer.android.com/privacy-and-security/local-network-permission>。
