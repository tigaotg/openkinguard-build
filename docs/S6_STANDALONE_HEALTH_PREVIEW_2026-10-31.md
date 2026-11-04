# OpenKinGuard 0.7.0 独立健康设备测试版交付说明

日期：2026-10-31

## 安装与兼容性

- **独立包名：** `io.github.tigaotg.openkinguard.debug.healthpreview`。
- **桌面名称：** OpenKinGuard 设备测试版（英语：OpenKinGuard Device Preview）。
- **版本：** `0.7.0-health-ble-devices-standalone` / versionCode 31。
- **系统：** Android 10 及更高版本（minSdk 29）；Android 10 与 Android 17 还需要真机测试。
- **与旧版共存：** 与已安装的 `io.github.tigaotg.openkinguard.debug.s1diagnostic` 不同包名，能够并行安装；不会读取或覆盖旧版的身份密钥、帮助关系、SOS 历史、权限与缓存。此版初始是独立新设备身份，旧版扫码关系不会自动迁移。

## 测试范围

底部第三个主栏目「设备」已加入。可点击扫描蓝牙低功耗设备，手动连接并订阅标准 GATT 特征，读取真实心率、血氧、血压、体温、体重数据；可在手机上查看最新测量及导出 CSV。

仍待真实设备验证；部分厂商私有协议可能不兼容；每次仅连接一台设备。App 进入后台会停止本次蓝牙扫描与连接，不提供连续生命监护、紧急医学警报、自动 SOS、跨移动网络 P2P 发送或保证送达。不要据此取代医疗或应急服务。

## 构建与签名

在项目根目录运行 `./gradlew :core:engine:test :app:testDebugUnitTest :app:assembleHealthPreview`；APK 输出路径为 `app/build/outputs/apk/healthPreview/app-healthPreview.apk`。

`healthPreview` 使用既有 S1 诊断版固定测试证书（SHA-256：`f64fe8a23261ebd81aae60f5c37d7f244550d421b016bc2cc891467aff65134f`），它仍需本地私有 keystore 文件，且源码 ZIP 不包含此密钥。证书仅用于测试，**不要用作正式发布签名**。

## 双机测试建议

1. 不卸载 0.6.4：直接安装独立健康测试版，确保桌面同时有两个应用。
2. 进入「设备」，同意附近设备权限（Android 10/11 还可能请求定位权限），打开蓝牙，点击扫描。
3. 如有标准 BLE 心率/血氧/血压/体温/体重设备，先让传感器进入广播/配对模式，再在列表中连接，观察服务和实时数据，不要将虚拟数据当作真实读数。
4. 点击导出会话 CSV 以验证测量数据能保存为文件；旧版帮助关系保持不变。

独立测试版与旧版互不共享数据，因此不能把独立测试版未发现旧版好友当作旧版绑定损坏，也不能把公共节点在线当作跨网业务通信成功。
