package dev.openpocket.probe;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** DJI Mic 3's Wi-Fi file service. It is separate from Pocket's 7001/9004 media transport. */
final class MicMediaSession implements DeviceMediaSession {
    private static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;
    private final CameraNetwork path;
    private final String host;
    private final CameraSession.Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    MicMediaSession(CameraNetwork path, String host, CameraSession.Listener listener) {
        this.path = path; this.host = host; this.listener = listener;
    }

    @Override public void open() { refreshMedia(); }

    @Override public void refreshMedia() {
        io.execute(() -> {
            try {
                URL url = new URL("http://" + host + "/data/emmc0/files_info.json");
                listener.onLog("Mic 3 媒体：GET /data/emmc0/files_info.json");
                byte[] body = readIndexWhenReady(url);
                if (body.length > MAX_INDEX_BYTES) throw new java.io.IOException("文件索引超过 4 MiB 安全上限");
                Object root = new JSONTokener(new String(body, java.nio.charset.StandardCharsets.UTF_8)).nextValue();
                LinkedHashSet<String> candidates = new LinkedHashSet<>();
                LinkedHashSet<String> keys = new LinkedHashSet<>();
                collect(root, null, candidates, keys, 0);
                List<MediaCatalog.Item> items = new ArrayList<>();
                for (String candidate : candidates) {
                    String normalized = normalize(candidate);
                    if (normalized != null) items.add(new MediaCatalog.Item(normalized));
                }
                listener.onLog("Mic 3 媒体：索引 " + body.length + " 字节，字段 " + summarize(keys) + "，录音 " + items.size() + " 条");
                listener.onMedia(items);
            } catch (Exception e) { listener.onFailure("读取 Mic 3 录音索引失败", e); }
        });
    }

