# DJI Mic 3 TX 录音下载链路（实机验证）

本文描述 Android APP 连接单个 DJI Mic 3 发射器、读取录音列表并下载 WAV 文件的完整流程。它来自 DJI Mimo 2.12.1 静态分析、Android HCI BTSnoop 对比以及 Mic 3 真机验证，可作为其他项目的实现依据。

> 验证范围：DJI Mic 3 TX、Android 10+、设备 SoftAP 地址 `192.168.0.1`。固件升级可能改变部分行为，应用应保留超时、回包校验和脱敏日志。

## 1. 链路总览

```text
扫描 BLE 广播
  → 连接 FFF0 GATT 服务
  → 启用 FFF4 通知、通过 FFF5 发送 DUML
  → 初始化会话 00/2B
  → 请求设备验证 07/45
  → 用户短按 TX 配对键
  → 收到并确认 07/46
  → 读取设备信息和 SoftAP 凭据
  → 发送 5B/19 进入录音回放模式
  → 主动断开 BLE
  → Android 加入 Mic 3 SoftAP
  → GET /data/emmc0/files_info.json
  → 从 fname 还原文件 URL
  → HTTP 下载 WAV
```

Mic 3 与 Pocket 3 的 BLE 入口相近，但媒体链完全不同：

| 设备 | 媒体数据链 |
|---|---|
| Pocket 3 | TCP `7001` + UDP `9004` + DJI 媒体协议 |
| Mic 3 TX | HTTP `80`，索引为 `/data/emmc0/files_info.json` |

Mic 3 的 `192.168.0.1:7001` 返回 `ECONNREFUSED` 是正常现象，不应沿用 Pocket 3 数据链。

## 2. BLE/GATT 初始化

### 2.1 设备发现

从 BLE 广播名识别设备，真机名称示例：

```text
DJI Mic 3 TX-b5907a
```

首次连接前，让用户长按 TX 配对键约 2 秒，直到蓝灯和绿灯交替闪烁。APP 完成 `07/45` 请求后，再提示用户短按一次配对键确认。

### 2.2 GATT 特征

