#!/bin/sh
# Safe opt-in installer for an unpacked release on an OpenWrt router.
# Does not install dependencies, open firewall ports, or enable public access.
set -eu

PACKAGE_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
SOURCE_DIR="$PACKAGE_DIR/plugin-files"

check_router() {
	[ -f /etc/openwrt_release ] || { echo '错误：只能在 OpenWrt 路由器上安装。' >&2; return 1; }
	command -v uci >/dev/null 2>&1 || { echo '错误：找不到 UCI。' >&2; return 1; }
	command -v python3 >/dev/null 2>&1 || {
		echo '缺少 Python 3；请先执行：apk -U add python3 python3-cryptography python3-websockets' >&2
		return 1
	}
	python3 -c 'import cryptography; from websockets.legacy.server import serve' || {
		echo '缺少 OpenWrt Python 依赖，请先安装 python3-cryptography 和 python3-websockets。' >&2
		return 1
	}
	command -v apk >/dev/null 2>&1 || {
		echo '提示：此安装包针对 apk 管理的 OpenWrt 25.12+ 构建。' >&2
		return 1
	}
	echo '预检查通过：Python、加密库、兼容 WebSocket 库、OpenWrt apk 可用。'
	uname -m
	df -h /overlay
}

install_file() {
	src="$1"
	dst="$2"
	mode="$3"
	base="${dst%/*}"
	mkdir -p "$base"
	if [ -e "$dst" ] && ! cmp -s "$src" "$dst"; then
		backup="$dst.bak.$(date +%Y%m%d%H%M%S)"
		cp -p "$dst" "$backup"
		echo "已备份原文件：$backup"
	fi
	# OpenWrt's minimal BusyBox image may not include the `install` utility.
	# `cp` + `chmod` are available in standard OpenWrt base images.
	cp "$src" "$dst"
	chmod "$mode" "$dst"
}

case "${1:---check}" in
	--check)
		check_router
		;;
	--install)
		check_router
		install_file "$SOURCE_DIR/usr/lib/openkinguard-relay/VERSION" \
			/usr/lib/openkinguard-relay/VERSION 0644
		install_file "$SOURCE_DIR/usr/lib/openkinguard-relay/relay.py" \
			/usr/lib/openkinguard-relay/relay.py 0644
		install_file "$SOURCE_DIR/usr/lib/openkinguard-relay/node_identity.py" \
			/usr/lib/openkinguard-relay/node_identity.py 0755
		install_file "$SOURCE_DIR/usr/lib/openkinguard-relay/enrollment.py" \
			/usr/lib/openkinguard-relay/enrollment.py 0644
		install_file "$SOURCE_DIR/usr/lib/openkinguard-relay/device_registry.py" \
			/usr/lib/openkinguard-relay/device_registry.py 0644
		install_file "$SOURCE_DIR/usr/lib/openkinguard-relay/manage_join.py" \
			/usr/lib/openkinguard-relay/manage_join.py 0644
		install_file "$SOURCE_DIR/usr/lib/openkinguard-relay/manage-join" \
			/usr/lib/openkinguard-relay/manage-join 0755
		install_file "$SOURCE_DIR/etc/init.d/openkinguard-relay" \
			/etc/init.d/openkinguard-relay 0755
		install_file "$SOURCE_DIR/www/luci-static/resources/view/services/openkinguard-relay.js" \
			/www/luci-static/resources/view/services/openkinguard-relay.js 0644
		install_file "$SOURCE_DIR/usr/share/luci/menu.d/luci-app-openkinguard-relay.json" \
			/usr/share/luci/menu.d/luci-app-openkinguard-relay.json 0644
		install_file "$SOURCE_DIR/usr/share/rpcd/acl.d/luci-app-openkinguard-relay.json" \
			/usr/share/rpcd/acl.d/luci-app-openkinguard-relay.json 0644
		if [ ! -e /etc/config/openkinguard-relay ]; then
			install_file "$SOURCE_DIR/etc/config/openkinguard-relay" \
				/etc/config/openkinguard-relay 0600
		else
			echo '检测到原有 /etc/config/openkinguard-relay，完整保留现有设置。'
		fi
		/etc/init.d/openkinguard-relay enable
		/etc/init.d/rpcd restart
		/etc/init.d/uhttpd reload || :
		/etc/init.d/openkinguard-relay restart
		echo "安装完成。家庭中继插件版本：$(cat /usr/lib/openkinguard-relay/VERSION 2>/dev/null || echo unknown)"
		echo '服务启用状态由 /etc/config/openkinguard-relay 决定；WAN 防火墙未改动。'
		echo 'LuCI → 服务 → OpenKinGuard 家庭中继。'
		echo '免域名家庭节点可执行：sh /tmp/openwrt-family-relay/install-on-openwrt.sh --setup-no-domain'
		echo '扫码加入需额外安装 QR 生成器：apk -U add qrencode；不会自动开放 WAN。'
		;;
	--setup-no-domain)
		check_router
		[ -f /usr/lib/openkinguard-relay/node_identity.py ] || {
			echo '请先执行 --install 更新家庭中继插件。' >&2
			exit 1
		}
		python3 /usr/lib/openkinguard-relay/node_identity.py --show-url
		echo '此步骤不会开启服务、打开防火墙或修改网络。'
		;;
	*)
		echo '用法：sh install-on-openwrt.sh --check | --install | --setup-no-domain' >&2
		exit 2
		;;
esac
