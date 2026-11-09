# OpenKinGuard 第五阶段：最终回归与 72 小时真机验收

状态：**验收工具与 CI 自动化已实现；真机验收尚未执行；不得据此宣布稳定版通过**。

## 已有基础

- S3：网络切换恢复、后台监听、低功耗、P2P/WSS/Nostr 连接。
- S4：SOS / 报平安端到端加密、稳定消息 ID、签名回执、离线补投。
- 7 天脱敏连接历史：可从 APP「实时连接状态」查看并手动导出 TXT/JSON。
- OpenWrt 家庭中继：短时间密文暂存、过期清理、延迟签名回执转发。

第五阶段不重做这些功能；重点是对它们进行**联合验证**，排除回归，提供可复现证据。

## GitHub CI：先完成源码全量验证

工作流：`.github/workflows/phase5-validation.yml`

- 构建 `:app:compilePilotV4Kotlin`，以及 core / adapters / security 和 app 的**所有 JVM 单元测试**。
- 同时运行 OpenWrt 家庭中继、公共中继测试及 Python 验收工具测试。
- 只编译源码，不执行 assemble、bundle、install、打包 APK 或 AAB。
- 此 CI 可以发现许多编译/逻辑错误，但**不能**证明真机长时间待机、后台保活、跨运营商 P2P 或实际 SOS 投递可靠。

## 双机 72 小时最低验收标准

使用两台不同品牌手机（例如 Android 10、Android 17），保留原安装包身份和绑定数据。
操作前必须确认已安全备份关键身份/绑定材料。不重新签名覆盖现有包，不清空应用数据。

每台手机开启开发者选项、USB 调试并授权自己的电脑。测试采集程序**只读**，不重启手机，不调用电话/短信，不切换网络、不修改权限、不读取 APP 私有数据。

Linux/macOS/Windows 均可安装 Python 3.10+ 和 Android Platform Tools，运行：

```bash
adb devices -l
python scripts/acceptance/phase5_monitor.py collect \
  --serial <DEVICE_1_ADB_SERIAL> --serial <DEVICE_2_ADB_SERIAL> \
  --package <ACTUAL_INSTALLED_APPLICATION_ID> \
  --duration-hours 72 --interval 60 --output-dir phase5-field-72h
```

如果两台手机的 applicationId 不同，请分别运行采集程序或先为同一版本和包名安排两台测试设备。**不可**猜测生产签名或覆盖安装已绑定设备。长时间 USB 连线可能让手机持续充电并影响耗电数据，报告应标注充电情况。

采集结束后：

```bash
python scripts/acceptance/phase5_monitor.py summarize \
  --input phase5-field-72h/samples.jsonl --duration-hours 72 --interval 60
```

输出：

- `devices.json`：设备编号、系统 API、厂商（不写入 ADB 序列号）。
- `samples.jsonl`：每分钟 APP 进程、ADB 可达、电量、充电、屏幕和休眠状态的**粗粒度脱敏数据**。
- `summary.json`：采样覆盖率、采样空档、APP 进程停止次数和时间范围。

所有输出均为监测证据，不能把 USB 通道不可达推断为“手机已断网”，也不能把进程存在推断为“能收到 SOS”。

## 人工必须完成的十项场景

1. SOS 接收手机实际收到，且原发送端验证**设备签名回执**。
2. 家属手动确认，与中继暂存/手机接收严格区分。
3. 报平安接收手机实际收到签名回执。
4. 发送时对端离线，恢复后成功补投；重复消息不产生多次警报。
5. Wi-Fi → 移动数据 → Wi-Fi 后自动恢复。
6. OpenWrt 家庭中继服务重启，未过期密文暂存按策略恢复。
7. 熄屏及后台情况下实际送达 SOS。
8. 去重测试：同一逻辑 SOS 不重复报警。
9. APP 进程重建或正常重启后原身份、关系、历史保持。
10. 从 APP 导出七天连接历史的脱敏 TXT/JSON，并核对关键事件。

人工场景必须留下时间戳、手机侧脱敏记录或截图、结果与缺陷编号。不强制引入此前未实现的自动拨号和短信功能；这些**不得被冒称为已经验证**。

## 验收检查器

将 `scripts/acceptance/checklist.example.json` 复制成自己的 `checklist.json`，只在各场景真实通过后设为 `true`，没有未解决的关键安全缺陷时将 `critical_unresolved_defects` 设为 `false`。

```bash
python scripts/acceptance/phase5_gate.py \
  --summary phase5-field-72h/summary.json \
  --devices phase5-field-72h/devices.json \
  --checklist checklist.json
```

工具会检查两台不同厂商、Android 10/17、连续观察约 72 小时、覆盖率、明显采样空档、应用进程意外中断和人工场景。即使全部满足，工具只显示 **READY_FOR_HUMAN_REVIEW**，**不是稳定版准入证、不是安全保证，也不是发布授权**。

## 停止/失败条件

若发生求救无法接收、签名回执验签失败、离线消息丢失、重复 SOS 造成多次警报、应用意外停止后无法恢复、隐私泄露或绑定关系损坏，立即记录缺陷并停止宣称当前版本可用于真实紧急监护。修复后针对相关场景重新验收，必要时重新计时 72 小时。

## 数据保护

采集程序不采集 IP、原始 dumpsys、ADB 序列号、证书、钥匙、设备完整指纹、地址和联系人。APP 导出的七天历史也只应包含脱敏状态码。保存或分享测试日志前再次检查，不上传签名文件或真实病历。