| 用途 | UUID |
|---|---|
| DJI 服务 | `0000fff0-0000-1000-8000-00805f9b34fb` |
| 设备通知 | `0000fff4-0000-1000-8000-00805f9b34fb` |
| APP 写入 | `0000fff5-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

推荐步骤：

1. 请求较大的 MTU（参考实现请求 512；失败不影响继续发现服务）。
2. 发现 `FFF0` 服务。
3. 为 `FFF4` 和 `FFF5` 注册通知；按 Android GATT 串行规则逐个写 CCCD。
4. 向 `FFF4` 写入 `01 00`，使设备进入可交换命令的状态。
5. 后续 DUML 帧通过 `FFF5` 使用 Write Without Response 发送，写队列间隔约 120 ms。

## 3. DUML 帧格式

所有多字节整数均为小端序。

| 偏移 | 长度 | 含义 |
|---:|---:|---|
| 0 | 1 | 固定头 `0x55` |
| 1 | 1 | 总长度低 8 位 |
| 2 | 1 | 低 2 位为总长度高位，其余位表示版本（当前为 1） |
| 3 | 1 | 头部 CRC8（字节 0..2） |
| 4 | 1 | sender |
| 5 | 1 | receiver |
| 6 | 2 | sequence |
| 8 | 1 | flags |
| 9 | 1 | command set |
| 10 | 1 | command id |
| 11 | N | payload |
| 尾部 | 2 | 整帧 CRC16（不含 CRC16 自身） |

已验证的设备地址：

```text
APP sender                  = 0x02
Mic 3 DM TX type 0x1A/index 1 = 0x3A
会话目标                    = 0xF0
```

常用 flags：

```text
0x40  请求
0x80  响应
0xC0  APP 对设备推送的确认响应
0x00  设备主动推送
```

CRC 参数可直接参考 [`Protocol.java`](../app/src/main/java/dev/openpocket/probe/Protocol.java)：

- CRC8 初值 `0x77`，反射多项式 `0x8C`。
- CRC16 初值 `0x3692`，反射多项式 `0x8408`。

## 4. 配对、验证和凭据读取

### 4.1 命令顺序

| 顺序 | 目标 | set/id | flags | payload | 作用 |
|---:|---:|---:|---:|---|---|
| 1 | `F0` | `00/2B` | `40` | `04 00` | 初始化 APP 会话 |
| 2 | `3A` | `07/45` | `40` | 两个长度前缀字符串 | 请求设备验证 |
| 3 | `3A` | `07/46` | `C0` | `00` | 确认设备按键推送 |
| 4 | `3A` | `00/01` | `40` | 空 | 读取设备信息 |
| 5 | `3A` | `00/32` | `40` | `11` | 读取版本信息 |
| 6 | `3A` | `07/07` | `40` | 空 | 读取 SoftAP SSID |
| 7 | `3A` | `07/0E` | `40` | 空 | 读取 SoftAP 密码 |
| 8 | `3A` | `07/0C` | `40` | 空 | 读取 SoftAP MAC |

`07/45` payload 是两个单字节长度前缀字符串：

```text
[len(appIdentifier)] [appIdentifier bytes]
[len(appName)]       [appName bytes]
```

Mimo 抓包中的值随安装/会话变化。参考实现使用稳定的 APP identifier 和 `osmo`；设备已实机接受。不要把 HCI 中捕获的其他用户标识当作固定协议常量。

### 4.2 按键确认的关键细节

设备收到 `07/45` 后通常响应：

```text
07/45 flags=0x80 payload=00 02
```

- `payload[0] = 0`：成功。
- `payload[1] = 2`：等待用户短按 TX 配对键。
- `payload[1] = 1`：设备已经记住此 APP，可直接继续。

用户短按后，Mic 3 推送：

```text
07/46 flags=0x00 payload=01 00
```

即使 flags 是 `0x00`，Mimo 仍会回复：

```text
sender=APP receiver=原推送 sender
sequence=原推送 sequence
07/46 flags=0xC0 payload=00
```

设备可能连续推送两次 `07/46`。每次都应 ACK，但进入下一阶段的状态转换必须幂等，只执行一次。

周期性的 `5B/04 event=0x05` 是状态流量，不代表用户按下了配对键。

### 4.3 字符串响应

`07/07` 和 `07/0E` 响应 payload 的已验证布局：

```text
byte 0  status
byte 1  UTF-8 字符串长度 N
byte 2  字符串起始
```

密码仅保存在内存中，日志只记录长度，不打印内容，不写入磁盘。

## 5. 启动录音回放/HTTP 文件服务

这是 Mic 3 与普通 DJI 相机流程的核心差异。

Mimo 原生 SDK 的 `KeyEnterAudioPlayback` 对应：

```text
receiver = 0x3A
set/id   = 5B/19
flags    = 0x40
payload  = 01 01 00 01
```

真机通过 BLE `FFF5` 发送后响应：

```text
5B/19 flags=0x80 payload=00
```

`status=0` 后设备进入录音回放模式，HTTP 文件服务开始可用。对应的退出参数为：

```text
5B/19 payload=01 01 00 00
```

Mimo 通常在 Wi-Fi 数据链建立后执行此动作；实测在 BLE 尚未断开时发送同一 DUML 命令也能成功，且能显著简化第三方实现。

取得凭据、收到 `5B/19 status=0` 后，等待约 350 ms 再主动断开 GATT。Mimo 抓包也显示它会主动断开 BLE（HCI reason `0x16 Local Host Terminated`）。断开 BLE 后继续使用 SoftAP，不要把它当作错误。

## 6. Android 加入 SoftAP

使用 `WifiNetworkSpecifier` 提交 SSID 和 WPA2 密码，并通过 `ConnectivityManager.requestNetwork()` 获取设备网络。

关键要求：

1. 等待系统确认目标网络。
2. 从 `LinkProperties` 读取实际 IPv4 地址与默认网关，不要永久写死地址。
3. 调用 `Network.openConnection(URL)` 或为 socket 显式绑定该 `Network`。
4. 不要依赖进程默认网络；手机可能同时保留蜂窝数据。
5. SoftAP 无互联网属于正常情况。

实机常见地址：

```text
phone   192.168.0.2
gateway 192.168.0.1
```

系统确认弹窗可能使 DHCP 等待 2–20 秒。获取网络后，HTTP 服务仍可能短暂未就绪；建议对连接拒绝/超时执行约 20 秒的有限重试。

参考实现：[`CameraNetwork.java`](../app/src/main/java/dev/openpocket/probe/CameraNetwork.java)。

## 7. 录音索引

请求：

```http
GET http://<gateway>/data/emmc0/files_info.json
```

已观察字段：

```text
name, files, fname, fsize, duration_sec, date
```

索引有一个容易误判的布局：

- 顶层 `name` 可能只是标签 `TX RECORD FILES INFO`，不是目录。
- `fname` 可能已经包含完整相对路径，例如：

```text
data/emmc0/TX_MIC001_20251022_101758/TX01_MIC001_20251022_101758_orig.wav
```

因此路径优先级应为：

1. 若 `fname` 包含 `/data/`，截取第一个 `/data/` 开始的部分。
2. 若 `fname` 以 `data/` 开头，在前面补 `/`。
3. 只有 `fname` 是纯文件名时，才尝试使用同层 `name` 作为目录。
4. 拒绝包含 `..`、反斜杠、查询串或 fragment 的路径，防止目录穿越。

不要构造以下错误形式：

```text
/data/emmc0/TX RECORD FILES INFO/data/emmc0/...
```

正确形式：

```text
/data/emmc0/TX_MIC001_.../TX01_MIC001_..._orig.wav
```

参考实现：[`MicMediaSession.java`](../app/src/main/java/dev/openpocket/probe/MicMediaSession.java)。

## 8. 文件下载

将规范化路径与设备基础地址组合：

```http
GET http://<gateway><normalized_fname>
```

路径的每一段必须进行 URL 编码，但保留 `/` 分隔符。接受 `200 OK`；若未来固件支持断点续传，也可接受 `206 Partial Content`。

Android 推荐通过 MediaStore 写入：

```text
Downloads/PocketMediaProbe/<filename>.wav
```

流程：

1. 插入 `MediaStore.Downloads`，设置 `IS_PENDING=1`。
2. 流式复制响应体，避免整文件进入内存。
3. 完成后设置 `IS_PENDING=0`。
4. 下载失败时删除未完成的 MediaStore 记录。

## 9. 推荐状态机

```text
IDLE
  → SCANNING
  → GATT_CONNECTING
  → GATT_READY
  → VERIFY_REQUESTED
  → WAITING_FOR_BUTTON
  → VERIFIED
  → READING_CREDENTIALS
  → ENTERING_AUDIO_PLAYBACK
  → BLE_DISCONNECTING
  → WIFI_REQUESTED
  → WIFI_BOUND
  → INDEX_LOADING
  → READY
  → DOWNLOADING
