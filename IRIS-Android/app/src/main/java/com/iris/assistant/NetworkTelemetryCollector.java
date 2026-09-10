package com.iris.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.SystemClock;

/**
 * Network facts for the Command Deck: transport, validated internet, addresses, gateway, DNS,
 * VPN/metered state, and IRIS's own traffic.
 *
 * Honesty rules applied here:
 *   - Wi-Fi identifiers are redacted by the platform depending on permission/version; we report
 *     PERMISSION_REQUIRED instead of inventing a value.
 *   - Labels are precise: "Phone IP (Wi-Fi)" vs "Router/gateway" vs "Cellular interface IP".
 *     Phone IP and Wi-Fi IP are the same fact, not two.
 *   - Public IP lookup is OFF by default (it requires contacting an outside service).
 *   - Traffic counters are IRIS's UID only; unavailable counters are never shown as 0.
 */
public final class NetworkTelemetryCollector {

    public static final String K_TRANSPORT   = "net_transport";
    public static final String K_INTERNET    = "net_internet";
    public static final String K_WIFI_NAME   = "wifi_name";
    public static final String K_WIFI_SIGNAL = "wifi_signal";
    public static final String K_WIFI_FREQ   = "wifi_freq";
    public static final String K_WIFI_LINK   = "wifi_link_speed";
    public static final String K_PHONE_IP    = "phone_ip_wifi";
    public static final String K_PHONE_IP6   = "phone_ip6";
    public static final String K_CELL_IP     = "cell_ip";
    public static final String K_GATEWAY     = "gateway";
    public static final String K_DNS         = "dns";
    public static final String K_VPN         = "vpn";
    public static final String K_METERED     = "metered";
    public static final String K_LAST_CHANGE = "net_last_change";
    public static final String K_PUBLIC_IP   = "public_ip";
    public static final String K_RX_RATE     = "iris_rx_rate";
    public static final String K_TX_RATE     = "iris_tx_rate";
    public static final String K_SESSION     = "iris_session_traffic";
    public static final String K_DEVICE_TOTAL = "device_total_traffic";

    private static final String SRC_CM = "ConnectivityManager";
    private static final String SRC_LP = "LinkProperties";
    private static final String SRC_WIFI = "WifiInfo";
    private static final String SRC_TS = "TrafficStats";

    private final Context ctx;
    private final TrafficRateMeter rx = new TrafficRateMeter(60);
    private final TrafficRateMeter tx = new TrafficRateMeter(60);
    private final TelemetryEventLog log;
    private ConnectivityManager.NetworkCallback callback;
    private volatile long lastChangeElapsed;
    private volatile String lastTransport = "";

    public NetworkTelemetryCollector(Context context, TelemetryEventLog log) {
        this.ctx = context.getApplicationContext();
        this.log = log;
    }

    /** Register for real state changes (callback-driven, per the update schedule). */
    public void start() {
        if (callback != null) return;
        try {
            ConnectivityManager cm = cm();
            if (cm == null) return;
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { note("available"); }
                @Override public void onLost(Network n) { note("lost"); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { note("changed"); }
            };
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(callback);
        } catch (Throwable ignored) { }
    }

    public void stop() {
        try {
            if (callback != null && cm() != null) cm().unregisterNetworkCallback(callback);
        } catch (Throwable ignored) { }
        callback = null;
    }

    private void note(String why) {
        lastChangeElapsed = SystemClock.elapsedRealtime();
        String t = transportLabel();
        if (log != null && !t.isEmpty() && !t.equals(lastTransport)) {
            lastTransport = t;
            log.add(TelemetryEventLog.Category.NET, "Default connection changed to " + t);
        }
    }

    /** Sample once per second while the panel is visible. */
    public void sampleTraffic() {
        long now = SystemClock.elapsedRealtime();
        int uid = android.os.Process.myUid();
        long rxBytes = safe(TrafficStats.getUidRxBytes(uid));
        long txBytes = safe(TrafficStats.getUidTxBytes(uid));
        rx.sample(rxBytes, now);
        tx.sample(txBytes, now);
    }

    private static long safe(long v) {
        // TrafficStats returns UNSUPPORTED (-1) when a counter isn't available.
        return v == TrafficStats.UNSUPPORTED ? TrafficRateMeter.UNSUPPORTED : v;
    }

    public TrafficRateMeter rxMeter() { return rx; }
    public TrafficRateMeter txMeter() { return tx; }

