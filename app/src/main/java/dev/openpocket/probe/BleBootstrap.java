package dev.openpocket.probe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;

import java.util.*;

/** BLE half of the chain: discover -> pair -> wake AP -> retrieve SSID/password. */
final class BleBootstrap implements AutoCloseable {
    interface Listener {
        void onLog(String message);
        void onDevice(BluetoothDevice device, String name, int rssi);
        void onCredentials(String ssid, char[] password, boolean mic3);
        void onFailure(String message, Throwable error);
    }

    private static final UUID SERVICE = uuid("fff0"), NOTIFY = uuid("fff4"), WRITE = uuid("fff5");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final HandlerThread thread = new HandlerThread("pocket-ble");
    private final Handler worker;
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notify, write;
    private int setupStage;
    private boolean writing, credentialsStarted, credentialsDelivered, servicesInitialized, mic3, connectedOnce;
    private boolean audioPlaybackRequested;
    private boolean intentionalDisconnect;
    private int connectRetries, tracePackets, micEventCount;
    private Integer lastMicEvent;
    private final Set<String> micShapes = new HashSet<>();
    private BluetoothDevice connectingDevice;
    private String ssid;

    BleBootstrap(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        thread.start(); worker = new Handler(thread.getLooper());
    }

    @SuppressLint("MissingPermission")
    void startScan() {
        if (adapter == null || !adapter.isEnabled()) { fail("请先打开蓝牙", null); return; }
        listener.onLog("BLE：扫描 DJI / Osmo 设备（设备名来自广播）");
        adapter.getBluetoothLeScanner().startScan(null,
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
    }

    @SuppressLint("MissingPermission")
    void connect(BluetoothDevice device) {
        stopScan(); connectingDevice = device; connectRetries = 0; tracePackets = 0;
        micEventCount = 0; lastMicEvent = null; micShapes.clear();
        credentialsStarted = false; credentialsDelivered = false; intentionalDisconnect = false;
        audioPlaybackRequested = false; ssid = null;
        String deviceName = safeName(device);
        mic3 = deviceName != null && deviceName.toLowerCase(Locale.US).contains("mic 3");
        listener.onLog("BLE：连接 " + deviceName);
        if (mic3) listener.onLog("Mic 3：请先长按 TX 配对键约 2 秒，确认蓝绿灯交替闪烁");
        worker.post(() -> connectGatt(device));
    }

    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice device) {
        servicesInitialized = false; connectedOnce = false;
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    void stopScan() {
        if (adapter != null && adapter.getBluetoothLeScanner() != null)
            try { adapter.getBluetoothLeScanner().stopScan(scan); } catch (Exception ignored) {}
    }

    private final ScanCallback scan = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) name = safeName(result.getDevice());
            String n = name == null ? "" : name.toLowerCase(Locale.US);
            if (n.contains("dji") || n.contains("osmo") || n.contains("pocket") || n.contains("xtra"))
                listener.onDevice(result.getDevice(), name == null ? "DJI camera" : name, result.getRssi());
        }
        @Override public void onScanFailed(int code) { fail("BLE 扫描失败：" + code, null); }
    };

    @SuppressLint("MissingPermission")
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
            worker.post(() -> {
                if (intentionalDisconnect && state == BluetoothProfile.STATE_DISCONNECTED) {
                    try { source.close(); } catch (Exception ignored) {}
                    if (gatt == source) gatt = null;
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!connectedOnce && connectRetries < 1 && (status == 62 || status == 133) && connectingDevice != null) {
                        connectRetries += 1;
                        listener.onLog("BLE：首次建链失败 status=" + status + "，1.2 秒后自动重试一次");
                        try { source.close(); } catch (Exception ignored) {}
                        worker.postDelayed(() -> connectGatt(connectingDevice), 1200);
                        return;
                    }
                    fail("BLE 连接断开（status=" + status + "）", null); return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedOnce = true;
                    listener.onLog("BLE：GATT 已连接，发现 FFF0 服务");
                    if (!source.requestMtu(512)) source.discoverServices();
                    worker.postDelayed(source::discoverServices, 1200);
                }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt source, int mtu, int status) {
            worker.post(source::discoverServices);
        }
        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            worker.post(() -> {
                if (servicesInitialized) return;
                BluetoothGattService service = source.getService(SERVICE);
                if (status != BluetoothGatt.GATT_SUCCESS || service == null) { fail("设备没有 FFF0 DUML 服务", null); return; }
                servicesInitialized = true;
                if (mic3) logGattInventory(source);
                notify = service.getCharacteristic(NOTIFY); write = service.getCharacteristic(WRITE);
                if (notify == null || write == null) { fail("缺少 FFF4/FFF5 特征", null); return; }
                setupStage = 0; enableNotify(notify);
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor d, int status) {
            worker.post(() -> {
                if (setupStage == 0) { setupStage = 1; enableNotify(write); }
                else if (setupStage == 1) { setupStage = 2; armPairing(); }
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic c, int status) {
            worker.post(() -> {
                if (c.getUuid().equals(NOTIFY) && setupStage == 2) {
                    setupStage = 3;
                    if (mic3) {
                        listener.onLog("BLE：FFF4/FFF5 已就绪，发送 Mic 3 设备验证请求");
                        listener.onLog("Mic 3：按 Mimo 顺序发送会话初始化 00/2B");
                        send(Protocol.sessionWake());
                        listener.onLog("Mic 3：目标 DM TX(type=0x1A,index=1)，命令 07/45");
                        send(Protocol.mic3Verification());
                        listener.onLog("Mic 3：现在请短按一次 TX 配对键，确认 APP 连接");
                        worker.postDelayed(() -> {
                            if (!credentialsStarted)
                                listener.onLog("Mic 3：尚未收到验证完成通知；请确认短按发生在本提示之后");
                        }, 12000);
                        return;
                    }
                    listener.onLog("BLE：FFF4/FFF5 已就绪，开始 Pocket 3 配对");
                    send(Protocol.sessionWake()); send(Protocol.pairing());
                }
            });
        }
        @Override @Deprecated public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue(); if (value != null) worker.post(() -> ingest(value.clone()));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] value) {
            worker.post(() -> ingest(value.clone()));
        }
    };

    @SuppressLint("MissingPermission")
    private void enableNotify(BluetoothGattCharacteristic characteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) { fail("注册 BLE 通知失败", null); return; }
        BluetoothGattDescriptor d = characteristic.getDescriptor(CCCD);
        if (d == null) { if (setupStage++ == 0) enableNotify(write); else armPairing(); return; }
        if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); gatt.writeDescriptor(d); }
    }

    @SuppressLint("MissingPermission")
    private void armPairing() {
        byte[] value = Protocol.bytes(1, 0);
        if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(notify, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        else { notify.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); notify.setValue(value); gatt.writeCharacteristic(notify); }
    }

    private void ingest(byte[] raw) {
        boolean recognizedDuml = false;
        for (Protocol.Frame f : Protocol.scan(raw)) {
            recognizedDuml = true;
            if (f.cmdSet() == 7 && f.cmdId() == 0x46 && (f.flags() & 0x80) == 0) {
                if (mic3) {
                    listener.onLog(String.format(Locale.US,
                            "Mic 3：收到按键确认通知 07/46 flags=0x%02X，验证已通过", f.flags()));
                    // Captured Mimo traffic ACKs Mic 3's flags=0x00 push as well as the duplicate push.
                    send(Protocol.approvalAck(f.sender(), f.seq()));
                } else {
                    listener.onLog("BLE：相机端已批准配对"); send(Protocol.approvalAck(f.seq()));
                }
                startCredentials();
            } else if (f.cmdSet() == 7 && f.cmdId() == 0x45) {
                int mode = f.payload().length > 1 ? Protocol.u8(f.payload()[1]) : -1;
                int status = f.payload().length > 0 ? Protocol.u8(f.payload()[0]) : -1;
                if (mode == 1) {
                    listener.onLog(mic3 ? "Mic 3：设备已记住本 APP" : "BLE：设备已记住本 APP");
                    startCredentials();
                } else if (mode == 2) {
                    listener.onLog(mic3 ? "Mic 3：验证请求已送达，请短按 TX 配对键" : "BLE：请在 Pocket 3 屏幕上点“接受”");
                } else if (mic3) {
                    listener.onLog(String.format(Locale.US,
                            "Mic 3：07/45 回包 status=0x%02X mode=%d，继续等待按键确认", status, mode));
                }
            } else if (f.cmdSet() == 7 && f.cmdId() == 7) {
                String value = Protocol.statusString(f.payload());
                if (!value.isEmpty()) { ssid = value; listener.onLog("BLE：读取到热点名 " + value); sendPasswordRequest(); }
                else worker.postDelayed(this::sendSsidRequest, 500);
            } else if (f.cmdSet() == 7 && f.cmdId() == 0x0E) {
                String value = Protocol.statusString(f.payload());
                if (!value.isEmpty() && ssid != null) {
                    if (!credentialsDelivered) {
                        credentialsDelivered = true;
                        listener.onLog("BLE：读取到 Wi-Fi 密码（已脱敏，长度 " + value.length() + "）");
                        if (mic3) {
                            audioPlaybackRequested = true;
                            listener.onLog("Mic 3：发送 5B/19 进入录音回放模式（01 01 00 01）");
                            send(Protocol.enterMic3AudioPlayback());
                        }
                        listener.onCredentials(ssid, value.toCharArray(), mic3);
                        if (mic3) worker.postDelayed(this::finishMic3BleSession, 2500);
                    }
                } else worker.postDelayed(this::sendPasswordRequest, 500);
            } else if (mic3 && f.cmdSet() == 7 && f.cmdId() == 0x0C) {
                listener.onLog("Mic 3：已读取 SoftAP MAC（已脱敏）");
            } else if (mic3 && f.cmdSet() == 0 && (f.cmdId() == 0x01 || f.cmdId() == 0x32)) {
                listener.onLog(String.format(Locale.US,
                        "Mic 3：初始化回包 00/%02X payload=%d", f.cmdId(), f.payload().length));
            } else if (mic3 && f.cmdSet() == 0x5B && f.cmdId() == 0x19) {
                int status = f.payload().length > 0 ? Protocol.u8(f.payload()[0]) : -1;
                listener.onLog(String.format(Locale.US,
                        "Mic 3：5B/19 录音回放回包 flags=0x%02X status=%s payload=%d",
                        f.flags(), status < 0 ? "--" : String.format(Locale.US, "0x%02X", status),
                        f.payload().length));
                if (audioPlaybackRequested && status == 0)
                    worker.postDelayed(this::finishMic3BleSession, 350);
            } else if (mic3 && f.cmdSet() == 7) {
                int status = f.payload().length > 0 ? Protocol.u8(f.payload()[0]) : -1;
                listener.onLog(String.format(Locale.US,
                        "Mic 3 DUML：07/%02X flags=0x%02X payload=%d status=%s",
                        f.cmdId(), f.flags(), f.payload().length,
                        status < 0 ? "--" : String.format(Locale.US, "0x%02X", status)));
            }
        }
        if (mic3 && (!recognizedDuml || Protocol.scan(raw).stream().noneMatch(f -> f.cmdSet() == 7)))
            traceMicPacket(raw);
    }

    private void startCredentials() {
        if (credentialsStarted) return; credentialsStarted = true;
        if (mic3) {
            listener.onLog("Mic 3：验证完成；按 Mimo 顺序读取设备信息及 SoftAP 参数");
            send(Protocol.mic3DeviceInfo());
            send(Protocol.mic3VersionInfo());
            sendSsidRequest(); sendPasswordRequest(); send(Protocol.getMic3Mac());
        } else {
            listener.onLog("BLE：发送 53/10，要求相机开启 SoftAP");
            send(Protocol.wakeAp()); worker.postDelayed(this::sendSsidRequest, 800);
            worker.postDelayed(keepalive, 1000);
        }
    }
    private void sendSsidRequest() { send(mic3 ? Protocol.getMic3Ssid() : Protocol.getSsid()); }
    private void sendPasswordRequest() { send(mic3 ? Protocol.getMic3Password() : Protocol.getPassword()); }
    @SuppressLint("MissingPermission")
    private void finishMic3BleSession() {
        BluetoothGatt source = gatt;
        if (!mic3 || source == null || intentionalDisconnect) return;
        intentionalDisconnect = true;
        listener.onLog("Mic 3：SoftAP 参数已取得，按 Mimo 流程主动断开 BLE 以退出配对态");
        writes.clear(); writing = false;
        try {
            source.disconnect();
            worker.postDelayed(() -> {
                try { source.close(); } catch (Exception ignored) {}
                if (gatt == source) gatt = null;
            }, 800);
        } catch (Exception e) {
            try { source.close(); } catch (Exception ignored) {}
            if (gatt == source) gatt = null;
        }
    }
    private final Runnable keepalive = new Runnable() {
        @Override public void run() { if (gatt != null) { send(Protocol.sessionKeepalive()); worker.postDelayed(this, 1000); } }
    };

    private void send(Protocol.Frame frame) { enqueue(Protocol.encode(frame)); }
    private void enqueue(byte[] bytes) { worker.post(() -> { writes.add(bytes); pump(); }); }
    @SuppressLint("MissingPermission") private void pump() {
        if (writing || writes.isEmpty() || gatt == null || write == null) return;
        writing = true; byte[] value = writes.remove();
        try {
            if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(write, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            else { write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE); write.setValue(value); gatt.writeCharacteristic(write); }
        } catch (Exception e) { fail("BLE 写入失败", e); }
        worker.postDelayed(() -> { writing = false; pump(); }, 120);
    }

    private void fail(String message, Throwable error) { listener.onFailure(message, error); }

    @SuppressLint("MissingPermission")
    private void logGattInventory(BluetoothGatt source) {
        for (BluetoothGattService service : source.getServices()) {
            StringBuilder line = new StringBuilder("Mic 3 GATT：service ").append(shortUuid(service.getUuid())).append(" chars=");
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                line.append(shortUuid(c.getUuid())).append("[").append(Integer.toHexString(c.getProperties())).append("] ");
            }
            listener.onLog(line.toString().trim());
        }
    }

    private void traceMicPacket(byte[] raw) {
        tracePackets += 1;
        MicProtocol.Packet p = MicProtocol.parse(raw);
        if (p == null) {
            if (tracePackets <= 5) listener.onLog("Mic 3 RX：收到无法识别的通知 len=" + raw.length + "（内容不显示）");
            return;
        }
        if (p.family() == 0x5B && p.opcode() == 0x04 && p.bodyLength() == 1) {
            micEventCount += 1;
            boolean changed = lastMicEvent == null || lastMicEvent != p.firstBodyByte();
            if (changed || micEventCount <= 3 || micEventCount % 10 == 0) {
                listener.onLog(String.format(Locale.US,
                        "Mic 3 事件：5B/04 event=0x%02X%s（计数 %d）",
                        p.firstBodyByte(), changed && lastMicEvent != null ? "，值发生变化" : "", micEventCount));
            }
            lastMicEvent = p.firstBodyByte();
            return;
        }
        String shape = String.format(Locale.US, "%02X/%02X-%d-%02X-%s-%s",
                p.family(), p.opcode(), p.length(), p.firstBodyByte(), p.dialect(), p.crcValid());
        if (micShapes.add(shape)) {
            listener.onLog(String.format(Locale.US,
                    "Mic 3 状态形态：set/id=%02X/%02X len=%d body=%d marker=0x%02X dialect=%s CRC=%s",
                    p.family(), p.opcode(), p.length(), p.bodyLength(), p.firstBodyByte(),
                    p.dialect(), p.crcValid() ? "ok" : "非 v2"));
        }
    }

    private static String shortUuid(UUID id) {
        String s = id.toString().toLowerCase(Locale.US);
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) return s.substring(4, 8);
        return s;
    }
    @SuppressLint("MissingPermission") private static String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (SecurityException e) { return null; }
    }
    private static UUID uuid(String shortId) { return UUID.fromString("0000" + shortId + "-0000-1000-8000-00805f9b34fb"); }
    @Override @SuppressLint("MissingPermission") public void close() {
        stopScan(); worker.removeCallbacksAndMessages(null);
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {} gatt = null; }
        thread.quitSafely();
    }
}
