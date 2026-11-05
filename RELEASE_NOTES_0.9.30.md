# OpenKinGuard 0.9.30 — 双移动网络远程协助信令修复（源码测试版）

基础版本：0.9.29 全局轻量界面。OpenWrt 家庭中继维持 0.3.5，无需因此更新路由器插件。

## 问题与修改

此前申请/同意远程协助可使用已认证家庭私人中继，但 WebRTC Offer/Answer/ICE 信令通过 `sendRemoteAssistPacket` 时强制 `allowRelay = false`。双手机移动数据下，没有可用局域网或 UDP 直连时，这些信令不能抵达对方，表现为系统已授权屏幕共享但控制端持续等待并显示黑屏。

本版限定性修改：

- OpenKinGuard 原有发送顺序仍为 **局域网 → 已认证 UDP P2P → 用户自己配置的家庭私人 WSS 中继**。
- 只有 `RemoteAssistStreamWire.requestKinds` 中定义的 RTC 信令、诊断和远程手势/导航指令才能使用该后备通道，且仍需设备签名、公钥关系、有效会话和人工授权。
- WebRTC 采集帧、音视频编码数据、媒体流**绝不序列化为中继业务帧**，继续使用 WebRTC ICE + DTLS/SRTP。
- 当 RTC 信令经家庭中继时，在诊断信息中标记；没有新增公共节点转发业务的路径，也没有削弱权限检查。

## 尚未解决的限制

该修改针对的是 SDP/ICE **信令无法到达**的明确代码问题，不能保证两台蜂窝网络设备的 WebRTC 媒体一定直连。当前只配置 STUN，不存在 TURN 后备服务器；若运营商 CGNAT、防火墙或 IPv4/IPv6 不匹配使 ICE 失败，仍可能黑屏。下一步应先用 Android 10 荣耀 7X 和 Android 17 红米 Note 12 Turbo 的双移动数据实测诊断区分：

1. SDP 信令是否收到、是否完成 offer/answer。
2. ICE 是否从 `CHECKING` 进入 `CONNECTED` / `COMPLETED`。
3. 被控制端是否真正捕获首帧，控制端是否解码首帧。
4. 视频成功后签名点击、滑动、返回/主页/多任务是否实际送达。

若仍卡 ICE，需要另外决定是否自建可信 TURN 服务；目前家庭中继插件不具备 TURN 媒体转发功能，不能把信令成功等同视频已连接。

## 构建与安全

Android 源码版本 `0.9.30-crosswan-rtc-signaling`，`versionCode=65`，Pilot V4 包名及签名身份不变。签名私钥、密码、绑定身份不应上传公开仓库。完整安装覆盖测试应使用同一 Pilot V4 签名，并在真机确认功能后才部署。仅供测试，不是现实急救保障。
