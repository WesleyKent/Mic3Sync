package dev.openpocket.probe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure byte-level DJI DUML + UDP transport helpers. No Android dependencies. */
final class Protocol {
    static final int REQUEST = 0x40;
    static final int RESPONSE = 0xC0;
    static final int SENDER_APP = 0x02;
    static final int RX_CAMERA = 0x01;
    static final int RX_WIFI = 0x07;
    // DUML device type 0x1A, receiver index 1 packed into the upper three bits.
    static final int RX_DM_TX_1 = 0x3A;
    static final int RX_SESSION = 0xF0;
    static final int RX_WAKE = 0x1C;
    static final String IDENTIFIER = "284ae5b8d76b3375a04a6417ad71bea3";

    record Frame(int sender, int receiver, int seq, int flags, int cmdSet, int cmdId, byte[] payload) {}

    static byte[] encode(Frame f) {
        int total = 13 + f.payload.length;
        byte[] out = new byte[total];
        out[0] = 0x55;
        out[1] = (byte) total;
        out[2] = (byte) (0x04 | ((total >>> 8) & 0x03));
        out[3] = (byte) crc8(out, 0, 3);
        out[4] = (byte) f.sender;
        out[5] = (byte) f.receiver;
        put16(out, 6, f.seq);
        out[8] = (byte) f.flags;
        out[9] = (byte) f.cmdSet;
        out[10] = (byte) f.cmdId;
        System.arraycopy(f.payload, 0, out, 11, f.payload.length);
        put16(out, total - 2, crc16(out, 0, total - 2));
        return out;
    }

    static List<Frame> scan(byte[] raw) {
        List<Frame> out = new ArrayList<>();
        for (int i = 0; i + 13 <= raw.length; i++) {
            if (u8(raw[i]) != 0x55) continue;
            int total = u8(raw[i + 1]) | ((u8(raw[i + 2]) & 3) << 8);
            if ((u8(raw[i + 2]) >>> 2) != 1 || total < 13 || i + total > raw.length) continue;
            if (crc8(raw, i, 3) != u8(raw[i + 3])) continue;
            if (crc16(raw, i, total - 2) != u16(raw, i + total - 2)) continue;
            out.add(new Frame(u8(raw[i + 4]), u8(raw[i + 5]), u16(raw, i + 6),
                    u8(raw[i + 8]), u8(raw[i + 9]), u8(raw[i + 10]),
                    Arrays.copyOfRange(raw, i + 11, i + total - 2)));
        }
        return out;
    }

    static Frame command(int receiver, int seq, int flags, int set, int id, byte[] payload) {
        return new Frame(SENDER_APP, receiver, seq, flags, set, id, payload);
    }

    static Frame sessionWake() { return command(RX_SESSION, 0x802B, REQUEST, 0, 0x2B, bytes(4, 0)); }
    static Frame sessionKeepalive() { return command(RX_SESSION, 0x802B, REQUEST, 0, 0x2B, bytes(1, 1)); }
    static Frame pairing() {
        return command(RX_WIFI, 0x8092, REQUEST, 7, 0x45,
                concat(pack(IDENTIFIER), pack("osmo")));
    }
    static Frame mic3Verification() {
        return command(RX_DM_TX_1, 0x8092, REQUEST, 7, 0x45,
                concat(pack(IDENTIFIER), pack("osmo")));
    }
    static Frame approvalAck(int receiver, int seq) { return command(receiver, seq, RESPONSE, 7, 0x46, bytes(0)); }
    static Frame approvalAck(int seq) { return approvalAck(RX_WIFI, seq); }
    static Frame mic3DeviceInfo() { return command(RX_DM_TX_1, 0x8001, REQUEST, 0, 0x01, new byte[0]); }
    static Frame mic3VersionInfo() { return command(RX_DM_TX_1, 0x8032, REQUEST, 0, 0x32, bytes(0x11)); }
    static Frame enterMic3AudioPlayback() {
        return command(RX_DM_TX_1, 0x8019, REQUEST, 0x5B, 0x19, bytes(1, 1, 0, 1));
    }
    static Frame exitMic3AudioPlayback() {
        return command(RX_DM_TX_1, 0x8019, REQUEST, 0x5B, 0x19, bytes(1, 1, 0, 0));
    }
    static Frame wakeAp() { return command(RX_WAKE, 0x8053, REQUEST, 0x53, 0x10, bytes(0,0,0,0)); }
    static Frame getSsid() { return command(RX_WIFI, 0x8007, REQUEST, 7, 7, new byte[0]); }
    static Frame getPassword() { return command(RX_WIFI, 0x800E, REQUEST, 7, 0x0E, new byte[0]); }
    static Frame getMic3Ssid() { return command(RX_DM_TX_1, 0x8007, REQUEST, 7, 7, new byte[0]); }
    static Frame getMic3Password() { return command(RX_DM_TX_1, 0x800E, REQUEST, 7, 0x0E, new byte[0]); }
    static Frame getMic3Mac() { return command(RX_DM_TX_1, 0x800C, REQUEST, 7, 0x0C, new byte[0]); }

