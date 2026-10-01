package dev.openpocket.probe;

/** Safe, metadata-only decoder for DJI microphone-family DUML notifications. */
final class MicProtocol {
    record Packet(int length, int seq, int kind, int family, int opcode,
                  int bodyLength, int firstBodyByte, String dialect, boolean crcValid) {}

    private MicProtocol() {}

    static Packet parse(byte[] raw) {
        if (raw == null || raw.length < 13 || Protocol.u8(raw[0]) != 0x55) return null;
        int length = Protocol.u8(raw[1]) | ((Protocol.u8(raw[2]) & 3) << 8);
        if (length != raw.length || (Protocol.u8(raw[2]) >>> 2) != 1) return null;
        if (Protocol.crc8(raw, 0, 3) != Protocol.u8(raw[3])) return null;

        int marker = Protocol.u8(raw[11]);
        String dialect = marker == 3 ? "v2" : marker == 0 ? "v1/无标记" : "未知";
        boolean crcValid = Protocol.crc16(raw, 0, length - 2) == Protocol.u16(raw, length - 2);
        return new Packet(length, Protocol.u16(raw, 6), Protocol.u8(raw[8]),
                Protocol.u8(raw[9]), Protocol.u8(raw[10]), length - 13, marker, dialect, crcValid);
    }
}
