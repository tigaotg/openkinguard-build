include $(TOPDIR)/rules.mk

PKG_NAME:=openkinguard-family-relay
PKG_VERSION:=0.3.4
PKG_RELEASE:=1
PKG_LICENSE:=AGPL-3.0-only
PKG_MAINTAINER:=OpenKinGuard contributors
PKG_BUILD_DIR:=$(BUILD_DIR)/$(PKG_NAME)-$(PKG_VERSION)
PKGARCH:=all

include $(INCLUDE_DIR)/package.mk

define Package/openkinguard-family-relay
  SECTION:=net
  CATEGORY:=Network
  SUBMENU:=Web Servers/Proxies
  TITLE:=OpenKinGuard family-only WSS relay + LuCI
  DEPENDS:=+python3 +python3-websockets +python3-cryptography +luci-base +qrencode
endef

define Package/openkinguard-family-relay/description
  Private WSS relay restricted to QR-paired family device fingerprints,
  with a LuCI configuration page and procd service. Disabled by default.
endef

define Package/openkinguard-family-relay/conffiles
/etc/config/openkinguard-relay
endef

define Build/Compile
endef

define Package/openkinguard-family-relay/install
	$(CP) $(CURDIR)/plugin-files/. $(1)/
	chmod 0755 $(1)/etc/init.d/openkinguard-relay
	chmod 0755 $(1)/usr/lib/openkinguard-relay/node_identity.py
	chmod 0755 $(1)/usr/lib/openkinguard-relay/manage-join
	chmod 0600 $(1)/etc/config/openkinguard-relay
endef

define Package/openkinguard-family-relay/postinst
#!/bin/sh
if [ -z "$$IPKG_INSTROOT" ]; then
	/etc/init.d/openkinguard-relay enable
	/etc/init.d/rpcd restart
fi
exit 0
endef

$(eval $(call BuildPackage,openkinguard-family-relay))