    /** Add everything this collector knows to the snapshot. */
    public void contribute(TelemetrySnapshot.Builder b, boolean allowPublicIpLookup) {
        long now = SystemClock.elapsedRealtime();
        ConnectivityManager cm = cm();
        if (cm == null) { b.unsupported(K_TRANSPORT, SRC_CM); return; }

        NetworkCapabilities caps = null;
        LinkProperties link = null;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    caps = cm.getNetworkCapabilities(active);
                    link = cm.getLinkProperties(active);
                }
            }
        } catch (Throwable ignored) { }

        b.value(K_TRANSPORT, transportLabel(), "", SRC_CM, now);

        if (caps == null) {
            b.unsupported(K_INTERNET, SRC_CM);
            b.unsupported(K_VPN, SRC_CM);
            b.unsupported(K_METERED, SRC_CM);
        } else {
            boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean validated = Build.VERSION.SDK_INT >= 23
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            boolean captive = Build.VERSION.SDK_INT >= 23
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
            String internet = captive ? "Captive portal"
                    : validated ? "Validated"
                    : hasInternet ? "No validated internet" : "No internet";
            b.value(K_INTERNET, internet, "", SRC_CM, now);
            b.value(K_VPN, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "Active" : "Inactive",
                    "", SRC_CM, now);
            boolean metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            b.value(K_METERED, metered ? "Metered" : "Unmetered", "", SRC_CM, now);
        }

        // Addresses, gateway and DNS come from LinkProperties (no extra permission).
        boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        boolean onWifi = caps != null && !vpn && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean onCell = caps != null && !vpn && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        String v4 = "", v6 = "";
        if (link != null) {
            b.value("net_interface", link.getInterfaceName(), "", SRC_LP, now);
            if (link.getMtu() > 0) b.value("net_mtu", String.valueOf(link.getMtu()), "bytes", SRC_LP, now);
            if (Build.VERSION.SDK_INT >= 28) b.value("net_private_dns", link.isPrivateDnsActive()
                    ? "Active" + (link.getPrivateDnsServerName() == null ? "" : " · " + link.getPrivateDnsServerName())
                    : "Not active", "", SRC_LP, now);
            try {
                for (android.net.LinkAddress la : link.getLinkAddresses()) {
                    String host = la.getAddress() == null ? "" : la.getAddress().getHostAddress();
                    if (host == null || host.isEmpty()) continue;
                    if (la.getAddress() instanceof java.net.Inet4Address) { if (v4.isEmpty()) v4 = host; }
                    else if (v6.isEmpty() && !host.startsWith("fe80")) v6 = host;
                }
            } catch (Throwable ignored) { }
            StringBuilder dns = new StringBuilder();
            try {
                for (java.net.InetAddress d : link.getDnsServers()) {
                    if (d == null || d.getHostAddress() == null) continue;
                    if (dns.length() > 0) dns.append(", ");
                    dns.append(d.getHostAddress());
                }
            } catch (Throwable ignored) { }
            if (dns.length() > 0) b.value(K_DNS, dns.toString(), "", SRC_LP, now);
            else b.unsupported(K_DNS, SRC_LP);

            String gw = gateway(link);
            if (!gw.isEmpty()) b.value(K_GATEWAY, gw, "", SRC_LP, now);
            else b.unsupported(K_GATEWAY, SRC_LP);
        } else {
            b.unsupported(K_DNS, SRC_LP);
            b.unsupported(K_GATEWAY, SRC_LP);
        }

        // "Phone IP (Wi-Fi)" and "Cellular interface IP" are the same underlying address seen on
        // different transports — never presented as two independent facts.
        if (onWifi) {
            if (!v4.isEmpty()) b.value(K_PHONE_IP, v4, "", SRC_LP, now); else b.unsupported(K_PHONE_IP, SRC_LP);
            b.unsupported(K_CELL_IP, SRC_LP);
        } else if (onCell) {
            if (!v4.isEmpty()) b.value(K_CELL_IP, v4, "", SRC_LP, now); else b.unsupported(K_CELL_IP, SRC_LP);
            b.unsupported(K_PHONE_IP, SRC_LP);
        } else {
            b.unsupported(K_PHONE_IP, SRC_LP);
            b.unsupported(K_CELL_IP, SRC_LP);
        }
        if (!v6.isEmpty()) b.value(K_PHONE_IP6, v6, "", SRC_LP, now); else b.unsupported(K_PHONE_IP6, SRC_LP);

        b.value("net_ip", (v4 + (v6.isEmpty() ? "" : " · " + v6)).trim(), "(default connection)", SRC_LP, now);
        contributeWifi(b, onWifi, now);

        b.value(K_LAST_CHANGE, lastChangeElapsed <= 0 ? "since start"
                : TelemetrySnapshot.duration(SystemClock.elapsedRealtime() - lastChangeElapsed) + " ago",
                "", SRC_CM, now);

        // Traffic — IRIS's UID. Unavailable counters stay unavailable.
        if (rx.supported() && rx.hasRate()) b.value(K_RX_RATE, TelemetrySnapshot.rate(rx.rate()), "", SRC_TS, now);
        else if (!rx.supported()) b.unsupported(K_RX_RATE, SRC_TS);
        else b.value(K_RX_RATE, "measuring\u2026", "", SRC_TS, now);
        if (tx.supported() && tx.hasRate()) b.value(K_TX_RATE, TelemetrySnapshot.rate(tx.rate()), "", SRC_TS, now);
        else if (!tx.supported()) b.unsupported(K_TX_RATE, SRC_TS);
        else b.value(K_TX_RATE, "measuring\u2026", "", SRC_TS, now);

        if (rx.supported() && tx.supported()) {
            b.value(K_SESSION, "\u2193 " + TelemetrySnapshot.bytes(rx.sessionBytes())
                    + "  \u2191 " + TelemetrySnapshot.bytes(tx.sessionBytes()), "", SRC_TS, now);
        } else {
            b.unsupported(K_SESSION, SRC_TS);
        }

        long devRx = safe(TrafficStats.getTotalRxBytes()), devTx = safe(TrafficStats.getTotalTxBytes());
        if (devRx >= 0 && devTx >= 0) {
            b.value(K_DEVICE_TOTAL, "\u2193 " + TelemetrySnapshot.bytes(devRx)
                    + "  \u2191 " + TelemetrySnapshot.bytes(devTx) + " (device-wide, since boot)",
                    "", SRC_TS, now);
        } else {
            b.unsupported(K_DEVICE_TOTAL, SRC_TS);
        }

        // Off by default: finding the internet-facing address means contacting an outside service.
        if (allowPublicIpLookup) b.value(K_PUBLIC_IP, "Not queried: external lookup is not implemented", "", "local-only telemetry", now);
        else b.value(K_PUBLIC_IP, "Not queried", "", "disabled by default", now);
    }

    private void contributeWifi(TelemetrySnapshot.Builder b, boolean onWifi, long now) {
        if (!onWifi) {
            b.unsupported(K_WIFI_NAME, SRC_WIFI);
            b.unsupported(K_WIFI_SIGNAL, SRC_WIFI);
            b.unsupported(K_WIFI_FREQ, SRC_WIFI);
            b.unsupported(K_WIFI_LINK, SRC_WIFI);
            return;
        }
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info == null) {
                b.unsupported(K_WIFI_NAME, SRC_WIFI);
                b.unsupported(K_WIFI_SIGNAL, SRC_WIFI);
                b.unsupported(K_WIFI_FREQ, SRC_WIFI);
                b.unsupported(K_WIFI_LINK, SRC_WIFI);
                return;
            }
            String ssid = info.getSSID();
            if (ssid != null) ssid = ssid.replace("\"", "");
            // The platform redacts the SSID unless location permission is held.
            boolean redacted = ssid == null || ssid.isEmpty()
                    || ssid.equalsIgnoreCase("<unknown ssid>") || ssid.equals("0x");
            if (redacted) b.permission(K_WIFI_NAME, SRC_WIFI + " (needs location)");
            else b.value(K_WIFI_NAME, ssid, "", SRC_WIFI, now);

            int rssi = info.getRssi();
            if (rssi > -127 && rssi < 0) {
                b.value(K_WIFI_SIGNAL, rssi + " dBm (" + signalWords(rssi) + ")", "", SRC_WIFI, now);
            } else {
                b.unsupported(K_WIFI_SIGNAL, SRC_WIFI);
            }
            if (Build.VERSION.SDK_INT >= 21 && info.getFrequency() > 0)
                b.value(K_WIFI_FREQ, info.getFrequency() + " MHz", "", SRC_WIFI, now);
            else b.unsupported(K_WIFI_FREQ, SRC_WIFI);
            if (info.getLinkSpeed() > 0)
                // Link speed is the radio negotiation, NOT internet download speed.
                b.value(K_WIFI_LINK, info.getLinkSpeed() + " Mbps (Wi-Fi link, not internet speed)",
                        "", SRC_WIFI, now);
            else b.unsupported(K_WIFI_LINK, SRC_WIFI);
        } catch (Throwable t) {
            b.permission(K_WIFI_NAME, SRC_WIFI);
            b.unsupported(K_WIFI_SIGNAL, SRC_WIFI);
            b.unsupported(K_WIFI_FREQ, SRC_WIFI);
            b.unsupported(K_WIFI_LINK, SRC_WIFI);
        }
    }

    private static String signalWords(int rssi) {
        if (rssi >= -55) return "excellent";
        if (rssi >= -67) return "good";
        if (rssi >= -78) return "fair";
        return "weak";
    }

    private static String gateway(LinkProperties link) {
        try {
            for (android.net.RouteInfo r : link.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() != null) {
                    String g = r.getGateway().getHostAddress();
                    if (g != null && !g.isEmpty() && !g.startsWith("::")) return g;
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    public String transportLabel() {
        try {
            ConnectivityManager cm = cm();
            if (cm == null || Build.VERSION.SDK_INT < 23) return "";
            Network active = cm.getActiveNetwork();
            NetworkCapabilities c = active == null ? null : cm.getNetworkCapabilities(active);
            if (c == null) return "No connection";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Cellular";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            return "Other";
        } catch (Throwable t) { return ""; }
    }

    private ConnectivityManager cm() {
        try { return (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE); }
        catch (Throwable t) { return null; }
    }
}