    static String statusString(byte[] p) {
        if (p.length < 2) return "";
        int n = Math.min(u8(p[1]), p.length - 2);
        return new String(p, 2, n, StandardCharsets.UTF_8);
    }

    static byte[] transportHeader(int type, int payloadLen, int session, int seq) {
        int total = 8 + payloadLen;
        byte[] b = new byte[8];
        put16(b, 0, 0x8000 | (total & 0x3FFF));
        put16(b, 2, session);
        put16(b, 4, seq);
        b[6] = (byte) type;
        int x = 0; for (int i = 0; i < 7; i++) x ^= u8(b[i]);
        b[7] = (byte) x;
        return b;
    }

    static byte[] handshakePayload(int baseSeq) {
        byte[] p = bytes(0,0,0x64,0,0x64,0,0xC0,5,0x14,0,0,0x64,0,0,1,0x90,
                1,0xC0,5,0x14,0,0,0x64,0,0x14,0,0x64,0,0xC0,5,0x14,0,0,0x64,0,1,1,4,1,2);
        put16(p, 0, baseSeq);
        return p;
    }

    static byte[] routingHeader(int seq, int counter) {
        byte[] b = new byte[12];
        put16(b, 0, (seq - 8) & 0xFFFF);
        put16(b, 2, seq);
        b[8] = (byte) counter;
        b[9] = 1;
        return b;
    }

    static byte[] ackPayload(int video, int acked, int extra) {
        return concat(group(video), group(acked), group(extra), bytes(0, 0));
    }
    private static byte[] group(int v) {
        return bytes(v, v >>> 8, v, v >>> 8, 0, 0, 0, 0);
    }

    static Frame deviceInfo(int seq) {
        byte[] p = new byte[62];
        p[1] = 'A'; p[2] = 'P'; p[3] = 'P'; p[41] = 2; p[50] = 2; p[51] = 8;
        return command(0x48, seq, 0x80, 0, 0x81, p);
    }
    static Frame appPresence(int seq) {
        return command(0x28, seq, REQUEST, 0, 0x88,
                bytes(0x17,0,0x46,0x23,0x7C,0x41,0x50,0x50,0,0,0,0,0,2));
    }
    static Frame gimbalInit(int seq) {
        return command(3, seq, REQUEST, 3, 0xDA, bytes(5,0xFF,0xFF,0xFF,0xFF));
    }
    static Frame enterPlayback(int seq) {
        return command(RX_CAMERA, seq, REQUEST, 2, 0x0C, bytes(1,1,0,1));
    }
    static Frame mediaList(int seq, int counter, long cursor) {
        byte[] p = bytes(0x4A,0,0x2A,0x10,1,0,0,0,0,0,1,0,0,0,0x2D,0,0x0D,1,0,
                0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0,1,0,0,0,0,0,0,0,0,0,0,0);
        p[4] = (byte) counter;
        put32(p, 10, cursor);
        return command(RX_CAMERA, seq, REQUEST, 0, 0x26, p);
    }
    static Frame mediaTrigger(int seq) {
        return command(RX_CAMERA, seq, REQUEST, 0, 0x26,
                bytes(0x4A,4,0x0E,0x10,1,0,0,0,0,0,1,0,0,0));
    }

    static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n]; int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }
    static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }
    static byte[] pack(String s) {
        byte[] text = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[text.length + 1]; out[0] = (byte) text.length;
        System.arraycopy(text, 0, out, 1, text.length); return out;
    }
    static int u8(byte b) { return b & 0xFF; }
    static int u16(byte[] b, int at) { return u8(b[at]) | (u8(b[at + 1]) << 8); }
    static void put16(byte[] b, int at, int v) { b[at] = (byte) v; b[at + 1] = (byte) (v >>> 8); }
    static void put32(byte[] b, int at, long v) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >>> (8 * i));
    }
    static int crc8(byte[] b, int at, int len) {
        int c = 0x77;
        for (int j = at; j < at + len; j++) { c ^= u8(b[j]); for (int i=0;i<8;i++) c = (c&1)!=0 ? (c>>>1)^0x8C : c>>>1; }
        return c & 0xFF;
    }
    static int crc16(byte[] b, int at, int len) {
        int c = 0x3692;
        for (int j = at; j < at + len; j++) { c ^= u8(b[j]); for (int i=0;i<8;i++) c = (c&1)!=0 ? (c>>>1)^0x8408 : c>>>1; }
        return c & 0xFFFF;
    }
}
