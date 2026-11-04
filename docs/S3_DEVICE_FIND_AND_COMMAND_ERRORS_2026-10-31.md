# OpenKinGuard 0.4.1 · 厂商查找设备与命令失败说明（2026-10-31）

## 更新内容

- `versionCode=16` / `versionName=0.4.1-find-portals-errors`，原 `s1diagnostic` 应用 ID、签名与局域网身份保持不变，Android 10 起支持。
- 设置中新增折叠式“厂商查找设备”，只使用固定 HTTPS 官方网页入口，调用系统浏览器打开，**不在应用内嵌 WebView、也不收集厂商账号、密码或验证码**。
- 所有人工命令的异常会在主界面给出中文原因，并在“记录 → 操作”保存最近80条本机操作结果。日志与既有演练历史一起经 Android Keystore AES-GCM 加密，按7/30/90天规则保留；JSON 手动导出会包含这些操作记录，导出文件本身是明文。
- 后台服务的历史行为不被此版本改动。定位、SOS、报平安、绑定等操作的错误显示更清楚；远端是否真的已收到，仍必须以正确的**签名回执**为准。

## 厂商入口（2026-10-31 根据厂商官方支持页面核对）

| 入口 | 官方链接 | 适用范围/备注 |
|---|---|---|
| 华为云空间 | https://cloud.huawei.com/ | 需先开启华为查找设备；部分旧荣耀仍用华为账号 |
| 荣耀查找设备 | https://cloud.honor.com/ | 支持荣耀云/荣耀账号查找功能的机型 |
| 小米、Redmi | https://i.mi.com/ | 已开启小米账号查找设备 |
| OPPO、一加、realme | https://cloud.oppo.com/findindex.html | 官网注明可用欢太账号查找支持机型，部分地区不同 |
| vivo、iQOO | https://yun.vivo.com.cn/ | 登录与设备一致的 vivo 账号 |
| 三星 Samsung Find | https://samsungfind.samsung.com/ | 对应三星账号及设备服务启用 |
| Google/Pixel/摩托罗拉等 | https://android.com/find | Google 查找中心；纯 AOSP、无 Google 服务机型可能不支持 |
| 魅族 Flyme | https://finder.flyme.cn/ | 需在 Flyme 里先开启查找手机 |
| 联想部分平板 | https://z.zui.com/ | 联想知识库目前仅确认指定 Y700 无极型号，其他平板不保证可用 |
| 苹果 iCloud | https://www.icloud.com/find | 方便家属使用自己的浏览器查找 Apple 设备 |

支持说明参考：[华为](https://consumer.huawei.com/cn/support/content/zh-cn16010223/)、[荣耀](https://www.honor.com/cn/support/content/zh-cn15809729/)、[小米](https://i.mi.com/guide/zh-CN/find/overview)、[OPPO](https://cloud.oppo.com/findindex.html)、[vivo](https://www.vivo.com.cn/service/questions)、[三星](https://www.samsung.com/jp/support/mobile-devices/features-of-the-samsung-find-app/)、[Google](https://support.google.com/android/answer/6160491?hl=zh-Hans)、[魅族](https://cloud.flyme.cn/)、[联想](https://iknow.lenovo.com.cn/detail/435042?type=xg)、[Apple](https://support.apple.com/zh-cn/guide/icloud/mmfc0f2442/icloud)。

入口与 OpenKinGuard 自身 GPS 没有数据交换；厂商账号、状态、定位精度和网页是否可用均由相应厂商决定。只有设备拥有者或经其授权的人才能登录；擦除数据等操作可能不可逆，用户必须在官方网页手动确认。

## 命令失败分类与证据边界

| 代码 | 事实依据 | 用户看到的意思 |
|---|---|---|
| `REMOTE_DENIED` | 已验签的定位拒绝 | 对方未授权这条关系查看位置 |
| `REMOTE_OS_PERMISSION` | 已验签的定位拒绝 | 对方没有系统定位权限 |
| `REMOTE_BACKGROUND` | 已验签的定位拒绝 | 对方未在前台打开 App |
| `REMOTE_NO_FIX` | 已验签的定位拒绝 | 对方未能获取有效位置 |
| `NETWORK_TIMEOUT` | 本机连接/读取超时 | 可能弱网、离线或响应慢，不能推断对方是否授权 |
| `CONNECT_FAILED` | TCP 连接被拒绝 | 对方没有正常接受局域网连接 |
| `NETWORK_UNREACHABLE` | 无路由或域名解析失败 | 网络路径不可达 |
| `CONNECTION_INTERRUPTED` | 连接重置或提前结束 | 未取得完整回执 |
| `CRYPTO_FAILED` | 签名/加密校验异常 | 不采用不可信内容 |
| `LOCAL_PERMISSION` | 本机系统权限异常 | 提示检查应用权限 |
| `NOT_READY` / `INVALID_COMMAND` | 本地状态或参数不合法 | 直接说明本机未就绪或校验失败 |

只有远端有效签名的 `DENIED` 才能作为远端拒绝的依据。**TCP超时不表示远端拒绝，也不表示远端同意**；发送成功不等于救助者读过。所有记录都有时间和失败类别；多设备发报平安、SOS 或结案时会显示多条尚未确认的原因，而不是只显示第一个。

## 测试与安全说明

新增纯 JVM 测试覆盖明确拒绝与超时不能混淆、断路、连接拒绝、加密异常、异常包装和日志长度限制。所有版本仍是诊断演练 APK；尚未进行本轮 Android10/17 真机确认，不应作为正式求救或设备定位保证。Android17 前台通知权限问题也不能靠网页入口解决。
