package com.iris.assistant;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Device links panel: what is actually connected, and what role it plays in audio.
 *
 * Honesty rules:
 *   - Paired is NOT connected. Paired devices are labelled "Paired" until something observable
 *     says otherwise.
 *   - No single API guarantees a full inventory, so this combines GATT-profile connections with
 *     AudioManager device routing, and says so.
 *   - Battery level is shown only if the platform actually exposes it.
 *   - No scanning: discovery is a separate, explicit user action, not part of this dashboard.
 */
public final class BluetoothTelemetryCollector {

    public static final String K_BT_STATE = "bt_state";
    public static final String K_BT_SUMMARY = "bt_summary";
    public static final String K_AUDIO_OUT = "audio_out";
    public static final String K_AUDIO_IN = "audio_in";
    private static final String SRC = "BluetoothManager+AudioManager";

    /** One row in the Device Links panel. */
    public static final class DeviceRow {
        public final String name;
        public final String category;
        public final String connection;      // "Connected" / "Paired" / "Visible"
        public final String detail;          // "Media audio", "BLE • Visible", …
        public final boolean audioActive;
        public final String battery;         // "" when the platform doesn't expose it

        DeviceRow(String name, String category, String connection, String detail,
                  boolean audioActive, String battery) {
            this.name = name; this.category = category; this.connection = connection;
            this.detail = detail; this.audioActive = audioActive; this.battery = battery;
        }
    }

    private final Context ctx;
    private final TelemetryEventLog log;
    private String lastRoute = "";

    public BluetoothTelemetryCollector(Context context, TelemetryEventLog log) {
        this.ctx = context.getApplicationContext();
        this.log = log;
    }

