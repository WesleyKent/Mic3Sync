package dev.openpocket.probe;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Conservative decoder for the CompositePack fields needed by this probe. */
final class MediaCatalog {
    record Item(String path) { String filename() { int i = path.lastIndexOf('/'); return i < 0 ? path : path.substring(i + 1); } }

    static List<Item> decode(Map<Integer, byte[]> stores) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (int counter : List.of(1, 2)) {
            byte[] b = stores.get(counter); if (b == null) continue;
            List<Anchor> anchors = new ArrayList<>();
            for (int i = 0; i + 6 <= b.length; i++) {
                if (Protocol.u8(b[i]) != 0x1A || b[i+2] != 0 || b[i+3] != 0 || b[i+4] != 0 || Protocol.u8(b[i+5]) != 1) continue;
                int len = Protocol.u8(b[i+1]) - 6;
                if (len < 5 || i + 6 + len > b.length) continue;
                String base = ascii(b, i + 6, len);
                if (base == null || !base.startsWith("DCIM/")) continue;
                anchors.add(new Anchor(i, i + 6 + len, base));
                i += 5 + len;
            }
            for (int k = 0; k < anchors.size(); k++) {
                Anchor a = anchors.get(k);
                int lo = k == 0 ? 0 : anchors.get(k - 1).end;
                int hi = k + 1 == anchors.size() ? b.length : anchors.get(k + 1).pos;
                String base = a.path;
                String ext = extensionNear(b, base.substring(base.lastIndexOf('/') + 1), lo, hi);
                String path = ext == null || base.contains(".") ? base : base + "." + ext;
                if (isMedia(path)) paths.add(path);
            }
        }
        List<Item> out = new ArrayList<>(); for (String p : paths) out.add(new Item(p)); return out;
    }

    private record Anchor(int pos, int end, String path) {}
    private static String extensionNear(byte[] b, String base, int lo, int hi) {
        for (int i = lo; i + 2 < hi; i++) {
            if (Protocol.u8(b[i]) != 0x0D) continue;
            int n = Protocol.u8(b[i+1]); if (i + 2 + n > hi) continue;
            String value = ascii(b, i + 2, n);
            if (value != null && value.startsWith(base + ".")) {
                String ext = value.substring(base.length() + 1).toUpperCase(Locale.US);
                if (Set.of("MP4","MOV","JPG","JPEG","DNG","HEIC","TIF","TIFF","PANO").contains(ext)) return ext;
            }
        }
        return null;
    }
    private static boolean isMedia(String p) {
        String u = p.toUpperCase(Locale.US);
        return u.matches(".*\\.(MP4|MOV|JPG|JPEG|DNG|HEIC|TIF|TIFF|PANO)$");
    }
    private static String ascii(byte[] b, int at, int n) {
        for (int i = at; i < at + n; i++) if (Protocol.u8(b[i]) < 0x20 || Protocol.u8(b[i]) > 0x7E) return null;
        return new String(b, at, n, StandardCharsets.ISO_8859_1);
    }
}
