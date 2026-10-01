package dev.openpocket.probe;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** TCP poke + UDP handshake/window ACK + media listing + HTTP download. */
final class CameraSession implements DeviceMediaSession {
    interface Listener {
        void onLog(String text);
        void onMedia(List<MediaCatalog.Item> items);
        void onProgress(String file, int percent);
        void onFailure(String text, Throwable error);
        default void onDownloadComplete(String sourcePath, Uri destination) {}
        default void onDownloadFailed(String sourcePath, String message) {}
    }
    private final CameraNetwork path;
    private final String host;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService ticks = Executors.newScheduledThreadPool(2);
    private final Object sendLock = new Object(), chunkLock = new Object();
    private final Map<Integer, ByteArrayOutputStream> chunks = new HashMap<>();
    private DatagramSocket udp;
    private Socket poke;
    private volatile boolean running, handshakeReply, initialWindow, sawEnd;
    private volatile long lastChunkAt;
    private int sessionId, baseSeq, udpSeq, dumlSeq = 0xA000, counter;
    private int videoCursor, ackedCursor, extraCursor;
    private boolean hasVideo, hasAcked, hasExtra;

    CameraSession(CameraNetwork path, String host, Listener listener) {
        this.path = path; this.host = host; this.listener = listener;
    }

    @Override public void open() { io.execute(() -> { try { openBlocking(); } catch (Exception e) { fail("数据链打开失败", e); } }); }

    private void openBlocking() throws Exception {
        listener.onLog("数据链：探测 TCP " + host + ":7001");
        poke = new Socket(); path.bind(poke); poke.connect(new InetSocketAddress(host, 7001), 2500);
        poke.getOutputStream().write(Protocol.encode(Protocol.pairing())); poke.getOutputStream().flush();
        Thread.sleep(400);

        sessionId = ThreadLocalRandom.current().nextInt(0x1000, 0xFFFE);
        baseSeq = ThreadLocalRandom.current().nextInt(0x1000, 0xF000) & 0xFFF8;
        udp = new DatagramSocket(null); udp.setReuseAddress(true); udp.setSoTimeout(250); udp.setReceiveBufferSize(512 * 1024);
        path.bind(udp); udp.bind(new InetSocketAddress("0.0.0.0", 0)); udp.connect(InetAddress.getByName(host), 9004);
        running = true; Thread rx = new Thread(this::receiveLoop, "pocket-udp-rx"); rx.setDaemon(true); rx.start();
        listener.onLog("数据链：UDP 本地临时端口 " + udp.getLocalPort() + " → 9004");
        byte[] hp = Protocol.handshakePayload(baseSeq);
        for (int i = 0; i < 20 && !(handshakeReply && initialWindow); i++) { raw(0, hp); Thread.sleep(350); }
        if (!(handshakeReply && initialWindow)) throw new IOException("未同时收到握手回复和 34 字节初始窗口");
        listener.onLog("数据链：握手成功，初始命令序列 0x" + Integer.toHexString(udpSeq));
        sendAck(); sendFrame(Protocol.deviceInfo(dumlSeq++)); sendAck();
        sendFrame(Protocol.appPresence(dumlSeq++)); sendAck(); sendFrame(Protocol.gimbalInit(dumlSeq++)); sendAck();
        ticks.scheduleWithFixedDelay(this::sendAck, 0, 25, TimeUnit.MILLISECONDS);
        ticks.scheduleWithFixedDelay(() -> sendFrame(Protocol.appPresence(nextDuml())), 1, 1, TimeUnit.SECONDS);
        listener.onLog("数据链：APP 身份注册完成，可以读取媒体");
        queryMediaBlocking();
    }

    @Override public void refreshMedia() { io.execute(() -> { try { queryMediaBlocking(); } catch (Exception e) { fail("读取媒体失败", e); } }); }
    private void queryMediaBlocking() throws Exception {
        synchronized (chunkLock) { chunks.clear(); sawEnd = false; lastChunkAt = 0; }
        listener.onLog("媒体：进入回放并请求 Pocket 3 SD 卡最新页");
        sendFrame(Protocol.enterPlayback(nextDuml())); Thread.sleep(500);
        sendFrame(Protocol.mediaList(nextDuml(), 1, 1)); collect(800, 200);
        sendFrame(Protocol.mediaTrigger(nextDuml())); collect(400, 200);
        sendFrame(Protocol.mediaList(nextDuml(), 2, 0x40000001L)); collect(800, 800);
        Map<Integer, byte[]> assembled = new HashMap<>();
        synchronized (chunkLock) { for (var e : chunks.entrySet()) assembled.put(e.getKey(), e.getValue().toByteArray()); }
        List<MediaCatalog.Item> items = MediaCatalog.decode(assembled);
        listener.onLog("媒体：解析到 " + items.size() + " 个文件"); listener.onMedia(items);
    }
    private void collect(long floor, long idle) throws InterruptedException {
        long start = System.currentTimeMillis(); long change = start; long seen = -1;
        while (System.currentTimeMillis() - start < 8000) {
            Thread.sleep(50); long total = chunkBytes();
            if (total != seen) { seen = total; change = System.currentTimeMillis(); }
            if (System.currentTimeMillis() - start >= floor && (sawEnd || System.currentTimeMillis() - change >= idle)) break;
        }
    }
    private long chunkBytes() { synchronized (chunkLock) { return chunks.values().stream().mapToLong(ByteArrayOutputStream::size).sum(); } }

