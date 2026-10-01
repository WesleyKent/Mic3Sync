package dev.openpocket.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Mic 3 incremental synchronizer that hands each new batch to Android Sharesheet. */
public final class MainActivity extends Activity implements BleBootstrap.Listener, CameraNetwork.Listener, CameraSession.Listener {
    private static final int PERMISSIONS = 7, PICK_FOLDER = 8;
    private static final String PREFS = "mic3-auto-sync", KEY_SYNCED = "synced-paths", KEY_TREE = "target-tree",
            KEY_DEVICE = "last-device", KEY_AUTO_SHARE = "auto-share";
    private static final int INK = Color.rgb(24, 24, 23), MUTED = Color.rgb(113, 113, 108),
            CANVAS = Color.rgb(247, 247, 245), BORDER = Color.rgb(225, 225, 221), PURPLE = Color.rgb(104, 81, 214);

    private final Set<String> visibleDevices = new HashSet<>(), inFlight = new HashSet<>();
    private final ArrayList<Uri> completedUris = new ArrayList<>();
    private LinearLayout devices, media;
    private TextView phase, destination, stats, log;
    private Button sync, refresh, chooseFolder, shareBatch;
    private CheckBox autoShare;
    private BleBootstrap ble;
    private CameraNetwork network;
    private MicMediaSession session;
    private SharedPreferences prefs;
    private Uri targetTree;
    private int pendingDownloads;
    private boolean autoConnectArmed, batchHadFailure;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedTree = prefs.getString(KEY_TREE, null); if (savedTree != null) targetTree = Uri.parse(savedTree);
        configureWindow(); buildUi();
        ble = new BleBootstrap(this, this); network = new CameraNetwork(this, this);
        sync.setOnClickListener(v -> requestThenScan());
        refresh.setOnClickListener(v -> { if (session != null) session.refreshMedia(); });
        chooseFolder.setOnClickListener(v -> chooseFolder());
        shareBatch.setOnClickListener(v -> shareCompleted());
        autoShare.setChecked(prefs.getBoolean(KEY_AUTO_SHARE, true));
        autoShare.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(KEY_AUTO_SHARE, checked).apply());
        updateDestination(); updateStats();
        append("工作流：Mic 3 新增录音 → 本机目录 → Android 分享面板");
        getWindow().getDecorView().postDelayed(this::requestThenScan, 650);
    }

    private void configureWindow() {
        Window window = getWindow(); window.setStatusBarColor(CANVAS); window.setNavigationBarColor(CANVAS);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void requestThenScan() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { permissions.add(Manifest.permission.BLUETOOTH_SCAN); permissions.add(Manifest.permission.BLUETOOTH_CONNECT); }
        else permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), PERMISSIONS); else beginScan();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == PERMISSIONS && results.length > 0 && Arrays.stream(results).allMatch(value -> value == PackageManager.PERMISSION_GRANTED)) beginScan();
        else { setPhase("需要附近设备权限"); append("权限不足：请在系统设置中允许附近设备后重试"); }
    }

    private void beginScan() {
        visibleDevices.clear(); devices.removeAllViews(); autoConnectArmed = true; sync.setEnabled(false);
        setPhase("正在扫描 DJI Mic 3 TX"); append("请长按 TX 配对键约 2 秒，等待蓝绿灯交替闪烁"); ble.startScan();
    }

    @Override public void onDevice(BluetoothDevice device, String name, int rssi) { ui(() -> {
        if (name == null || !name.toLowerCase(Locale.US).contains("mic 3")) return;
        String address; try { address = device.getAddress(); } catch (SecurityException error) { address = name; }
        if (!visibleDevices.add(address)) return;
        Button button = secondaryButton(name + "   " + rssi + " dBm");
        String key = address; button.setOnClickListener(view -> connect(device, key)); devices.addView(button, spaced(-1, dp(50), 6));
        String remembered = prefs.getString(KEY_DEVICE, null);
        if (autoConnectArmed && remembered != null && remembered.equals(address)) {
            autoConnectArmed = false; append("发现上次使用的 TX，自动连接"); connect(device, address);
        } else if (remembered == null && visibleDevices.size() == 1) append("发现 Mic 3；首次使用请点击设备名称确认连接");
    }); }

    private void connect(BluetoothDevice device, String address) {
        prefs.edit().putString(KEY_DEVICE, address).apply(); devices.removeAllViews(); sync.setEnabled(false);
        setPhase("BLE 验证：看到提示后短按一次 TX 配对键"); ble.connect(device);
    }

    @Override public void onCredentials(String ssid, char[] password, boolean mic3) { ui(() -> {
        if (!mic3) { onFailure("请选择 DJI Mic 3 TX", null); return; }
        setPhase("加入 Mic 3 设备热点"); network.join(ssid, password);
    }); }

    @Override public void onReady(android.net.Network networkValue, String localIp, String gateway) { ui(() -> {
        setPhase("读取 Mic 3 录音索引"); if (session != null) session.close();
        session = new MicMediaSession(network, gateway, this); session.open();
    }); }

    @Override public void onMedia(List<MediaCatalog.Item> items) { ui(() -> {
        if (!inFlight.isEmpty()) { append("下载仍在进行，暂不重复规划同步任务"); return; }
        refresh.setEnabled(true); media.removeAllViews();
        Set<String> synced = new LinkedHashSet<>(prefs.getStringSet(KEY_SYNCED, Set.of()));
        List<MediaCatalog.Item> fresh = SyncPlanner.fresh(items, synced, inFlight);
        stats.setText("设备 " + items.size() + " 条  ·  已下载 " + synced.size() + " 条  ·  新增 " + fresh.size() + " 条");
        if (fresh.isEmpty()) {
            if (session != null) { session.close(); session = null; }
            network.close();
            setPhase("同步完成，没有新增录音"); sync.setEnabled(true);
            media.addView(body("设备上的录音都已下载；以后只处理新增文件。")); return;
        }
        setPhase("发现 " + fresh.size() + " 条新增录音，自动下载中");
        pendingDownloads = fresh.size(); completedUris.clear(); batchHadFailure = false; shareBatch.setEnabled(false); refresh.setEnabled(false);
        for (MediaCatalog.Item item : fresh) {
            inFlight.add(item.path()); TextView row = body("下载队列  ·  " + item.filename()); row.setPadding(0, dp(7), 0, dp(7)); media.addView(row);
            session.download(item, getContentResolver(), targetTree);
        }
    }); }

    @Override public void onDownloadComplete(String sourcePath, Uri destinationUri) { ui(() -> {
        Set<String> synced = new LinkedHashSet<>(prefs.getStringSet(KEY_SYNCED, Set.of()));
        synced.add(sourcePath); completedUris.add(destinationUri); prefs.edit().putStringSet(KEY_SYNCED, synced).apply();
        inFlight.remove(sourcePath); pendingDownloads = Math.max(0, pendingDownloads - 1);
        stats.setText("已下载 " + synced.size() + " 条  ·  本次剩余 " + pendingDownloads + " 条");
        if (pendingDownloads == 0) {
            finishBatch();
        }
    }); }

    @Override public void onDownloadFailed(String sourcePath, String message) { ui(() -> {
        batchHadFailure = true; inFlight.remove(sourcePath); pendingDownloads = Math.max(0, pendingDownloads - 1);
        append("本条未写入下载历史，下次会自动重试：" + message);
        if (pendingDownloads == 0) finishBatch();
    }); }

    private void finishBatch() {
        if (session != null) { session.close(); session = null; }
        network.close(); sync.setEnabled(true); refresh.setEnabled(false); shareBatch.setEnabled(!completedUris.isEmpty()); updateStats();
        if (completedUris.isEmpty()) { setPhase("新增录音下载失败，可点击立即同步重试"); return; }
        setPhase(batchHadFailure ? "部分录音已下载，可以分享成功的文件" : "新增录音已下载，准备分享");
        if (autoShare.isChecked()) getWindow().getDecorView().postDelayed(this::shareCompleted, 650);
    }

    private void shareCompleted() {
        if (completedUris.isEmpty()) return;
        Intent share = new Intent(completedUris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        share.setType("audio/*"); share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (completedUris.size() == 1) share.putExtra(Intent.EXTRA_STREAM, completedUris.get(0));
        else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(completedUris));
        ClipData clip = ClipData.newUri(getContentResolver(), "DJI Mic 3 recordings", completedUris.get(0));
        for (int i = 1; i < completedUris.size(); i++) clip.addItem(new ClipData.Item(completedUris.get(i)));
        share.setClipData(clip);
        startActivity(Intent.createChooser(share, "将新增录音导入到…"));
        append("已打开系统分享面板，可选择闪念贝壳或其他音频应用");
        setPhase("录音已准备好，请在分享面板选择目标应用");
    }

    @Override public void onProgress(String file, int percent) {
        if (percent == 0 || percent == 25 || percent == 50 || percent == 75 || percent == 100) ui(() -> append("下载 " + file + "：" + percent + "%"));
    }
    @Override public void onLog(String message) { ui(() -> append(message)); }
    @Override public void onFailure(String message, Throwable error) { ui(() -> {
        sync.setEnabled(true); setPhase("同步失败"); append("错误：" + message + (error == null || error.getMessage() == null ? "" : " — " + error.getMessage()));
    }); }
    @Override public void onFailure(String message) { onFailure(message, null); }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (SecurityException error) { append("目标文件夹未授予长期写入权限，将只在本次运行中使用"); }
        targetTree = uri; prefs.edit().putString(KEY_TREE, uri.toString()).apply(); updateDestination();
    }

    private void updateDestination() { destination.setText(targetTree == null ? "Downloads / Mic3AutoSync" : "已选择自定义文件夹"); }
    private void updateStats() { stats.setText("本机已同步 " + prefs.getStringSet(KEY_SYNCED, Set.of()).size() + " 条录音"); }

    private void buildUi() {
        ScrollView outer = new ScrollView(this); outer.setFillViewport(true); outer.setBackgroundColor(CANVAS);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(22), dp(20), dp(36)); outer.addView(root);

        TextView eyebrow = label("MIC 3 SYNC", 12); eyebrow.setTextColor(PURPLE); eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD); eyebrow.setLetterSpacing(.12f); root.addView(eyebrow);
        TextView title = label("让每一段录音\n自动抵达你的想法库", 30); title.setTextColor(INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setLineSpacing(dp(3), 1f); root.addView(title, spaced(-1, -2, 8));
        TextView subtitle = body("连接一次，之后只下载新增素材；完成后通过系统分享面板导入闪念贝壳或其他应用。\n设备验证仍需按一次 TX 配对键。"); root.addView(subtitle, spaced(-1, -2, 18));

        LinearLayout syncCard = card(); root.addView(syncCard, spaced(-1, -2, 12));
        syncCard.addView(sectionTitle("设备同步"));
        phase = label("准备同步", 18); phase.setTextColor(INK); phase.setTypeface(Typeface.DEFAULT, Typeface.BOLD); syncCard.addView(phase, spaced(-1, -2, 8));
        stats = muted(""); syncCard.addView(stats, spaced(-1, -2, 12));
        sync = primaryButton("立即同步"); syncCard.addView(sync, spaced(-1, dp(52), 8));
        refresh = secondaryButton("刷新设备索引"); refresh.setEnabled(false); syncCard.addView(refresh, spaced(-1, dp(48), 8));
        devices = new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); syncCard.addView(devices);

        LinearLayout shareCard = card(); root.addView(shareCard, spaced(-1, -2, 12));
        shareCard.addView(sectionTitle("导入到其他应用"));
        shareCard.addView(body("录音下载完成后调用 Android 标准分享接口。可以选择闪念贝壳、网盘、剪辑软件或任意支持音频导入的应用。"), spaced(-1, -2, 9));
        autoShare = new CheckBox(this); autoShare.setText("下载完成后自动打开分享面板"); autoShare.setTextColor(INK); autoShare.setTextSize(14); autoShare.setButtonTintList(android.content.res.ColorStateList.valueOf(PURPLE)); shareCard.addView(autoShare, spaced(-1, dp(48), 8));
        shareBatch = secondaryButton("分享本次新增录音"); shareBatch.setEnabled(false); shareCard.addView(shareBatch, spaced(-1, dp(48), 0));

        LinearLayout storageCard = card(); root.addView(storageCard, spaced(-1, -2, 12));
        storageCard.addView(sectionTitle("本机副本")); destination = body(""); storageCard.addView(destination, spaced(-1, -2, 8));
        chooseFolder = secondaryButton("选择保存文件夹"); storageCard.addView(chooseFolder, spaced(-1, dp(48), 0));

        LinearLayout progressCard = card(); root.addView(progressCard, spaced(-1, -2, 12));
        progressCard.addView(sectionTitle("本次同步")); media = new LinearLayout(this); media.setOrientation(LinearLayout.VERTICAL); media.addView(muted("尚未开始")); progressCard.addView(media);

        LinearLayout logCard = card(); root.addView(logCard, spaced(-1, -2, 12));
        logCard.addView(sectionTitle("安全连接日志")); logCard.addView(muted("不会显示或保存 Mic 3 的 Wi-Fi 密码。"), spaced(-1, -2, 7));
        log = label("", 12); log.setTextColor(MUTED); log.setMovementMethod(new ScrollingMovementMethod()); log.setLineSpacing(dp(3), 1f); logCard.addView(log);

        Button settings = textButton("打开系统设置"); settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))); root.addView(settings, spaced(-1, dp(46), 2));
        Button reset = textButton("重置下载历史");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("重置同步历史？")
                .setMessage("下次连接会重新下载设备录音。本机已有文件不会被删除。")
                .setNegativeButton("取消", null).setPositiveButton("重置", (dialog, which) -> {
                    prefs.edit().remove(KEY_SYNCED).apply(); updateStats(); append("同步历史已重置");
                }).show());
        root.addView(reset, spaced(-1, dp(46), 0)); setContentView(outer);
    }

    private LinearLayout card() {
        LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); value.setPadding(dp(18), dp(18), dp(18), dp(18));
        value.setBackground(rounded(Color.WHITE, 20, 0, Color.TRANSPARENT)); value.setElevation(dp(1)); return value;
    }
    private TextView sectionTitle(String text) { TextView value = label(text, 13); value.setTextColor(MUTED); value.setTypeface(Typeface.DEFAULT, Typeface.BOLD); value.setLetterSpacing(.04f); value.setPadding(0, 0, 0, dp(12)); return value; }
    private Button primaryButton(String text) { Button value = button(text); value.setTextColor(Color.WHITE); value.setTypeface(Typeface.DEFAULT, Typeface.BOLD); value.setBackground(rounded(INK, 15, 0, Color.TRANSPARENT)); return value; }
    private Button secondaryButton(String text) { Button value = button(text); value.setTextColor(INK); value.setBackground(rounded(Color.rgb(249,249,247), 14, 1, BORDER)); return value; }
    private Button textButton(String text) { Button value = button(text); value.setTextColor(MUTED); value.setBackgroundColor(Color.TRANSPARENT); return value; }
    private Button button(String text) { Button value = new Button(this); value.setAllCaps(false); value.setText(text); value.setTextSize(15); value.setPadding(dp(12), 0, dp(12), 0); return value; }
    private TextView body(String text) { TextView value = label(text, 14); value.setTextColor(MUTED); value.setLineSpacing(dp(3), 1f); return value; }
    private TextView muted(String text) { TextView value = label(text, 13); value.setTextColor(MUTED); return value; }
    private TextView label(String text, int sp) { TextView value = new TextView(this); value.setText(text); value.setTextSize(sp); return value; }
    private GradientDrawable rounded(int color, int radius, int stroke, int strokeColor) { GradientDrawable value = new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radius)); if (stroke > 0) value.setStroke(dp(stroke), strokeColor); return value; }
    private LinearLayout.LayoutParams spaced(int width, int height, int bottom) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(width, height); value.bottomMargin = dp(bottom); return value; }
    private void append(String message) { log.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + message + "\n"); }
    private void setPhase(String text) { phase.setText(text); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void ui(Runnable action) { runOnUiThread(action); }

    @Override protected void onDestroy() {
        if (session != null) session.close(); if (network != null) network.close(); if (ble != null) ble.close(); super.onDestroy();
    }
}