    private byte[] readIndexWhenReady(URL url) throws Exception {
        final int attempts = 12;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpURLConnection c = (HttpURLConnection) path.open(url);
                c.setConnectTimeout(1800); c.setReadTimeout(8000);
                int status = c.getResponseCode();
                if (status != 200) throw new java.io.IOException("files_info.json HTTP " + status);
                try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32 * 1024]; int total = 0, read;
                    while ((read = in.read(buffer)) >= 0 && total <= MAX_INDEX_BYTES) {
                        out.write(buffer, 0, read); total += read;
                    }
                    return out.toByteArray();
                } finally {
                    c.disconnect();
                }
            } catch (ConnectException | SocketTimeoutException e) {
                if (attempt == attempts) throw e;
                if (attempt == 1)
                    listener.onLog("Mic 3 媒体：HTTP 服务尚未就绪，最多等待约 20 秒并重试");
                Thread.sleep(1500);
            }
        }
        throw new java.io.IOException("Mic 3 HTTP service unavailable");
    }

    private static void collect(Object value, String directory, Set<String> paths, Set<String> keys, int depth) {
        if (value == null || depth > 12) return;
        if (value instanceof JSONObject object) {
            String nestedDirectory = directory;
            Object files = object.opt("files");
            String name = object.optString("name", "").trim();
            if (files instanceof JSONArray && normalizeDirectory(name) != null) nestedDirectory = name;
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (keys.size() < 16) keys.add(key);
                Object child = object.opt(key);
                if ("fname".equals(key) && child instanceof String text && isAudio(text))
                    paths.add(withDirectory(nestedDirectory, text));
                else
                    collect(child, nestedDirectory, paths, keys, depth + 1);
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++)
                collect(array.opt(i), directory, paths, keys, depth + 1);
        } else if (value instanceof String text && isAudio(text)) {
            paths.add(withDirectory(directory, text));
        }
    }

    private static String normalizeDirectory(String value) {
        String directory = value == null ? "" : value.trim();
        if (directory.isEmpty() || directory.length() > 320 || directory.contains("\\") ||
                directory.contains("..") || directory.contains("?") || directory.contains("#")) return null;
        if (directory.startsWith("http://") || directory.startsWith("https://")) {
            try { directory = new URL(directory).getPath(); } catch (Exception e) { return null; }
        }
        // Mic 3 prefixes storage paths with a manifest label such as
        // "TX RECORD FILES INFO/data/emmc0/...". The label is not part of the URL.
        int embeddedDataPath = directory.indexOf("/data/");
        if (embeddedDataPath >= 0) directory = directory.substring(embeddedDataPath);
        while (directory.endsWith("/")) directory = directory.substring(0, directory.length() - 1);
        if (directory.startsWith("/")) return directory;
        if (directory.startsWith("data/")) return "/" + directory;
        return "/data/emmc0/" + directory;
    }

    static String withDirectory(String directory, String filename) {
        String filePath = filename == null ? "" : filename.trim();
        if (filePath.isEmpty() || filePath.contains("..") || filePath.contains("\\") ||
                filePath.contains("?") || filePath.contains("#")) return filePath;
        int embeddedDataPath = filePath.indexOf("/data/");
        if (embeddedDataPath >= 0) return filePath.substring(embeddedDataPath);
        if (filePath.startsWith("data/")) return "/" + filePath;
        String base = normalizeDirectory(directory);
        if (base == null || filePath.startsWith("/") ||
                filePath.startsWith("http://") || filePath.startsWith("https://")) return filePath;
        return base + "/" + filePath;
    }

    private static boolean isAudio(String text) {
        String clean = text.split("[?#]", 2)[0].toLowerCase(Locale.US);
        return clean.endsWith(".wav") || clean.endsWith(".mp3") || clean.endsWith(".aac") || clean.endsWith(".m4a");
    }

    private static String normalize(String candidate) {
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > 512 || value.contains("..")) return null;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try { value = new URL(value).getPath(); } catch (Exception e) { return null; }
        }
        if (!value.startsWith("/")) value = "/data/emmc0/" + value;
        return isAudio(value) ? value : null;
    }

    private static String summarize(Set<String> keys) {
        if (keys.isEmpty()) return "--";
        String joined = String.join(",", keys);
        return joined.length() <= 180 ? joined : joined.substring(0, 180) + "…";
    }

    @Override public void download(MediaCatalog.Item item, ContentResolver resolver) {
        download(item, resolver, null);
    }

    void download(MediaCatalog.Item item, ContentResolver resolver, Uri targetTree) {
        io.execute(() -> {
            Uri destination = null;
            try {
                String encoded = Uri.encode(item.path(), "/");
                URL url = new URL("http://" + host + encoded);
                listener.onLog("Mic 3 下载：" + item.filename());
                listener.onLog("Mic 3 路径：" + item.path());
                HttpURLConnection c = (HttpURLConnection) path.open(url);
                c.setConnectTimeout(4000); c.setReadTimeout(30000);
                int status = c.getResponseCode();
                if (status != 200 && status != 206) throw new java.io.IOException("HTTP " + status);
                long total = c.getContentLengthLong();
                ContentValues values = new ContentValues();
                if (targetTree != null) {
                    String treeId = DocumentsContract.getTreeDocumentId(targetTree);
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(targetTree, treeId);
                    destination = DocumentsContract.createDocument(resolver, parent, mime(item.path()), item.filename());
                } else {
                    values.put(MediaStore.Downloads.DISPLAY_NAME, item.filename());
                    values.put(MediaStore.Downloads.MIME_TYPE, mime(item.path()));
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mic3AutoSync");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                }
                if (destination == null) throw new java.io.IOException("MediaStore insert failed");
                try (InputStream in = c.getInputStream(); OutputStream out = resolver.openOutputStream(destination)) {
                    if (out == null) throw new java.io.IOException("output unavailable");
                    byte[] buffer = new byte[128 * 1024]; long done = 0; int read, last = -1;
                    while ((read = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, read); done += read;
                        int percent = total > 0 ? (int) (done * 100 / total) : 0;
                        if (percent != last) { last = percent; listener.onProgress(item.filename(), percent); }
                    }
                }
                if (targetTree == null) {
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(destination, values, null, null);
                }
                listener.onProgress(item.filename(), 100);
                listener.onLog("下载完成：" + item.filename());
                listener.onDownloadComplete(item.path(), destination);
            } catch (Exception e) {
                if (destination != null) resolver.delete(destination, null, null);
                listener.onDownloadFailed(item.path(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                listener.onFailure("下载 Mic 3 录音失败：" + item.filename(), e);
            }
        });
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".aac")) return "audio/aac";
        return "audio/mp4";
    }

    @Override public void close() { io.shutdownNow(); }
}