    @Override public void download(MediaCatalog.Item item, ContentResolver resolver) {
        io.execute(() -> { try { downloadBlocking(item, resolver); } catch (Exception e) { fail("下载失败：" + item.filename(), e); } });
    }
    private void downloadBlocking(MediaCatalog.Item item, ContentResolver resolver) throws Exception {
        String encoded = Uri.encode(item.path(), "/");
        URL url = new URL("http://" + host + "/v2?storage=0&path=" + encoded);
        HttpURLConnection c = (HttpURLConnection) path.open(url); c.setConnectTimeout(4000); c.setReadTimeout(15000);
        int status = c.getResponseCode(); if (status != 200 && status != 206) throw new IOException("HTTP " + status);
        long total = c.getContentLengthLong();
        ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME, item.filename());
        values.put(MediaStore.Downloads.MIME_TYPE, mime(item.path()));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketMediaProbe");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri dest = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values); if (dest == null) throw new IOException("MediaStore insert failed");
        try (InputStream in = c.getInputStream(); OutputStream out = resolver.openOutputStream(dest)) {
            if (out == null) throw new IOException("output unavailable"); byte[] buf = new byte[128 * 1024]; long done = 0; int n, last = -1;
            while ((n = in.read(buf)) >= 0) { out.write(buf, 0, n); done += n; int p = total > 0 ? (int)(done * 100 / total) : 0; if (p != last) { last = p; listener.onProgress(item.filename(), p); } }
        } catch (Exception e) { resolver.delete(dest, null, null); throw e; }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(dest, values, null, null);
        listener.onProgress(item.filename(), 100); listener.onLog("下载完成：Downloads/PocketMediaProbe/" + item.filename());
    }

    private void receiveLoop() {
        byte[] buffer = new byte[65535];
        while (running) try {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length); udp.receive(packet);
            byte[] d = Arrays.copyOf(packet.getData(), packet.getLength()); if (d.length < 8) continue;
            int type = Protocol.u8(d[6]), seq = Protocol.u16(d, 4);
            if (type == 0) handshakeReply = true;
            if (type == 1 && d.length == 34) {
                if (!initialWindow) { udpSeq = (Protocol.u16(d, 8) + 8) & 0xFFFF; initialWindow = true; }
                if (!hasVideo) { videoCursor = Protocol.u16(d, 10); hasVideo = true; }
                if (!hasAcked) { ackedCursor = Protocol.u16(d, 18); hasAcked = true; }
                extraCursor = Protocol.u16(d, 26); hasExtra = true;
            } else if (type == 2) { videoCursor = seq; hasVideo = true; }
            else if (type == 3) { ackedCursor = seq; hasAcked = true; }
            for (Protocol.Frame f : Protocol.scan(d)) ingestFrame(f);
        } catch (SocketTimeoutException ignored) {} catch (Exception e) { if (running) fail("UDP 接收失败", e); }
    }
    private void ingestFrame(Protocol.Frame f) {
        if (f.cmdSet() != 0 || f.cmdId() != 0x27 || f.payload().length < 10 || Protocol.u8(f.payload()[0]) != 0x4A) return;
        int subtype = Protocol.u8(f.payload()[1]);
        synchronized (chunkLock) {
            if (subtype == 3) sawEnd = true;
            else if (subtype == 1 && f.payload().length > 10) {
                int key = Protocol.u8(f.payload()[4]);
                chunks.computeIfAbsent(key, k -> new ByteArrayOutputStream()).write(f.payload(), 10, f.payload().length - 10);
                lastChunkAt = System.currentTimeMillis();
            }
        }
    }

    private int nextDuml() { synchronized (sendLock) { int n = dumlSeq; dumlSeq = (dumlSeq + 1) & 0xFFFF; return n; } }
    private void sendFrame(Protocol.Frame f) {
        synchronized (sendLock) {
            if (udp == null || !running) return;
            try {
                byte[] frame = Protocol.encode(f); counter = (counter + 1) & 0xFF;
                byte[] route = Protocol.routingHeader(udpSeq, counter);
                write(Protocol.concat(Protocol.transportHeader(5, route.length + frame.length, sessionId, udpSeq), route, frame));
                udpSeq = (udpSeq + 8) & 0xFFFF;
            } catch (IOException e) { fail("命令发送失败", e); }
        }
    }
    private void raw(int type, byte[] payload) throws IOException {
        synchronized (sendLock) { write(Protocol.concat(Protocol.transportHeader(type, payload.length, sessionId, udpSeq), payload)); udpSeq = (udpSeq + 8) & 0xFFFF; }
    }
    private void sendAck() {
        synchronized (sendLock) {
            if (udp == null || !running) return;
            try { byte[] p = Protocol.ackPayload(hasVideo ? videoCursor : 0, hasAcked ? ackedCursor : baseSeq, hasExtra ? extraCursor : baseSeq); write(Protocol.concat(Protocol.transportHeader(4, p.length, sessionId, 0), p)); }
            catch (IOException e) { if (running) fail("ACK 发送失败", e); }
        }
    }
    private void write(byte[] bytes) throws IOException { udp.send(new DatagramPacket(bytes, bytes.length)); }
    private static String mime(String p) { String u = p.toUpperCase(Locale.US); if (u.endsWith(".JPG") || u.endsWith(".JPEG")) return "image/jpeg"; if (u.endsWith(".DNG")) return "image/x-adobe-dng"; return "video/mp4"; }
    private void fail(String text, Throwable e) { listener.onFailure(text, e); }
    @Override public void close() { running = false; ticks.shutdownNow(); io.shutdownNow(); if (udp != null) udp.close(); try { if (poke != null) poke.close(); } catch (IOException ignored) {} }
}
