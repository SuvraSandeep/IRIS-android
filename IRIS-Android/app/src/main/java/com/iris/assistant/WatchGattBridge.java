package com.iris.assistant;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Authenticated BLE GATT service for watch communication.
 * Provides fallback transport when loopback HTTP (127.0.0.1:18473) is blocked by newer OS/OEM runtimes.
 */
final class WatchGattBridge implements AutoCloseable {
    static final UUID SERVICE_UUID = UUID.fromString("00001847-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_COMMAND_UUID = UUID.fromString("00001848-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_RESPONSE_UUID = UUID.fromString("00001849-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final WatchBridge.Actions actions;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> connectedDevices = new CopyOnWriteArrayList<>();

    private volatile boolean closed;
    private volatile BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic responseCharacteristic;

    WatchGattBridge(Context context, WatchBridge.Actions actions) {
        this.context = context;
        this.actions = actions;
    }

    void start() {
        if (!WatchBridge.enabled(context)) return;
        if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null) return;
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return;

            gattServer = manager.openGattServer(context, gattServerCallback);
            if (gattServer == null) return;

            BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic commandCharacteristic = new BluetoothGattCharacteristic(
                    CHAR_COMMAND_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
            );

            responseCharacteristic = new BluetoothGattCharacteristic(
                    CHAR_RESPONSE_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ
            );

            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
            );
            responseCharacteristic.addDescriptor(cccd);

            service.addCharacteristic(commandCharacteristic);
            service.addCharacteristic(responseCharacteristic);
            gattServer.addService(service);
        } catch (Throwable ignored) { }
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!connectedDevices.contains(device)) connectedDevices.add(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (gattServer == null) return;
            if (CHAR_RESPONSE_UUID.equals(characteristic.getUuid())) {
                byte[] val = responseCharacteristic != null ? responseCharacteristic.getValue() : new byte[0];
                if (val == null) val = new byte[0];
                byte[] sub = offset < val.length ? java.util.Arrays.copyOfRange(val, offset, val.length) : new byte[0];
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, sub);
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            if (gattServer == null) return;
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                             boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            if (value == null || value.length == 0 || !CHAR_COMMAND_UUID.equals(characteristic.getUuid())) {
                return;
            }

            String payload = new String(value, StandardCharsets.UTF_8).trim();
            handleIncomingCommand(device, payload);
        }
    };

    private void handleIncomingCommand(BluetoothDevice device, String payload) {
        String token = null;
        String action = null;

        if (payload.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(payload);
                token = json.optString("token", "");
                action = json.optString("action", json.optString("path", ""));
            } catch (Exception ignored) { }
        } else if (payload.contains(":")) {
            int colon = payload.indexOf(':');
            token = payload.substring(0, colon).trim();
            action = payload.substring(colon + 1).trim();
        }

        if (token == null || action == null) {
            sendGattResponse(device, "Unsupported request");
            return;
        }

        try {
            if (!WatchBridge.authorized("Bearer " + token, WatchBridge.token(context))) {
                sendGattResponse(device, "Pairing required");
                return;
            }
        } catch (Exception e) {
            sendGattResponse(device, "Pairing required");
            return;
        }

        final String path = action.startsWith("/") ? action : "/" + action;
        if (!path.equals("/status") && !path.equals("/find") && !path.equals("/stop") && !path.equals("/talk")) {
            sendGattResponse(device, "Unsupported request");
            return;
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        mainHandler.post(() -> {
            if (closed || !WatchBridge.enabled(context)) {
                result.complete("Bridge disabled");
                return;
            }
            try {
                result.complete(actions.run(path));
            } catch (Exception e) {
                result.complete("Action unavailable");
            }
        });

        try {
            String reply = result.get(2500, TimeUnit.MILLISECONDS);
            sendGattResponse(device, reply);
        } catch (Exception e) {
            sendGattResponse(device, "Phone busy; request expired");
        }
    }

    private void sendGattResponse(BluetoothDevice target, String message) {
        if (gattServer == null || responseCharacteristic == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("message", message);
            byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
            responseCharacteristic.setValue(bytes);
            if (Build.VERSION.SDK_INT >= 33) {
                gattServer.notifyCharacteristicChanged(target, responseCharacteristic, false, bytes);
            } else {
                gattServer.notifyCharacteristicChanged(target, responseCharacteristic, false);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void close() {
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (Exception ignored) { }
            gattServer = null;
        }
        connectedDevices.clear();
    }
}
