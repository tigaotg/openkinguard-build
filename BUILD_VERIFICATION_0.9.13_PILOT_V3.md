# OpenKinGuard 0.9.13 Pilot V3 构建验证

日期：2026-11-04

- `:core:engine:test :security:protocol:test :app:testDebugUnitTest :app:assemblePilotV3`：全部成功。
- `versionCode=48`，`versionName=0.9.13-map-handoff-links-pilot-v3`。
- 应用 ID：`io.github.tigaotg.openkinguard.debug.pilotv3`；最低 Android 10。
- APK 签名方案 v2 校验成功；与 0.9.12 Pilot V3 的签名证书 SHA-256 完全一致：`d100a7eee2e1765aa7e9292304f1d7e536569cd8f713218f5279f8c207bba624`。
- APK SHA-256：`773dd1e3e0e8817f8e9c355ab38e90a3c7cc6abf09859167b56edd1a271a7e67`。
- 测试覆盖 Google `纬度,经度` WGS84 链接，高德 `经度,纬度` WGS84 网页链接，高德原生 `dev=1` 与百度原生 `coord_type=wgs84` 参数。

源码归档不包含私有签名证书、签名密码、Gradle 缓存或编译产物。该 APK 只支持同包名、同证书的 Pilot V3 安装渠道覆盖升级。Android 10/17 真实地图应用唤起、地图标点精度和联网互通仍待实机测试。
