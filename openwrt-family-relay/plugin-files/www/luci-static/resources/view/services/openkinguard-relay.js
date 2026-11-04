'use strict';
'require view';
'require form';
'require fs';
'require ui';

function joinAction(action, id, value) {
	var args = [action];
	if (id !== undefined) args.push(id);
	if (value !== undefined) args.push(value);
	return fs.exec('/usr/lib/openkinguard-relay/manage-join', args).then(function(reply) {
		if (reply.code !== 0) throw new Error('管理员操作被拒绝，检查 LuCI 权限和安装版本');
		var data = JSON.parse(reply.stdout);
		if (!data.ok) throw new Error(data.error || '操作失败');
		return data;
	});
}

function showJoinError(error) {
	ui.addNotification(null, E('p', {}, 'OpenKinGuard：' + (error.message || error)), 'danger');
}

return view.extend({
	render: function() {
		var m, s, o;
		m = new form.Map('openkinguard-relay', 'OpenKinGuard 家庭中继',
			'为家人设备提供独立 WSS 消息转发，不向公众开放。默认关闭。' +
			'支持可信 TLS 域名证书，或由路由器生成免域名私有证书，手机客户端必须核对节点公钥指纹。' +
			'填写已绑定设备的 64 位指纹，' +
			'然后保存并应用。请勿把 LuCI 管理页面直接暴露到公网。');
		s = m.section(form.TypedSection, 'relay', '家庭专用节点');
		s.anonymous = true;
		s.addremove = false;

		o = s.option(form.Flag, 'enabled', '启用私人中继');
		o.default = '0';
		o.rmempty = false;

		o = s.option(form.ListValue, 'listen', '监听协议');
		o.value('both', 'IPv4 + IPv6（推荐）');
		o.value('0.0.0.0', '仅 IPv4');
		o.value('::', '仅 IPv6（视系统双栈行为）');
		o.value('127.0.0.1', '仅本机（配合其他 TLS 代理）');
		o.default = 'both';
		o.rmempty = false;

		o = s.option(form.Value, 'port', 'WSS 端口');
		o.default = '9443';
		o.datatype = 'range(1024,65535)';
		o.rmempty = false;

		o = s.option(form.Value, 'cert', 'TLS 证书 fullchain.pem 的绝对路径');
		o.default = '/etc/ssl/openkinguard/fullchain.pem';
		o.description = '免域名模式：SSH 中执行 sh /tmp/openwrt-family-relay/install-on-openwrt.sh --setup-no-domain，生成证书后将节点公钥指纹与地址同时填入两台手机。';
		o.rmempty = false;
		o = s.option(form.Value, 'key', 'TLS 私钥 privkey.pem 的绝对路径');
		o.default = '/etc/ssl/openkinguard/privkey.pem';
		o.rmempty = false;

		o = s.option(form.DynamicList, 'allowed_device', '允许接入的家人设备指纹',
			'每行一个 OpenKinGuard 设备的 64 位小写十六进制指纹。未填写时服务拒绝启动。' +
			'只允许本人的家人设备，不要填随机示例值。建议在下方“已授权设备”中取消或恢复，' +
			'手工删除此名单可能缺失撤销时间记录。');
		o.rmempty = true;
		o.validate = function(section_id, value) {
			if (!value) return true;
			if (Array.isArray(value) && value.every(function(fp) { return /^[0-9a-f]{64}$/.test(fp); }))
				return true;
			if (typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)) return true;
			return '设备指纹必须是 64 位小写十六进制字符';
		};

		o = s.option(form.Value, 'max_clients', '最大并发设备数');
		o.default = '16';
		o.datatype = 'range(1,64)';
		o.rmempty = false;

		o = s.option(form.Value, 'rate_kbps', '应用层总出站速率上限（kbps）',
			'默认 25000 = 25 Mbps。仅限制本服务发出的数据，不保证整个 WAN 的精确 QoS；' +
			'要严格限制家庭上行占用，仍需在 OpenWrt 使用 SQM / QoS。');
		o.default = '25000';
		o.datatype = 'range(64,25000)';
		o.rmempty = false;

		return m.render().then(function(settings) {
			var qrBox = E('div', { 'style': 'margin-top:12px' });
			var pendingBox = E('div', { 'style': 'margin-top:10px' });
			var approvedBox = E('div', { 'style': 'margin-top:10px' });
			var revokedBox = E('div', { 'style': 'margin-top:10px' });
			var qrLabel = E('p', {}, '二维码有效期5分钟，使用一次即失效；手机扫码后仍须在下方批准。请仅在自己可信的路由器管理页面上出示。');
			var firewallBox = E('div', { 'style': 'margin-top:8px' });
			function showFirewall() {
				return joinAction('firewall-status').then(function(data) {
					firewallBox.replaceChildren(E('p', {},
						'IPv6 WAN 入站 TCP ' + data.port + '：' +
						(data.enabled ? '已启用专用放行规则' : '未启用专用放行规则')));
				}).catch(showJoinError);
			}
			var makeQrButton = E('button', {
				'class': 'btn cbi-button cbi-button-positive',
				'click': function() {
					qrBox.replaceChildren(E('p', {}, '正在生成一次性邀请…'));
					return joinAction('invite').then(function(data) {
						qrBox.replaceChildren(E('img', {
							'src': 'data:image/svg+xml;base64,' + data.svgBase64,
							'alt': '一次性家庭中继加入二维码',
							'style': 'max-width:300px;width:100%;background:white;padding:12px'
						}), E('p', {}, '路由器公钥指纹：' + data.nodeFingerprint),
							E('p', {}, '请用 OpenKinGuard → 设置 → 私人中继 → 扫描加入二维码。'));
					}).catch(showJoinError);
				}
			}, '生成限时加入二维码');

			function displayTime(seconds) {
				return seconds ? new Date(seconds * 1000).toLocaleString('zh-CN') : '旧版本未记录';
			}
			function showSavedDevice(item, revoked) {
				var fp = item.fingerprint;
				var panel = E('div', {
					'style': 'padding:12px;border:1px solid #ccc;border-radius:6px;margin:8px 0;overflow-wrap:anywhere'
				}, [
					E('strong', {}, item.note || item.nickname || '未命名设备'),
					E('p', {}, '手机自报昵称：' + (item.nickname || '未提供')),
					E('p', {}, '手机自报型号：' + (item.model || '未提供')),
					E('p', {}, '设备公钥指纹：' + fp),
					E('p', {}, '首次批准：' + displayTime(item.boundAt))
				]);
				if (item.note) panel.appendChild(E('p', {}, '路由器备注：' + item.note));
				if (item.restoredAt) panel.appendChild(E('p', {}, '最近恢复：' + displayTime(item.restoredAt)));
				if (revoked) panel.appendChild(E('p', {}, '取消时间：' + displayTime(item.revokedAt)));
				panel.appendChild(E('button', {
					'class': 'btn cbi-button',
					'click': function() {
						var note = window.prompt('设置本路由器上的备注（最多32字，不改变手机名称）', item.note || '');
						if (note === null) return;
						return joinAction('set-note', fp, note).then(refreshAll).catch(showJoinError);
					}
				}, '修改备注'));
				panel.appendChild(document.createTextNode(' '));
				panel.appendChild(E('button', {
					'class': 'btn cbi-button ' + (revoked ? 'cbi-button-positive' : 'cbi-button-negative'),
					'click': function() {
						var question = revoked ?
							'恢复此设备的私人中继访问权限？请确认仍然信任该手机及其完整公钥指纹。\n' + fp :
							'取消此设备的中继访问权限？当前会话将断开，但记录留在“已取消设备”，以后可恢复。\n' + fp;
						if (!window.confirm(question)) return;
						return joinAction(revoked ? 'restore' : 'revoke', fp)
							.then(refreshAll).catch(showJoinError);
					}
				}, revoked ? '重新授权' : '取消绑定'));
				return panel;
			}
			function refreshAll() {
				return joinAction('list').then(function(data) {
					pendingBox.replaceChildren();
					approvedBox.replaceChildren();
					revokedBox.replaceChildren();
					if (!data.pending.length)
						pendingBox.appendChild(E('p', {}, '目前没有等待批准的设备。'));
					data.pending.forEach(function(item) {
						var fingerprint = item.fingerprint;
						var approve = E('button', {
							'class': 'btn cbi-button cbi-button-positive',
							'click': function() {
								if (!window.confirm('你已在手机上核对完整设备指纹，确认允许该设备加入？\n' + fingerprint)) return;
								return joinAction('approve', item.id).then(refreshAll).catch(showJoinError);
							}
						}, '批准');
						var reject = E('button', {
							'class': 'btn cbi-button cbi-button-negative',
							'click': function() {
								return joinAction('reject', item.id).then(refreshAll).catch(showJoinError);
							}
						}, '拒绝');
						pendingBox.appendChild(E('div', { 'style': 'padding:10px;border:1px solid #ccc;margin-bottom:8px;overflow-wrap:anywhere' }, [
							E('p', {}, '手机自报：' + (item.nickname || '未命名') + ' · ' + (item.model || '型号未知')),
							E('p', {}, '申请时间：' + displayTime(item.created)),
							E('p', {}, '设备公钥指纹：' + fingerprint), approve, ' ', reject
						]));
					});
					(data.approved || []).forEach(function(item) {
						approvedBox.appendChild(showSavedDevice(item, false));
					});
					if (!(data.approved || []).length)
						approvedBox.appendChild(E('p', {}, '尚无已授权设备。'));
					(data.revoked || []).forEach(function(item) {
						revokedBox.appendChild(showSavedDevice(item, true));
					});
					if (!(data.revoked || []).length)
						revokedBox.appendChild(E('p', {}, '没有已取消的设备。'));
				}).catch(showJoinError);
			}
			var joinPanel = E('div', { 'class': 'cbi-section' }, [
				E('h3', {}, '扫码申请加入 · 由路由器主人批准'),
				E('p', {}, '先保存并启用上方中继；首次使用需运行 apk -U add qrencode。二维码只发送加入申请，不会自动授予访问权限，也不会打开公网防火墙。'),
				qrLabel, makeQrButton, qrBox,
				E('h4', {}, '公网 IPv6 精确端口开关'),
				E('p', {}, '只操作 OpenKinGuard 专用的 WAN → 本机 IPv6/TCP 中继端口规则，不开放管理后台、SSH 或 IPv4。开启前须确认路由器已配置 TLS 和人工审批；节点暴露公网仍存在被扫描/拒绝服务风险。'),
				E('button', { 'class': 'btn cbi-button', 'click': showFirewall }, '查看端口状态'), ' ',
				E('button', { 'class': 'btn cbi-button cbi-button-positive', 'click': function() {
					if (!window.confirm('确认将本机 OpenKinGuard 的 IPv6 TCP 端口开放给公网访问？不会开放管理端口。')) return;
					return joinAction('open-ipv6').then(showFirewall).catch(showJoinError);
				} }, '开放专用IPv6端口'), ' ',
				E('button', { 'class': 'btn cbi-button cbi-button-negative', 'click': function() {
					if (!window.confirm('确认关闭 OpenKinGuard 专用 IPv6 入站规则？已连接设备可能离线。')) return;
					return joinAction('close-ipv6').then(showFirewall).catch(showJoinError);
				} }, '关闭专用IPv6端口'),
				firewallBox,
				E('h4', {}, '待批准设备'),
				E('button', { 'class': 'btn cbi-button', 'click': refreshAll }, '刷新设备列表'),
				pendingBox,
				E('h4', {}, '已授权设备'),
				E('p', {}, '昵称与型号由手机在扫码申请时提供，仅作识别线索。真正的设备身份以你核对的公钥指纹为准。取消绑定会中断现有连接，但不会清除该手机里的家人关系和历史。'),
				approvedBox,
				E('h4', {}, '已取消设备 · 可恢复'),
				E('p', {}, '这里只保留曾经获准的设备记录。重新授权会恢复原指纹的访问资格，无须重新扫码；请先确认这仍是你信任的手机。'),
				revokedBox
			]);
			refreshAll();
			return E('div', {}, [settings, joinPanel]);
		});
	}
});
