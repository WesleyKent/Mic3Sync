package dev.openpocket.probe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ProtocolTest {
    @Test public void crcVectors() {
        assertEquals(0x92, Protocol.crc8(Protocol.bytes(0x55,0x11,0x04), 0, 3));
        assertEquals(0xC2, Protocol.crc8(Protocol.bytes(0x55,0x33,0x04), 0, 3));
    }
    @Test public void enterPlaybackMatchesCapture() {
        byte[] got = Protocol.encode(Protocol.enterPlayback(0xA000));
        assertArrayEquals(Protocol.bytes(0x55,0x11,0x04,0x92,0x02,0x01,0x00,0xA0,0x40,0x02,0x0C,1,1,0,1,0xB6,0x3B), got);
        assertEquals(1, Protocol.scan(got).size());
    }
    @Test public void transportWorkedExamples() {
        assertArrayEquals(Protocol.bytes(0x25,0x80,0x7C,0x3A,0x90,0xB8,5,0xCE), Protocol.transportHeader(5,29,0x3A7C,0xB890));
        assertArrayEquals(Protocol.bytes(0x88,0xB8,0x90,0xB8,0,0,0,0,6,1,0,0), Protocol.routingHeader(0xB890,6));
        assertEquals(40, Protocol.handshakePayload(0xB887).length);
        assertEquals(26, Protocol.ackPayload(0x1234,0x5678,0x5678).length);
    }
    @Test public void pairingFrameHasKnownShape() {
        byte[] got = Protocol.encode(Protocol.pairing());
        assertEquals(51, got.length); assertEquals(0xC2, Protocol.u8(got[3]));
        assertEquals(0xA0, Protocol.u8(got[49])); assertEquals(0xB4, Protocol.u8(got[50]));
    }
    @Test public void mic3VerificationTargetsDmTxIndexOne() {
        Protocol.Frame verify = Protocol.mic3Verification();
        assertEquals(0x3A, verify.receiver());
        assertEquals(7, verify.cmdSet());
        assertEquals(0x45, verify.cmdId());
        assertArrayEquals(Protocol.pairing().payload(), verify.payload());
        assertEquals(0x3A, Protocol.getMic3Ssid().receiver());
        assertEquals(0x3A, Protocol.getMic3Password().receiver());
        assertEquals(0x01, Protocol.mic3DeviceInfo().cmdId());
        assertEquals(0x32, Protocol.mic3VersionInfo().cmdId());
        assertArrayEquals(Protocol.bytes(0x11), Protocol.mic3VersionInfo().payload());
        assertEquals(0x0C, Protocol.getMic3Mac().cmdId());
        assertEquals(0x5B, Protocol.enterMic3AudioPlayback().cmdSet());
        assertEquals(0x19, Protocol.enterMic3AudioPlayback().cmdId());
        assertArrayEquals(Protocol.bytes(1, 1, 0, 1), Protocol.enterMic3AudioPlayback().payload());
        assertArrayEquals(Protocol.bytes(1, 1, 0, 0), Protocol.exitMic3AudioPlayback().payload());
    }
    @Test public void microphoneAckMetadataIsDecodedWithoutPayloadDump() {
        byte[] ack = Protocol.bytes(0x55,0x0E,0x04,0x66,0x5A,0x02,0x01,0x00,0x80,0x5B,0x01,0x00,0xC0,0xE8);
        MicProtocol.Packet packet = MicProtocol.parse(ack);
        assertNotNull(packet);
        assertEquals(0x5B, packet.family());
        assertEquals(1, packet.opcode());
        assertEquals(1, packet.bodyLength());
        assertEquals(0, packet.firstBodyByte());
    }
    @Test public void micManifestFolderAndFilenameRemainNested() {
        assertEquals("/data/emmc0/TX_MIC001_20260930_100800/TX01_MIC001_20260930_100800_orig.wav",
                MicMediaSession.withDirectory("TX_MIC001_20260930_100800",
                        "TX01_MIC001_20260930_100800_orig.wav"));
        assertEquals("/already/absolute.wav",
                MicMediaSession.withDirectory("TX_MIC001_20260930_100800", "/already/absolute.wav"));
        assertEquals("/data/emmc0/TX_MIC001_20260930_100800/clip.wav",
                MicMediaSession.withDirectory("/data/emmc0/TX_MIC001_20260930_100800", "clip.wav"));
        assertEquals("/data/emmc0/TX_MIC001_20260930_100800/clip.wav",
                MicMediaSession.withDirectory("data/emmc0/TX_MIC001_20260930_100800", "clip.wav"));
        assertEquals("/data/emmc0/TX_MIC001_20260930_100800/clip.wav",
                MicMediaSession.withDirectory(
                        "TX RECORD FILES INFO/data/emmc0/TX_MIC001_20260930_100800/", "clip.wav"));
        assertEquals("/data/emmc0/TX_MIC001_20260930_100800/clip.wav",
                MicMediaSession.withDirectory("TX RECORD FILES INFO",
                        "data/emmc0/TX_MIC001_20260930_100800/clip.wav"));
        assertEquals("/data/emmc0/TX_MIC001_20260930_100800/clip.wav",
                MicMediaSession.withDirectory("TX RECORD FILES INFO",
                        "TX RECORD FILES INFO/data/emmc0/TX_MIC001_20260930_100800/clip.wav"));
    }
    @Test public void manifestPathAndExtensionDecode() {
        byte[] name = "DJI_20260925120000_0001_D.MP4".getBytes(StandardCharsets.ISO_8859_1);
        byte[] path = "DCIM/100MEDIA/DJI_20260925120000_0001_D".getBytes(StandardCharsets.ISO_8859_1);
        byte[] blob = Protocol.concat(Protocol.bytes(0x0D, name.length), name,
                Protocol.bytes(0x1A, path.length + 6, 0, 0, 0, 1), path);
        var items = MediaCatalog.decode(Map.of(1, blob));
        assertEquals(1, items.size());
        assertEquals("DCIM/100MEDIA/DJI_20260925120000_0001_D.MP4", items.get(0).path());
    }
}
