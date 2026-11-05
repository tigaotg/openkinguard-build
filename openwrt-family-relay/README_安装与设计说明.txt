OpenKinGuard 家庭专用 OpenWrt 私人中继插件 0.3.3（设备管理与恢复授权，源码验证版）
日期：2026-11-05
用途：两台已配对 Android 手机 P2P 失败时，由自己的路由器转发签名业务消息。
目标路由：腾达 Tenda BE12 Pro，OpenWrt 25.12+ / apk / LuCI / procd。
0.3.0：延续自生成并显式固定 SPKI 公钥指纹的私有 TLS；新增一次性二维码、
管理员批准、UCI 持久准入和精确 IPv6 端口开关；
兼容原来的受信任域名 WSS 节点。安装脚本保持默认不启用、不开放公网端口。
兼容 0.1.1 的 cp/chmod 安装方式，不依赖 install 命令。
0.3.2：扫码申请额外携带手机自报昵称和型号，审批时记录首次批准时间；
LuCI 新增“已授权设备 / 已取消设备”、路由器备注、取消绑定与恢复授权。
取消会从 UCI 白名单撤销身份、清除临时批准并重启中继断开已有会话。
旧手机的批准时间无法追溯，页面显示“旧版本未记录”，不会编造时间。
元数据仅保存在路由器 root 可访问的 /etc/openkinguard-relay/devices.json，
没有新增云账号；显示昵称和型号仅作参考，设备身份仍以公钥指纹为准。
0.3.3：已取消设备新增“彻底删除”：经输入确认后，只允许删除不在 UCI 白名单的已取消设备；清除本机设备登记、昵称、型号、备注和该指纹的旧扫码申请，不允许再直接恢复，若重新加入必须重新扫码并获批准。该操作不改变其他设备授权，也不删除用户手机中的帮助关系和历史。
搭配手机 0.9.28：扫码批准通知丢失后会继续通过经过 TLS 钉扎的设备挑战核验路由器授权，避免错误提示重新扫码；仍要求实际设备密钥证明。
升级安装不删除已有白名单、证书、手机绑定或本地历史；会短暂重启本中继服务。
扫码二维码需额外安装 qrencode：apk -U add qrencode；安装程序不会代装依赖。
0.3.3 尚未在真实路由器或两台手机上完成回归联调；不得将本地测试等同实测。

【先看结论】
代码、LuCI 页面、procd 服务、身份白名单、连接数限制、应用层出站速率限制和
离线 RAM 暂存均已实现。本地 11 项 WebSocket 集成测试与 2 项证书身份测试通过。
此前 0.1.1 已由用户在真实 BE12 Pro 上安装，0.2.0 免域名改动尚未真机验证，
也没有验证公网 IPv6 入站与家庭网络防火墙。这是可上传路由器的插件源包，不是假装已生成的
OpenWrt 官方签名 .apk，也不代表已经完成公网部署。

一、与 OpenKinGuard 0.9.4 免域名独立测试 APK 的配合
本版本的自签名证书必须使用 0.9.4+ 配套客户端；旧版不能验证该方案。
手机「设置→公网通信→私人中继」填写：
    wss://你的域名:9443/relay
或免域名方案（复制路由器本地终端生成的完整地址，包括#spki=）：
    wss://[你的公网IPv6]:9443/relay#spki=64位小写十六进制节点公钥指纹
两台手机填完全相同的地址。已绑定关系签名不变；本插件仅负责转发。
免费公共 Nostr 节点与这台私人中继是两个不同服务：后者可以承载既有业务帧。
中继并不能伪造应用的签名，但目前并非所有业务帧均具有完整 E2E 加密。
GPS 结果的应用层加密仍按现有协议执行，服务器可以观察消息元数据。

二、安全默认值（重要）
- 插件安装完默认 enabled=0，不主动开启公网监听。
- 没有有效的受信任 TLS 证书和私钥时拒绝启动。
- 允许零初始白名单启动，但陌生设备只能凭限时令牌提交**待批准申请**，
  不能发送普通业务消息；必须管理员在 LuCI 批准并写入 UCI 后才能成为正式设备。
