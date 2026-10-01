package dev.openpocket.probe;

import android.content.Context;
import android.net.*;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;

import java.net.*;
import java.io.IOException;

/** Requests the camera-only Wi-Fi and makes socket routing explicit. */
final class CameraNetwork implements AutoCloseable {
    interface Listener { void onLog(String text); void onReady(Network network, String localIp, String gateway); void onFailure(String text); }
    private final ConnectivityManager connectivity;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback callback;
    private Network network;
    private boolean readyDelivered;
    private String lastLinkSummary;

    CameraNetwork(Context context, Listener listener) {
        connectivity = context.getSystemService(ConnectivityManager.class); this.listener = listener;
    }

    void join(String ssid, char[] password) {
        if (callback != null) try { connectivity.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        callback = null; network = null; readyDelivered = false; lastLinkSummary = null;
        String pass = new String(password); java.util.Arrays.fill(password, '\0');
        WifiNetworkSpecifier.Builder sb = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (!pass.isEmpty()) sb.setWpa2Passphrase(pass);
        WifiNetworkSpecifier spec = sb.build(); pass = null;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build();
        listener.onLog("Wi-Fi：请求加入 " + ssid + "（Android 会弹出确认框）");
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                network = n;
                if (!connectivity.bindProcessToNetwork(n)) { listener.onFailure("系统拒绝绑定相机网络"); return; }
                listener.onLog("Wi-Fi：系统已提供目标网络，等待 DHCP 地址与网关");
                completeWhenReady(n, connectivity.getLinkProperties(n));
            }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) {
                completeWhenReady(n, p);
            }
            @Override public void onBlockedStatusChanged(Network n, boolean blocked) {
                listener.onLog("Wi-Fi：系统网络状态 " + (blocked ? "被阻止" : "可用"));
            }
            @Override public void onUnavailable() {
                listener.onFailure("45 秒内未加入热点；请确认已在 Android 弹窗中选择“连接”");
            }
            @Override public void onLost(Network n) { if (n.equals(network)) listener.onFailure("相机 Wi-Fi 已断开"); }
        };
        try { connectivity.requestNetwork(request, callback, new Handler(Looper.getMainLooper()), 45_000); }
        catch (RuntimeException e) { listener.onFailure("请求相机 Wi-Fi 失败：" + e.getMessage()); }
    }

    private void completeWhenReady(Network n, LinkProperties p) {
        if (readyDelivered || p == null) return;
        String local = localIp(p), gateway = gateway(p);
        String summary = "local=" + (local == null ? "--" : local) + " gateway=" + (gateway == null ? "--" : gateway);
        if (!summary.equals(lastLinkSummary)) { lastLinkSummary = summary; listener.onLog("Wi-Fi：DHCP " + summary); }
        if (local == null || gateway == null) return;
        readyDelivered = true;
        listener.onLog("Wi-Fi：已绑定设备网络，本机地址 " + local + "，设备网关 " + gateway);
        listener.onReady(n, local, gateway);
    }

    private String localIp(LinkProperties p) {
        for (LinkAddress a : p.getLinkAddresses())
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        return null;
    }
    private String gateway(LinkProperties p) {
        for (RouteInfo route : p.getRoutes()) {
            InetAddress g = route.getGateway();
            if (route.isDefaultRoute() && g instanceof Inet4Address) return g.getHostAddress();
        }
        for (RouteInfo route : p.getRoutes()) {
            InetAddress g = route.getGateway();
            if (g instanceof Inet4Address) return g.getHostAddress();
        }
        return null;
    }
    void bind(DatagramSocket socket) throws IOException { if (network == null) throw new IOException("camera network missing"); network.bindSocket(socket); }
    void bind(Socket socket) throws IOException { if (network == null) throw new IOException("camera network missing"); network.bindSocket(socket); }
    URLConnection open(URL url) throws IOException { if (network == null) throw new IOException("camera network missing"); return network.openConnection(url); }

    @Override public void close() {
        connectivity.bindProcessToNetwork(null);
        if (callback != null) try { connectivity.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        callback = null; network = null;
    }
}