```

所有异步回调都应检查当前状态；重复的 `07/46`、重复的凭据响应和 Android 网络回调不能重复触发下一阶段。

## 10. 常见错误与判断

| 现象 | 原因/处理 |
|---|---|
| BLE `status=62/133` | 首次建链偶发失败；关闭旧 GATT，短延迟后重试一次 |
| 一直收到 `5B/04 event=05` | 这是周期状态，不是按键确认；等待 `07/46` |
| 已读到密码但设备仍蓝绿闪烁 | 尚未完成 `07/46` ACK 或未进入 `5B/19` 回放模式 |
| `192.168.0.1:7001 ECONNREFUSED` | 错用了 Pocket 3 媒体链；Mic 3 使用 HTTP 80 |
| HTTP 80 暂时拒绝连接 | 等待 `5B/19 status=0`、断开 BLE，并有限重试 |
| 索引成功但 WAV 为 404 | 错误拼接 `name/fname`；优先使用 `fname` 内嵌路径 |
| 请求走蜂窝网络 | 必须用 Android 返回的 `Network` 打开连接 |

## 11. 安全与兼容建议

- 只实现读取和下载；不要暴露未知写命令、删除、格式化或录音控制。
- 对每个 DUML 帧验证头部 CRC8、长度与整帧 CRC16。
- 限制索引大小、JSON 深度、路径长度和下载目标。
- 不打印、不持久化 Wi-Fi 密码。
- SSID、设备序列号和 MAC 在公开日志中应脱敏。
- 对未知固件保留命令回包 status，不要把“已发送”当作“已成功”。
- 离开下载页时，如果仍有可用命令链，可发送 `5B/19 01 01 00 00`；否则释放 Wi-Fi 网络请求，让设备自行退出会话。

## 12. 本项目中的参考文件

| 文件 | 内容 |
|---|---|
| [`Protocol.java`](../app/src/main/java/dev/openpocket/probe/Protocol.java) | DUML 编解码、CRC、已验证命令 |
| [`BleBootstrap.java`](../app/src/main/java/dev/openpocket/probe/BleBootstrap.java) | BLE/GATT、验证、凭据和回放模式状态机 |
| [`CameraNetwork.java`](../app/src/main/java/dev/openpocket/probe/CameraNetwork.java) | Android SoftAP 请求与网络绑定 |
| [`MicMediaSession.java`](../app/src/main/java/dev/openpocket/probe/MicMediaSession.java) | HTTP 索引解析、路径规范化和 WAV 下载 |
| [`analyze_btsnoop.py`](../tools/analyze_btsnoop.py) | BTSnoop/H4 中 ATT 与 DUML 帧提取 |
| [`ProtocolTest.java`](../app/src/test/java/dev/openpocket/probe/ProtocolTest.java) | 协议帧与路径规则测试向量 |

## 13. 最小实现伪代码

```text
connectGatt(tx)
enableNotify(FFF4, FFF5)
write(FFF4, 01 00)

sendDUML(sessionWake 00/2B, 04 00)
sendDUML(verify 07/45, packedAppIdentity)

on 07/45 mode=2:
    promptShortPress()

on 07/46 push:
    sendDUML(07/46 ACK, sameSeq, C0, 00)
    once:
        request 00/01
        request 00/32 payload 11
        request 07/07
        request 07/0E
        request 07/0C

on credentialsReady:
    sendDUML(5B/19, 01 01 00 01)
    requestAndroidWifi(ssid, password)

on 5B/19 status=0:
    delay(350 ms)
    disconnectGatt()

on wifiBound(network, gateway):
    json = network.GET("http://" + gateway + "/data/emmc0/files_info.json")
    files = parseAndNormalizeFname(json)

on download(file):
    network.GET("http://" + gateway + file.normalizedPath)
    streamToMediaStore()
```