- 未经白名单许可的设备，即使有自己的合法签名公钥，也无法连接。
- 最大并发16台（可配置1-64）；应用总出站上限25000 kbps（可降低到64）。
- 不修改 firewall，不做 DDNS，不开放公网端口，不修改现有 LuCI 管理端口。
- Python 读取证书后自动降权到 nobody，再接受网络连接。
- SOS/报平安允许用户主动开启的短时 RAM 暂存；暂存不算已送达。
- 只适合自家亲友的测试阶段，不能作为单一的紧急救援保障机制。

三、先在路由器只读自检
SSH 登录你自己的 OpenWrt，运行：

    ubus call system board
    uname -m
    df -h /overlay
    free -h
    apk list --installed | grep -E '^(python3|luci|rpcd)'

把结果（隐去公网 IP、凭据）发给我，就能进一步核对具体固件兼容性。

四、依赖准备（需有可用的 OpenWrt 包源和足够剩余空间）

    apk -U add python3 python3-cryptography python3-websockets luci-base

不要自行从 Alpine Linux 仓库下载混装同名 apk；必须使用适配当前固件架构、
ABI 与 OpenWrt 版本的软件源。如提示依赖缺失或空间不足，请停止并反馈日志。
软件源中的 python3-websockets 可能为 11.x；本插件使用与其兼容的 legacy API。

五、上传与安装（Windows 例子）
将完整压缩包解压到电脑，假设得到目录 openwrt-family-relay。
Windows PowerShell 在该目录的父目录运行（路由器IP替换成实际LAN地址）：

    scp -r .\openwrt-family-relay root@192.168.2.1:/tmp/

SSH 到路由器后先只检查：

    sh /tmp/openwrt-family-relay/install-on-openwrt.sh --check

通过后再确认安装：

    sh /tmp/openwrt-family-relay/install-on-openwrt.sh --install

安装脚本只更新本插件文件，覆盖已有程序文件前生成带时间戳的 .bak 备份，
并短暂重启 OpenKinGuard 中继使撤销功能生效，不重启整台路由器。
不会改已存在的 /etc/config/openkinguard-relay；不会自动下载软件包。
随后刷新 LuCI，进入「服务→OpenKinGuard 家庭中继」。

六、免域名私有证书（0.2.0 新增，推荐自家测试）
1. 安装并更新插件后，在 OpenWrt 本地 SSH 运行：
    sh /tmp/openwrt-family-relay/install-on-openwrt.sh --setup-no-domain
2. 自动生成 P-256 私钥和自签名 TLS 证书，保存在：
    /etc/ssl/openkinguard/fullchain.pem
    /etc/ssl/openkinguard/privkey.pem
   私钥仅 root 可读。重新运行命令会复用同一密钥，指纹不变。
3. 脚本会尝试从 wan_6/wan6 找到路由公网 IPv6，并输出可复制的 WSS 地址。
   若未找到则手工填入当前可达公网地址；路由器公网 IPv6 变化后必须更新客户端地址。
4. 将完整连接地址（含 #spki=...）分别填入两台新版 Android 客户端。
   仅从自己信任的本地终端核对指纹；绝不可跳过 TLS 身份验证。
5. 仍需在 LuCI 中设置两台手机的设备白名单、保存应用并显式启用中继。
6. WAN 防火墙端口 9443 不会自动开放；两台手机公网测试之前，需要单独确认
   外部 IPv6 可达且确有需要，再手工建立仅作用于 TCP 9443 的入站规则。
7. 这不是公开受信任的证书，浏览器会提示不可信；只适合支持显式 SPKI 验证
   的 OpenKinGuard 新版客户端。不得将此功能推广为向所有用户开放的公共中继。

七、旧式 TLS 域名证书（仍支持，不是免域名模式必需）
1. 准备你控制的域名，例如 guard.example.cn，并通过 DDNS 解析到家里当前可达地址。
2. 如果家宽没有公网 IPv4，可以先走公网 IPv6；外部手机也必须有可用 IPv6。
3. 申请可信证书，推荐通过 OpenWrt ACME/DNS-01 方式；证书续期需另行配置。
4. 将完整证书与私钥放在下面路径，严格保护私钥：
    /etc/ssl/openkinguard/fullchain.pem
    /etc/ssl/openkinguard/privkey.pem
