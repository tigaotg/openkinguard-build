# OpenKinGuard 0.9.17 使用独立公开 GitHub Actions 构建

由于开发会话的本地云端不能连接 Gradle、Android SDK、Maven 镜像，推荐在新的**公开构建专用仓库**运行本项目。这个仓库与原有私人项目完全独立。

## 必要准备

1. 在 GitHub 建立全新的公开仓库，例如 `tigaotg/openkinguard-build`。
2. 将本源码 ZIP 解压后的 `OpenKinGuard-0.9.17` **文件夹内所有内容**放到该仓库根目录，包括 `.github/workflows/build-pilot-v4.yml`。
3. 进入 GitHub 仓库的 **Settings → Secrets and variables → Actions → New repository secret**，添加两个密钥：
   - `OKG_PILOT_V4_SIGNING_PASSWORD`：从随源码单独提供的签名密码 TXT 读取。
   - `OKG_PILOT_V4_KEYSTORE_B64`：把私有 `openkinguard-pilot-v4.p12` 文件 Base64 编码后的全文。Windows PowerShell：
     `[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\openkinguard-pilot-v4.p12'))`
4. 进入 **Actions → OpenKinGuard Pilot V4 APK → Run workflow**。
5. 等待单元测试、APK 构建和 `apksigner verify` 全部通过，再下载名为 `OpenKinGuard-0.9.17-PilotV4-signed` 的 Actions Artifact ZIP，解压取得 APK。

签名私钥不得提交到仓库 Git 文件、公开 Issue、构建日志或 Actions Artifact。构建时工作流只从 GitHub Actions Secrets 读取密钥到短暂的构建机。它不会写入源码仓库。

如果构建失败，查看该次 Actions run 的报错日志，修复源码后重新运行。**尚未运行 CI 就不能宣称 APK 编译成功。**

本仓库仅为构建代码公开，不要求连接任何私人 GitHub 仓库。
