# OpenKinGuard S1 诊断版签名固定与恢复

## 目标与适用范围

2026-10-30 两部手机真机测试通过的应用包名为：

`io.github.tigaotg.openkinguard.debug.s1diagnostic`

其签名证书 SHA-256（去掉冒号、小写）为：

`f64fe8a23261ebd81aae60f5c37d7f244550d421b016bc2cc891467aff65134f`

**本备份只对应这个已经安装的 S1 0.1.1 独立诊断版。**
更早期 `io.github.tigaotg.openkinguard.debug.s1` 的签名证书不同且私钥未恢复；本备份不能让新包覆盖那个旧包。

## 私钥存储

原始 Keystore 从当前可验证的 Android Debug KeyStore 获取，已通过证书指纹验证与现有 APK 完全一致，并以 AES-256-CBC + PBKDF2-HMAC-SHA256（200000 次迭代）加密备份，备份文件：

`OpenKinGuard_S1_诊断版签名私钥_AES256加密备份_2026-10-30.enc`

加密文件的解密口令**不会写入本仓库、APK、备份文件或本说明**，必须由所有者另行保存。

Android 默认调试密钥别名为 `androiddebugkey`，Keystore / 密钥口令均为 `android`。这是调试密钥的默认口令，不是备份文件的加密口令，不适合作为正式发布签名。

## 在 Windows 11 WSL Ubuntu 上恢复

1. 下载加密备份及本源码。把备份放在源码仓库的父目录或任意安全位置。
2. 进入项目根目录（含 `gradlew` 的文件夹）：

```bash
mkdir -p private-signing
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
  -in '/path/to/OpenKinGuard_S1_诊断版签名私钥_AES256加密备份_2026-10-30.enc' \
  -out private-signing/openkinguard-s1-diagnostic-debug.keystore
chmod 600 private-signing/openkinguard-s1-diagnostic-debug.keystore
```

OpenSSL 会提示输入备份解密密码；输入用户另行保存的那一串随机密码。

3. 校验签名证书是否与现有安装包一致：

```bash
keytool -list -v \
  -keystore private-signing/openkinguard-s1-diagnostic-debug.keystore \
  -storepass android -alias androiddebugkey
```

必须核对 SHA-256 为 `F6:4F:E8:A2:32:61:EB:D8:1A:AE:60:F5:C3:7D:7F:24:45:50:D4:21:B0:16:BC:2C:C8:91:46:7A:FF:65:13:4F`。

4. 安装 JDK 17 与 Android SDK，运行：

```bash
./gradlew :app:assembleDebug
```

`app/build.gradle.kts` 指向这把固定的 Keystore。**如果文件缺失，Android Gradle Plugin 将报错，不应自动换一把新的签名密钥。**

5. 构建后验证 APK 签名证书，再对原 `s1diagnostic` 包做覆盖安装：

```bash
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

## 安全事项

- **不要将原始 `.keystore`、加密备份的密码或包含私钥的目录提交到公开 GitHub 仓库。** `.gitignore` 已忽略 `private-signing/` 和 `*.keystore`。
- 不要把解密密码发给其他人或粘贴进公开 Issue。将加密文件与密码分别备份，并存放在不同位置。
- 将来发布公益版应生成独立的、由项目所有者控制且有离线备份的正式发布签名密钥，不应把调试签名当正式版签名。
- 签名固定只解决 **Android APK 覆盖升级的发布身份一致性**，不会替代应用内 Android Keystore 的设备身份、公钥扫码授权与消息签名。
