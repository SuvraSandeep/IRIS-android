package com.iris.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coordinates audio hardware routing, device callbacks, audio focus,
 * and media key dispatch for voice interactions.
 */
public class VoicePipelineCoordinator {

    public interface RouteListener {
        void onRouteChanged(String label);
    }

    private final Context context;
    private final Handler handler;
    private final RouteListener routeListener;
    private AudioManager audioManager;
    private AudioDeviceCallback audioDeviceCallback;
    private Object speechFocusRequest;
    private boolean commandPausedMedia;
    private boolean btMicAllowed = true;
    private String currentMicrophoneLabel = "Phone";

    public VoicePipelineCoordinator(Context context, Handler handler, RouteListener routeListener) {
        this.context = context.getApplicationContext();
        this.handler = handler != null ? handler : new Handler(Looper.getMainLooper());
        this.routeListener = routeListener;
    }

    public void setBtMicAllowed(boolean allowed) {
        this.btMicAllowed = allowed;
    }

    public boolean isBtMicAllowed() {
        return btMicAllowed;
    }

    public String getMicrophoneLabel() {
        return currentMicrophoneLabel;
    }

    public synchronized String configureAudioRoute() {
        if (audioManager == null) {
            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }
        currentMicrophoneLabel = AudioRouteController.observed;
        return currentMicrophoneLabel;
    }

    public synchronized void registerAudioChanges() {
        if (audioManager == null) {
            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null || audioDeviceCallback != null) return;
        audioDeviceCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) { refreshAudioRoute(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) { refreshAudioRoute(); }
        };
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
    }

    public void refreshAudioRoute() {
        handler.postDelayed(() -> {
            String label = configureAudioRoute();
            if (routeListener != null) {
                routeListener.onRouteChanged(label);
            }
        }, 300);
    }

    public synchronized void releaseAudioRoute() {
        if (audioManager != null && audioDeviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
            } catch (Exception ignored) { }
            audioDeviceCallback = null;
        }
        audioManager = null;
    }

    public void logDetectedInputDevices() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            StringBuilder sb = new StringBuilder("Input devices:");
            List<AudioDeviceInfo> devices = Build.VERSION.SDK_INT >= 31
                    ? audioManager.getAvailableCommunicationDevices()
                    : arrayToList(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS));
            if (devices.isEmpty()) sb.append(" (none reported)");
            for (AudioDeviceInfo d : devices) {
                sb.append(' ').append('[').append(d.getType()).append(' ')
                        .append(readableDeviceName(d)).append(']');
            }
            Log.i("IRIS", sb.toString());
            LogStore.append(context, "AUDIO ROUTE", sb.toString());
        } catch (Throwable ignored) { }
    }

    public boolean isBluetoothMic(AudioDeviceInfo device) {
        if (device == null) return false;
        int t = device.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    public AudioDeviceInfo chooseDevice(List<AudioDeviceInfo> devices, String preference) {
        boolean musicPlaying = false;
        try {
            musicPlaying = audioManager != null && audioManager.isMusicActive();
        } catch (Throwable ignored) { }
        return chooseDevice(devices, preference, btMicAllowed && !musicPlaying);
    }

    public AudioDeviceInfo chooseDevice(List<AudioDeviceInfo> devices, String preference, boolean allowBluetoothInAuto) {
        AudioDeviceInfo builtIn = null, btDev = null, wiredDev = null;
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) builtIn = device;
            boolean bluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
            boolean wired = type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
            if (bluetooth) btDev = device;
            if (wired) wiredDev = device;
        }
        if ("Phone".equals(preference)) return builtIn;
        if ("Bluetooth".equals(preference)) return btDev != null ? btDev : builtIn;
        if ("Wired / USB".equals(preference)) return wiredDev != null ? wiredDev : builtIn;
        if (wiredDev != null) return wiredDev;
        if (btDev != null && allowBluetoothInAuto) return btDev;
        return builtIn;
    }

    public static List<AudioDeviceInfo> arrayToList(AudioDeviceInfo[] devices) {
        List<AudioDeviceInfo> result = new ArrayList<>();
        if (devices != null) Collections.addAll(result, devices);
        return result;
    }

    public static String readableDeviceName(AudioDeviceInfo device) {
        if (device == null) return "Unknown";
        int type = device.getType();
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            return "Phone microphone";
        String product = device.getProductName() == null ? "" : device.getProductName().toString().trim();
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return (product.isEmpty() ? "Bluetooth headset" : product) + " (Bluetooth)";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return (product.isEmpty() ? "Wired headset" : product) + " (wired)";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return (product.isEmpty() ? "USB headset" : product) + " (USB)";
            default:
                if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                    return (product.isEmpty() ? "Bluetooth headset" : product) + " (Bluetooth)";
                return product.isEmpty() ? "Phone microphone" : product + " microphone";
        }
    }

    public void requestSpeechFocus() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            if (!commandPausedMedia && audioManager.isMusicActive()) {
                commandPausedMedia = true;
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
            }
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .build();
                if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    speechFocusRequest = req;
                }
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception ignored) { }
    }

    public void abandonSpeechFocus() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= 26 && speechFocusRequest instanceof AudioFocusRequest) {
                audioManager.abandonAudioFocusRequest((AudioFocusRequest) speechFocusRequest);
                speechFocusRequest = null;
            } else {
                audioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignored) { }
    }

    public void finishCommandMediaResume() {
        if (!commandPausedMedia) return;
        commandPausedMedia = false;
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
        } catch (Exception ignored) { }
    }

    public void resetCommandPausedMedia() {
        commandPausedMedia = false;
    }
}
