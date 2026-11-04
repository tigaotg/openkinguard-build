# GitHub 可复用组件研究（2026-10-30）

用户约束：**只借用经过官方发布的功能/二进制依赖/公开接口，不复制第三方源码到本仓库**。使用二进制仍需要遵守许可证；不能将其误称为“无需授权”。当前核心采用自有的数据模型和状态机。

| 类别 | 上游仓库 / 许可证 | 是否进入 S0 | 采用边界 / 阶段 |
|---|---|---|---|
| Kotlin 语言 / 编译插件 | [JetBrains Kotlin](https://github.com/JetBrains/kotlin)，Apache-2.0，具体组件另核对 | 构建工具 | Gradle 插件，未复制源码 |
| Android UI | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)，多数 Apache-2.0 | **是** | 通过 Maven 使用 Jetpack Compose、Activity |
| Kotlin 协程 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)，Apache-2.0 | 否 | S1/S2 网络异步连接时按需引入 |
| Kotlin 序列化 | [Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)，Apache-2.0 | 否 | S1 消息协议定版、兼容与签名设计后引入 |
| QR 邀请扫码 | [journeyapps/zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded)，Apache-2.0 | **S1-02 已引入 4.3.0** | 使用 Maven 发布的 AAR；独立扫码界面；摄像头权限按需申请，不复制源码 |
| QR 编码/解码 | [zxing/zxing](https://github.com/zxing/zxing)，Apache-2.0 | **S1-02 已引入 core 3.5.4** | 使用 Maven 发布的 JAR；二维码生成和库内解码，不复制源码 |
| Android WSS 通信 | [square/okhttp](https://github.com/square/okhttp)，Apache-2.0 | **S2 0.5.0 已引入 4.12.0** | 仅通过 Maven 的二进制依赖建立 WSS 链接，不复制源码；独立设备公钥签名协议仍由本项目实现 |
| Nostr secp256k1 曲线运算 | [bcgit/bc-java](https://github.com/bcgit/bc-java)，Bouncy Castle License（宽松许可） | **S2 0.5.3 已引入 bcprov-jdk18on 1.83** | 只通过 Maven 依赖做椭圆曲线点运算，不复制源码；本项目实现 NIP-01/BIP-340 的签名包装并使用官方测试向量校验。公共消息另用 JCA P-256 ECDH/HKDF/AES-GCM 加密。 |
| 中继 WebSocket 服务 | [python-websockets/websockets](https://github.com/python-websockets/websockets)，BSD-3-Clause | **S2 服务端引入 13—16 的安装依赖** | 通过 Python 包安装，不复制源码；本项目仅实现路由与认证逻辑 |
| 中继签名验签 | [pyca/cryptography](https://github.com/pyca/cryptography)，Apache-2.0 / BSD | **S2 服务端引入 41—50 的安装依赖** | 验证独立 Android Keystore P-256 公钥挑战签名；不持有设备私钥 |
| 地图 | [maplibre/maplibre-native](https://github.com/maplibre/maplibre-native)，BSD-2-Clause；Android 子依赖逐项确认 | 否 | S3 使用官方 SDK，位置数据格式和地图显示严格分离 |
| P2P 候选 | [libp2p/jvm-libp2p](https://github.com/libp2p/jvm-libp2p)，仓库标识 Apache-2.0 / MIT | 否 | S0 仅列为候选，S2 需验证 Android 网络切换、NAT 和 ROM 后台限制 |
| WebRTC 候选 | [webrtc](https://webrtc.googlesource.com/src/)，BSD-3-Clause 源仓库及各组件另核对 | 否 | 只做对照实测，不能未经真机测试宣布最终选型 |
| 标准 BLE 心率 | Android 系统 BLE GATT API + Bluetooth SIG Heart Rate Service `0x180D` | 否 | S4 不需要将某厂商私有协议写入决策核心 |

当前 S2 0.5.3 Gradle 依赖包含 Android 构建插件、Kotlin、AndroidX Compose / Activity、JUnit、ZXing、OkHttp 与 Bouncy Castle 的二进制依赖；Python 中继通过环境管理包安装 `websockets`、`cryptography`。项目仓库未复制这些第三方库源码；正式分发须检查传递依赖，随包保留适用的第三方许可证及版权声明。暂未加入地图 SDK、WebRTC/P2P 或 BLE 候选组件。

选型纪律：先稳定身份与安全协议、再比较组件的实际可达率、电池成本、APK体积、更新能力与国内网络兼容；收到真实设备验证结果后才定稿。尤其 WebRTC 不等于保证无中继可穿透 NAT，libp2p 不等于自动解决安卓后台保活。