    private boolean canUseBluetooth() {
        if (Build.VERSION.SDK_INT < 31) return true;   // legacy BLUETOOTH permission is install-time
        return ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public void contribute(TelemetrySnapshot.Builder b) {
        long now = SystemClock.elapsedRealtime();
        // Audio routing first — it needs no Bluetooth permission and is the most reliable signal.
        String out = audioRoute(true), in = IrisListeningService.isRunning && !IrisListeningService.currentMic.isEmpty()
                ? IrisListeningService.currentMic : "Not currently selected by IRIS";
        if (!out.isEmpty()) b.value(K_AUDIO_OUT, out, "", "AudioManager", now); else b.unsupported(K_AUDIO_OUT, "AudioManager");
        if (!in.isEmpty()) b.value(K_AUDIO_IN, in, "", "AudioManager", now); else b.unsupported(K_AUDIO_IN, "AudioManager");
        if (log != null && !out.isEmpty()) {
            String route = "Available outputs: " + out + " · input: " + in;
            if (!route.equals(lastRoute)) {
                lastRoute = route;
                log.add(TelemetryEventLog.Category.AUDIO, route);
            }
        }

        if (!canUseBluetooth()) {
            b.permission(K_BT_STATE, "BLUETOOTH_CONNECT");
            b.permission(K_BT_SUMMARY, "BLUETOOTH_CONNECT");
            b.permission("bt_devices", "BLUETOOTH_CONNECT");
            return;
        }
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null) { b.unsupported(K_BT_STATE, SRC); b.unsupported(K_BT_SUMMARY, SRC); return; }
            b.value(K_BT_STATE, adapter.isEnabled() ? "On" : "Off", "", SRC, now);
            StringBuilder names = new StringBuilder();
            for (DeviceRow row : devices()) { if (names.length() > 0) names.append("; "); names.append(row.name).append(" (observed via ").append(row.detail).append(")"); }
            b.value("bt_devices", !adapter.isEnabled() ? "Bluetooth off" : names.length() == 0
                    ? "No connected devices visible to IRIS; Android may hide some connections" : names.toString(), "", SRC, now);
            // devices() now returns connected devices only, deduplicated by address, so this
            // count matches exactly what the panel lists.
            int connected = devices().size();
            b.value(K_BT_SUMMARY, connected == 0 ? "None visible"
                    : connected + " observed", "", SRC, now);
        } catch (Throwable t) {
            b.unsupported(K_BT_STATE, SRC);
            b.unsupported(K_BT_SUMMARY, SRC);
        }
    }

    /**
     * Rows for the panel: ONLY devices that are actually connected, one row per device.
     * Paired-but-disconnected devices are deliberately not listed.
     */
    public List<DeviceRow> devices() {
        List<DeviceRow> rows = new ArrayList<>();
        if (!canUseBluetooth()) return rows;
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return rows;

            // Deduplicate by hardware address: one physical device = one row, even when it
            // reports several profiles or both a GATT link and an audio route.
            java.util.Map<String, DeviceRow> byAddress = new java.util.LinkedHashMap<>();

            // 1. GATT-profile connections (documented to cover supported profiles only).
            try {
                for (android.bluetooth.BluetoothDevice d :
                        bm.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)) {
                    if (d != null && d.getAddress() != null) {
                        byAddress.put(d.getAddress(), new DeviceRow(safeName(d).isEmpty()?"Unnamed BLE device":safeName(d), category(d), "Connected", "GATT", false, battery(d)));
                    }
                }
            } catch (Throwable ignored) { }

            // 2. Classic Bluetooth profiles (HEADSET, A2DP) — smartwatches and most companion-app
            // wearables connect over these or a proprietary profile, not GATT, so GATT alone
            // misses them. HID_DEVICE is deliberately not queried here: it is a restricted/
            // hidden-API surface on several Android versions, so relying on it risks a build or
            // runtime failure on this constant. Non-audio companion devices (most watches) are
            // instead picked up by the bonded-device fallback below.
            try {
                int[] classicProfiles = {
                        android.bluetooth.BluetoothProfile.HEADSET,
                        android.bluetooth.BluetoothProfile.A2DP
                };
                for (int profile : classicProfiles) {
                    try {
                        for (android.bluetooth.BluetoothDevice d : bm.getConnectedDevices(profile)) {
                            if (d != null && d.getAddress() != null && !byAddress.containsKey(d.getAddress())) {
                                byAddress.put(d.getAddress(), new DeviceRow(
                                        safeName(d).isEmpty() ? "Unnamed Bluetooth device" : safeName(d),
                                        category(d), "Connected", profileName(profile), profile == android.bluetooth.BluetoothProfile.A2DP, battery(d)));
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }

            // 3. Fallback for profiles with no BluetoothManager.getConnectedDevices support
            // (e.g. HID_DEVICE on some OEM skins, or a proprietary companion-app profile):
            // ask each bonded device directly via the public isConnected() method.
            try {
                for (android.bluetooth.BluetoothDevice d : adapter.getBondedDevices()) {
                    if (d == null || d.getAddress() == null || byAddress.containsKey(d.getAddress())) continue;
                    try {
                        java.lang.reflect.Method m = d.getClass().getMethod("isConnected");
                        Object connected = m.invoke(d);
                        if (Boolean.TRUE.equals(connected)) {
                            byAddress.put(d.getAddress(), new DeviceRow(
                                    safeName(d).isEmpty() ? "Unnamed Bluetooth device" : safeName(d),
                                    category(d), "Connected", "Bonded \u00b7 active", false, battery(d)));
                        }
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }

            // AudioManager reports attached endpoints, not proof of active playback.
            AudioManager am = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                if (!isBt(d.getType())) continue;
                String address = Build.VERSION.SDK_INT >= 28 ? d.getAddress() : "";
                String key = address == null || address.isEmpty() ? "endpoint:" + d.getId() : address;
                if (byAddress.containsKey(key)) continue;
                String name = d.getProductName() == null ? "Bluetooth audio endpoint" : d.getProductName().toString();
                byAddress.put(key, new DeviceRow(name, "Audio endpoint", "Connected",
                        "Audio endpoint available; active playback not established", false, ""));
            }
            rows.addAll(byAddress.values());
        } catch (Throwable ignored) { }
        return rows;
    }

    private static boolean isBt(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_SPEAKER);
    }

    /** Human-readable name for a classic Bluetooth profile constant, for the detail column. */
    private static String profileName(int profile) {
        if (profile == android.bluetooth.BluetoothProfile.HEADSET) return "Headset";
        if (profile == android.bluetooth.BluetoothProfile.A2DP) return "Media audio";
        return "Bluetooth profile";
    }

    private String safeName(android.bluetooth.BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? "" : n; }
        catch (Throwable t) { return ""; }
    }

    /** Battery only when genuinely exposed; otherwise blank so the UI omits the field. */
    private String battery(android.bluetooth.BluetoothDevice d) {
        try {
            java.lang.reflect.Method m = d.getClass().getMethod("getBatteryLevel");
            Object v = m.invoke(d);
            if (v instanceof Integer) {
                int level = (Integer) v;
                if (level >= 0 && level <= 100) return level + "%";
            }
        } catch (Throwable ignored) { }
        return "";
    }

    private String category(android.bluetooth.BluetoothDevice d) {
        try {
            android.bluetooth.BluetoothClass c = d.getBluetoothClass();
            if (c == null) return "Bluetooth device";
            switch (c.getMajorDeviceClass()) {
                case android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO: return "Audio";
                case android.bluetooth.BluetoothClass.Device.Major.WEARABLE: return "Wearable";
                case android.bluetooth.BluetoothClass.Device.Major.PHONE: return "Phone";
                case android.bluetooth.BluetoothClass.Device.Major.COMPUTER: return "Computer";
                case android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL: return "Peripheral";
                case android.bluetooth.BluetoothClass.Device.Major.HEALTH: return "Health";
                default: return "Bluetooth device";
            }
        } catch (Throwable t) { return "Bluetooth device"; }
    }

    /** Current audio route name, or "" when unknown. */
    public String audioRoute(boolean output) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null || Build.VERSION.SDK_INT < 23) return "";
            AudioDeviceInfo[] devices = am.getDevices(output ? AudioManager.GET_DEVICES_OUTPUTS
                    : AudioManager.GET_DEVICES_INPUTS);
            java.util.Set<String> labels = new java.util.LinkedHashSet<>();
            for (AudioDeviceInfo d : devices) {
                String label = routeLabel(d.getType()); if (!label.isEmpty()) labels.add(label);
            }
            return android.text.TextUtils.join(", ", labels);
        } catch (Throwable t) { return ""; }
    }

    private static String routeLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Phone microphone";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Phone speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired headphones";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth media";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth headset";
            default:
                if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) return "Bluetooth LE headset";
                return "";
        }
    }
}

