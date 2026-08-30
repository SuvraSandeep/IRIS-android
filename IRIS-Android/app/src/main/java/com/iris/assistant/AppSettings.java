package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    public static final String MODE_WAKE = "wake";
    public static final String MODE_TAP = "tap";
    public static final String MODE_CONTINUOUS = "continuous";
    public static final String LOG_COMMANDS = "commands";
    public static final String LOG_FULL = "full";
    public static final String LOG_OFF = "off";

    private static final String PREFS = "iris_settings_v2";
    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String listeningMode() { return prefs.getString("listening_mode", MODE_WAKE); }
    public void setListeningMode(String value) { prefs.edit().putString("listening_mode", value).apply(); }
    public String logMode() { return prefs.getString("log_mode", LOG_COMMANDS); }
    public void setLogMode(String value) { prefs.edit().putString("log_mode", value).apply(); }
    public int retentionDays() { return prefs.getInt("retention_days", 7); }
    public void setRetentionDays(int value) { prefs.edit().putInt("retention_days", value).apply(); }
    public String personality() { return prefs.getString("personality", "Sarcastic"); }
    public void setPersonality(String value) { prefs.edit().putString("personality", value).apply(); }
    public boolean requireUnlock() { return prefs.getBoolean("require_unlock", true); }
    public void setRequireUnlock(boolean value) { prefs.edit().putBoolean("require_unlock", value).apply(); }
    public boolean haptics() { return prefs.getBoolean("haptics", true); }
    public void setHaptics(boolean value) { prefs.edit().putBoolean("haptics", value).apply(); }
    public boolean voiceReplies() { return prefs.getBoolean("voice_replies", true); }
    public void setVoiceReplies(boolean value) { prefs.edit().putBoolean("voice_replies", value).apply(); }
    public boolean preferOnDevice() { return prefs.getBoolean("prefer_on_device", true); }
    public void setPreferOnDevice(boolean value) { prefs.edit().putBoolean("prefer_on_device", value).apply(); }
    public String preferredMicrophone() { return prefs.getString("preferred_microphone", "Automatic"); }
    public void setPreferredMicrophone(String value) { prefs.edit().putString("preferred_microphone", value).apply(); }
    public String languageTag() { return prefs.getString("language_tag", "system"); }
    public void setLanguageTag(String value) { prefs.edit().putString("language_tag", value).apply(); }
    public String resolvedLanguageTag() {
        String value = languageTag();
        if ("hinglish".equals(value)) return "en-IN";
        return "system".equals(value) ? java.util.Locale.getDefault().toLanguageTag() : value;
    }
    public float textScale() { return prefs.getFloat("text_scale", 1.0f); }
    public void setTextScale(float value) { prefs.edit().putFloat("text_scale", value).apply(); }
    public String hfToken() { return prefs.getString("hf_token", ""); }
    public void setHfToken(String value) { prefs.edit().putString("hf_token", value == null ? "" : value.trim()).apply(); }
    public boolean speakerVerification() { return prefs.getBoolean("speaker_verification", true); }
    public void setSpeakerVerification(boolean value) { prefs.edit().putBoolean("speaker_verification", value).apply(); }
    public float speakerThreshold() { return prefs.getFloat("speaker_threshold", 0.70f); }
    public void setSpeakerThreshold(float value) { prefs.edit().putFloat("speaker_threshold", value).apply(); }
}
