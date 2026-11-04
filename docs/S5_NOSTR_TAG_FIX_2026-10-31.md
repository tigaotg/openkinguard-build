# S5-02 Nostr 公共信令 0/3 拒收修复（2026-10-31）

版本 `0.6.3-nostr-tag-fix` / `versionCode=29`，最低 Android 10。

## 真机证据

用户的两张截图中：

- `relay.nostr.net` 与 `relay.nostr.info`：`invalid: tag val was not a string`
- `relay.kizuna-miki.com`：同样拒绝身份公告
- `relay.nostr.wirednet.jp`：HTTP WebSocket 升级收到 `403 Forbidden`

源代码 `S2NostrWire.sign()` 将 NIP-40 的 `expiration` 标签的值编码为 JSON number：`["expiration",1793430000]`，与 NIP-01 的 tag 值必须为字符串不符。改成 `["expiration","1793430000"]`。NIP-40 的规范示例也明确采用字符串形式。官方参考：

- https://github.com/nostr-protocol/nips/blob/master/01.md
- https://github.com/nostr-protocol/nips/blob/master/40.md

修复同时加强 `S2NostrWire.verify()`：拒绝非字符串 tag 值和已过期的 `expiration` 标签。单元测试直接重新签署一个含数字标签的异常事件，确保即使加密学签名正确也不能绕过协议格式检查。

## 节点调整

默认三节点改为 `relay.nostr.net`、`relay.nostr.info`、`relay.kizuna-miki.com`。因真实手机观察到 `wirednet.jp` 返回 403，把它移入手选的旧候选组合，不再作为默认值。只有存储内容完整等于上一版内置三节点列表时才自动升级成新默认；用户自行编写的列表不被覆盖。

**重要：** 修复格式与调换节点只是明确的代码修复，不等于已经验证这些域名在中国大陆移动网络上可用。中继还可能拒绝 kind 30078、要求 AUTH、阻断 WebSocket 或限制 IP。须根据 0.6.3 的新错误文本再做判断。

## 交互调整

设置页的 Nostr 错误详情初始折叠，只显示「至少一个节点已确认」或「尚无节点完成确认」，点击「展开节点详细诊断与错误原因」才能查看逐节点错误，避免撑满一整屏。原始信息仍完整可访问。

## 不变的安全边界

- 公共 Nostr 节点仍**不允许**转发 SOS、GPS、报平安等业务帧，仅保留身份信令。
- STUN 是本机 UDP 地址诊断，尚未实施真正的 IP 候选交换和 NAT 打洞，跨网 P2P 业务仍不可用。
- 接入一个节点只说明本机订阅与公告可被接受，不说明远端设备收到求助。
- 同局域网 TCP 验签通信和独立选配的私人中继功能保持不变。
- 没有可靠 P2P 通道前，仍不可用于真实急救。

## 0.6.3 双机复测

1. `adb install -r` 覆盖 Android 10 / Android 17 上的 0.6.2，保留原身份和配对关系。
2. 默认列表已自动迁移时，应显示 `kizuna-miki.com` 而不是 `wirednet.jp`；自定义列表需自己点「使用大陆优先候选」。
3. 两台手机分别启用公共信令。打开「展开节点详细诊断与错误原因」，记录每台 `n/3` 和每个域名的错误。
4. 若曾经的 `invalid: tag val was not a string` 消失，说明标签问题修复得到真机验证；如继续有 AUTH、403、限制 kind 等提示，则另行处理节点兼容性。
5. 不应在移动数据跨网状态把任何 SOS、GPS 业务显示为已送达（除非用户另行自愿配置并验收私人中继）。

129 项权威需求文件 SHA-256 不变：`42ddb0fa3be4f84f0efa1272614cba64791d7e148e67d5e6b521af5eeb910461`。