5. 不要使用自签名证书欺骗 Android 的 TLS 验证，不要关闭客户端证书校验。
6. 在 LuCI 插件设置中输入这两个路径与允许接入的设备指纹，保存应用后启动。

八、家人设备白名单
在 OpenKinGuard「设置→高级信息→检查本机签名身份」找到手机指纹。
也可在设备与授权页查看绑定设备指纹。务必逐一核对 64 位十六进制字符。
两台手机均需加入白名单（不能只填一台）。无需微信账号或手机号。
只要白名单中有设备，就算其他陌生人知道中继域名也不能通过签名认证。

新版本建议从 LuCI「服务 → OpenKinGuard 家庭中继 → 已授权设备」管理：
- 扫描一次性二维码，核对公钥指纹后批准；昵称、型号和首次批准时间会显示在列表里。
- 点击「修改备注」给路由器上的手机取本地名字，不会修改手机昵称或设备密钥。
- 点击「取消绑定」会移出 UCI 白名单、中断中继连接；该设备出现在「已取消设备」。
- 确认仍然信任原手机时点击「重新授权」，恢复同一个公钥指纹，无需重新扫码。
- 若怀疑手机私钥失窃，不要使用恢复功能，应要求手机生成新身份并重新安全绑定。
- 手机未连接不等于被撤销；仅新版中继对已验证公钥明确返回拒绝时，手机才显示
  「路由器端未绑定您」，并每60秒尝试重新确认恢复状态。
- 这只取消手机访问**该家庭中继**的权限，不会遥控删除手机本地的家人关系或消息历史。

九、防火墙和外网验证（需要你在确认节点地址与指纹后自行开启）
OpenWrt LuCI→网络→防火墙→通信规则，新建允许 WAN 来源访问「此设备」
TCP 9443 的精确规则；不要开放 LuCI 管理端口80/443、SSH22 等其他服务。
若上级光猫 IPv4 NAT，则需要单独端口转发；在 CGNAT 下 IPv4 转发无法解决。
若走 IPv6，需确认家宽 IPv6 前缀、路由器 WAN 入站规则和手机网络 IPv6 可达。
建议先在家庭 LAN 内用域名验证可信证书和 WSS；再从手机移动数据实测。

十、状态检查与故障排除

    /etc/init.d/openkinguard-relay restart
    /etc/init.d/openkinguard-relay status
    logread -e openkinguard
    logread -e okg-relay
    netstat -lnt | grep 9443

常见问题：
- 服务没有启动：白名单空、证书私钥缺失、依赖未安装或端口被占用。
- Android 出现 TLS 错误：域名/证书不匹配、证书过期或系统时间错误。
- 两端连接了不同中继：双方必须有至少一个共同节点才能互相送达。
- 局域网可以、移动数据不行：公网 IPv4/IPv6 不可达或 WAN 防火墙未放行。
- 显示在线仍无 SOS 回执：必须检查 Android 守护监听、实际签名 ACK。
- 25Mbps 应用层上限不能等于严格链路限速；要留出家庭上网余量可配置 SQM。

十一、后续可制作正式 .apk 包
根目录 Makefile 是 OpenWrt package feed 配方，依赖项同上。
在与你实际固件匹配的 OpenWrt SDK/源码树中作为一个本地 package 构建后，
才会得到可由 apk 包管理器安装、管理的真正 .apk 安装包。
当前发行的是无需交叉编译即可手动安装的源码插件，避免冒用其它固件的架构包。

十二、功能与版权归属
- OpenKinGuard 私人中继协议及服务器实现：OpenKinGuard contributors；AGPLv3。
- Python 3：Python Software Foundation 与社区贡献者。
- websockets：Aymeric Augustin 与项目贡献者（BSD-3-Clause）。
- cryptography：Python Cryptographic Authority / PyCA 贡献者（Apache-2.0 / BSD）。
- OpenWrt、procd、UCI、LuCI：OpenWrt Project 与各自维护者；遵守上游许可。
- TLS、WebSocket：标准协议，不代表复制第三方服务器源码。
官方技术来源及许可证详见 NOTICE_开源技术归属.txt。

【后续最需要你提供】路由器设备信息与存储、自有域名/IPv6 可达性、两台手机指纹。
无需提供路由器密码、TLS 私钥或其他敏感凭据。
