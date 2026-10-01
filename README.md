# Mic3Sync

一款面向 DJI Mic 3 TX 的原生 Android 增量下载工具。它通过已经验证的 BLE → SoftAP → HTTP 链路读取录音，只下载新增文件，并通过 Android 标准分享面板把音频交给闪念贝壳、网盘、剪辑软件或其他应用。

不依赖 DJI SDK、网页、WebView、Web Bluetooth 或远程服务。

## 功能

- 扫描广播名中的 DJI Mic 3 TX，并记住上次连接的设备；
- 完成设备验证后读取 SoftAP 名称和密码；
- 自动进入录音回放模式并读取 `files_info.json`；
- 使用 Mic 3 内部完整路径判断增量，只下载新增录音；
- 默认保存到 `Downloads/Mic3AutoSync/`，也可以选择自定义目录；
- 下载完成后退出设备热点，通过 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 打开系统分享面板；
- 下载失败不会写入同步历史，下次连接会自动重试；
- Wi-Fi 密码只在内存中短暂使用，不显示、不记录、不持久化。

## 使用方法

1. 安装 APK，并授予“附近设备”权限。
2. 长按 TX 配对键约 2 秒，等待蓝绿灯交替闪烁。
3. 第一次使用时点击扫描结果中的 Mic 3；以后扫描到同一设备会自动连接。
4. 应用提示后短按一次 TX 配对键。
5. 在 Android 弹窗中确认加入 Mic 3 热点。
6. 等待应用读取索引并下载新增录音。
7. 在系统分享面板中选择闪念贝壳或其他目标应用。

默认开启“下载完成后自动打开分享面板”。关闭后，可以点击“分享本次新增录音”手动打开。

## 自动化边界

Mic 3 的设备验证和 Android 的 WPA SoftAP 授权属于设备与系统安全确认，第三方应用无法静默绕过。因此首次连接或系统要求重新授权时，仍需要短按 TX 按键并确认 Wi-Fi 弹窗。

目标应用是否接受多条音频，以及导入后如何处理，由目标应用自己的分享接口决定。

## 核心链路

1. BLE 扫描 `Mic 3` 广播，连接 `FFF0` 服务并订阅 `FFF4/FFF5`；
2. 发送 `00/2B` 会话初始化和面向 `DM TX(type=0x1A,index=1)` 的 `07/45`；
3. 收到 `07/46` 后发送对应 ACK；
4. 读取 `07/07` SSID、`07/0E` 密码、`07/0C` MAC；
5. 发送 `5B/19`、payload `01 01 00 01` 进入录音回放模式；
6. 使用 `WifiNetworkSpecifier` 加入 SoftAP，并取得 DHCP 网关；
7. 请求 `/data/emmc0/files_info.json`；
8. 按索引中的完整 `/data/emmc0/...` 路径下载音频；
9. 写入本机目录并记录已完成路径；
10. 退出 SoftAP，通过 Android Sharesheet 分享本次新增文件。

完整协议记录见 [DJI_MIC3_DOWNLOAD_PROTOCOL.md](docs/DJI_MIC3_DOWNLOAD_PROTOCOL.md)。

## 构建

要求：JDK 17、Android SDK 36。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 会执行同样的单元测试、Lint 和 APK 构建，并上传 Debug APK artifact。

## 安全范围

本项目只读取录音索引并下载文件，不包含删除、格式化、远程录音或 TX/RX 无线配对操作。项目采用 [Apache License 2.0](LICENSE)。
