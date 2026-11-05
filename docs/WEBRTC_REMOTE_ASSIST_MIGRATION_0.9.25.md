# OpenKinGuard 0.9.25 — WebRTC 远程协助视频迁移

## 技术选择与许可证

- Android WebRTC AAR: `io.github.webrtc-sdk:android:150.7871.04`
- 项目：https://github.com/webrtc-sdk/android
- 上游：https://webrtc.googlesource.com/src
- SDK 及相关上游许可：BSD-3-Clause（需保留版权声明和上游许可文本）。
- OpenKinGuard 应用本身仍遵守 GPLv3，家庭中继仍遵守 AGPLv3。

## 替换范围

旧的逐张 JPEG、逐片段签名等待回执、定制 H.264 TCP socket、
自行调用 MediaCodec 进行编码或解码的代码已从当前构建路径移除。
旧版本源代码历史保存在 Git 提交和历史源码归档中，以便追查及回滚，
但本版不加载这些视频协议。

Android `MediaProjection` 系统授权由用户逐次确认，
`ScreenCapturerAndroid` 只在授权过的前台服务中创建，
每次会话终止时主动关闭屏幕采集、PeerConnection 和视频 track。
新实现不采集音频，不自动启用无障碍辅助功能。

OpenKinGuard 原本有签名及重放保护的连接通道仍负责
用户授权、会话关闭、远程手势、诊断和 WebRTC SDP/ICE 信令。
信令切成小块验证，每个块具有有效的当前会话 ID 及签名。
媒体本身只经 WebRTC 的 DTLS-SRTP 加密视频流与 ICE 通道，
不会进入 OpenKinGuard 家庭中继的签名业务消息队列。

## 局域网 / 公网使用边界

WebRTC 优先尝试局域网候选地址，公网可借助 STUN（当前
`stun.cloudflare.com:3478`）获得候选地址。不依赖 Google
Play 服务。使用 STUN 时，第三方可能观察到连接请求的公网
IP；若没有可用的 UDP 通路且没有 TURN 服务器，对称 NAT 或
严格运营商防火墙下依旧可能无法建立视频连接。

目前没有配置 TURN，故无法承诺任意公网网络均可直连。
不能把 "通过编译" 当作两台实际 Android 真机通过低延迟测试。

## 下一轮真机验收

1. Redmi Note 12 Turbo (Android 17) 和 Honor 7X (Android 10)
   都覆盖安装相同版本的 V4 APK；
2. 荣耀逐次同意远程协助、授予系统屏幕共享权限；
3. 红米开启控制窗口，确认 WebRTC ICE `CONNECTED` 或
   `COMPLETED`，并出现实时画面；
4. 持续上下滑动至少 30 秒，测试丢帧、黑屏、页面返回；
5. 结束协助后，确认通知栏屏幕共享状态消失、无法沿用旧授权；
6. 分别复制两台设备的诊断信息。30 FPS 是配置请求，
   不是自动测试已经证实的结果。

## 版本与源码规则

设置 → 关于软件 显示从已安装 APK 读取的版本号。
每次提交必须含同步源码及版本号，私有签名密钥禁止入库。
