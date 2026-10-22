package com.iris.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.view.KeyEvent;

/**
 * RAII session manager for transient speech audio focus and background media pause/resume.
 * Implements AutoCloseable to ensure audio focus is released and paused media is resumed
 * even if speech recognition, parsing, or command execution fails with an unhandled error.
 */
public final class AudioFocusSession implements AutoCloseable {

    private final Context context;
    private final AudioManager audioManager;
    private final boolean pauseActiveMedia;
    private Object focusRequest;
    private boolean focusGranted;
    private boolean mediaPaused;
    private boolean closed;
    private boolean autoResumeMediaOnClose = true;

    public AudioFocusSession(Context context, boolean pauseActiveMedia) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.audioManager = this.context != null
                ? (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE)
                : null;
        this.pauseActiveMedia = pauseActiveMedia;
        acquire();
    }

    public static AudioFocusSession acquire(Context context, boolean pauseActiveMedia) {
        return new AudioFocusSession(context, pauseActiveMedia);
    }

    private synchronized void acquire() {
        if (audioManager == null) return;
        try {
            if (pauseActiveMedia && audioManager.isMusicActive()) {
                mediaPaused = true;
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .build();
                if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    focusRequest = req;
                    focusGranted = true;
                }
            } else {
                int result = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                focusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
        } catch (Exception ignored) { }
    }

    public synchronized boolean isFocusGranted() {
        return focusGranted && !closed;
    }

    public synchronized boolean hasPausedMedia() {
        return mediaPaused;
    }

    /**
     * Disables automatic media playback resume on close(), e.g. when an asynchronous
     * voice playback or TTS engine will resume media later upon completion.
     */
    public synchronized void transferMediaResume() {
        this.autoResumeMediaOnClose = false;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void abandonFocusOnly() {
        if (closed) return;
        try {
            if (focusGranted && audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26 && focusRequest instanceof AudioFocusRequest) {
                    audioManager.abandonAudioFocusRequest((AudioFocusRequest) focusRequest);
                } else {
                    audioManager.abandonAudioFocus(null);
                }
            }
        } catch (Exception ignored) { }
        finally {
            focusRequest = null;
            focusGranted = false;
        }
    }

    public synchronized void resumeMedia() {
        if (mediaPaused) {
            mediaPaused = false;
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        }
    }

    private void sendMediaKey(int keyCode) {
        if (audioManager == null) return;
        try {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } catch (Exception ignored) { }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (focusGranted && audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26 && focusRequest instanceof AudioFocusRequest) {
                    audioManager.abandonAudioFocusRequest((AudioFocusRequest) focusRequest);
                } else {
                    audioManager.abandonAudioFocus(null);
                }
            }
        } catch (Exception ignored) { }
        finally {
            focusRequest = null;
            focusGranted = false;
            if (mediaPaused && autoResumeMediaOnClose) {
                mediaPaused = false;
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        }
    }
}
