package com.iris.assistant;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IrisListeningService extends Service implements RecognitionListener {
    public static final String ACTION_START = "com.iris.assistant.START";
    public static final String ACTION_STOP = "com.iris.assistant.STOP";
    public static final String ACTION_CONFIRM_CALL = "com.iris.assistant.CONFIRM_CALL";
    public static final String ACTION_CANCEL_CALL = "com.iris.assistant.CANCEL_CALL";
    public static final String ACTION_CHOOSE_CONTACT = "com.iris.assistant.CHOOSE_CONTACT";
    public static final String EVENT_STATE = "com.iris.assistant.EVENT_STATE";
    public static final String EVENT_TRANSCRIPT = "com.iris.assistant.EVENT_TRANSCRIPT";
    public static final String EVENT_CALL_PROMPT = "com.iris.assistant.EVENT_CALL_PROMPT";
    public static final String EVENT_DISAMBIGUATE = "com.iris.assistant.EVENT_DISAMBIGUATE";
    public static final String EVENT_TEACH = "com.iris.assistant.EVENT_TEACH";
    public static final String EVENT_LEVEL = "com.iris.assistant.EVENT_LEVEL";
    public static final String EVENT_MESSAGE = "com.iris.assistant.EVENT_MESSAGE";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_NUMBER = "number";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_NUMBERS = "numbers";
    public static final String EXTRA_MIC = "mic";
    public static final String EXTRA_RECOGNITION = "recognition";
    public static final String EXTRA_AUTH_REQUIRED = "auth_required";
    public static final String EXTRA_TEACH = "teach";
    public static final String EXTRA_LEVEL = "level";

    private static final String LISTENING_CHANNEL = "iris_listening_v2";
    private static final String CALL_CHANNEL = "iris_call_confirmation_v2";
    private static final int LISTENING_NOTIFICATION = 4201;
    private static final int CALL_NOTIFICATION = 4202;
    private static final int TEACH_NOTIFICATION = 4203;
    private static final Pattern CALL_PATTERN = Pattern.compile(
            "^(?:(?:please|can you|could you|just)\\s+)?"
            + "(?:call|dial|phone|ring|ring up|phone up|buzz|hit up"
            + "|get\\s+.+?\\s+on\\s+the\\s+(?:line|phone)"
            + "|talk\\s+to|speak\\s+to|connect\\s+(?:me\\s+)?to|reach)"
            + "\\s+(.+?)(?:\\s+(?:please|for me))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HINDI_CALL_PATTERN = Pattern.compile(
            "^(.+?)\\s+(?:ko\\s+(?:call|phone|ring)\\s+karo|se\\s+baat\\s+karo|ko\\s+phone\\s+karo)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REDIAL_PATTERN = Pattern.compile(
            "^(?:redial|call\\s+(?:again|back|the\\s+last\\s+(?:person|one|contact))|ring\\s+(?:again|back))$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUICK_ACTION_PATTERN = Pattern.compile(
            "^(?:what(?:\\s+is)?\\s+the\\s+time|time\\s*(?:please)?|what\\s+time\\s+is\\s+it"
            + "|battery|battery\\s+level|how\\s+much\\s+battery"
            + "|stop|shut\\s+up|quiet|silence|go\\s+to\\s+sleep"
            + "|help|what\\s+can\\s+you\\s+do)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIONSHIP_CALL_PATTERN = Pattern.compile(
            "^(?:(?:please|can you)\\s+)?(?:call|dial|phone|ring|talk\\s+to|reach)\\s+(?:my\\s+)?(.+?)(?:\\s+(?:please|for me))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY_PATTERN = Pattern.compile(
            "^(?:who\\s+did\\s+i\\s+call\\s*(?:last|today|yesterday)?"
            + "|how\\s+many\\s+times\\s+did\\s+i\\s+call\\s+(.+)"
            + "|when\\s+did\\s+i\\s+(?:last\\s+)?call\\s+(.+)"
            + "|call\\s+history|my\\s+calls)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMORY_PATTERN = Pattern.compile(
            "^(?:remember|note|save|store)\\s+(?:that\\s+)?(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_APP_PATTERN = Pattern.compile(
            "^(?:please\\s+)?(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTIFICATION_PATTERN = Pattern.compile(
            ".*\\b(?:notification|notifications|any\\s+(?:new\\s+)?message|new\\s+messages?"
            + "|who\\s+(?:texted|messaged|pinged)\\s+me|did\\s+(?:anyone|someone)\\s+(?:text|message)"
            + "|what\\s+did\\s+i\\s+miss|read\\s+(?:my\\s+)?(?:sms|text|texts|whatsapp|messages))\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORGET_PATTERN = Pattern.compile(
            "^(?:forget|delete|remove)\\s+(?:that|the\\s+last\\s+(?:memory|thing)|it)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RECALL_PATTERN = Pattern.compile(
            "^(?:what\\s+do\\s+you\\s+(?:know|remember)|tell\\s+me\\s+about\\s+(?:me|myself)|my\\s+(?:memories|info|memory))$",
            Pattern.CASE_INSENSITIVE);
    private static final String PHASE_WAKE = "wake";
    private static final String PHASE_COMMAND = "command";
    private static final String PHASE_CONFIRM = "confirm";

    public static volatile boolean isRunning;
    public static volatile String currentPhase = "off";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private WakeWordEngine wakeEngine;
    private AppSettings settings;
    private String phase = PHASE_WAKE;
    private String microphoneLabel = "Phone microphone";
    private String recognitionLabel = "System speech service";
    private String pendingName;
    private String pendingNumber;
    private java.util.List<ContactMatch> pendingCandidates;
    private int pendingCandidateIndex;
    private AudioManager audioManager;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private AudioDeviceCallback audioDeviceCallback;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private int confirmationRetries;
    private String lastMemoryId;
    private SpeakerVerifier speakerVerifier;
    private VoskEngine voskEngine;
    private boolean voskReady;
    private LlmAgent llmAgent;
    private volatile boolean llmReady;
    private final ConversationManager conversation = new ConversationManager();
    private long lastLevelBroadcast;
    private Runnable commandTimeout;
    private Runnable confirmTimeout;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        createNotificationChannels();
        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                textToSpeech.setLanguage(Locale.getDefault());
                // Warm up TTS engine with silent utterance — some devices need this
                textToSpeech.speak(" ", TextToSpeech.QUEUE_FLUSH, null, "warmup");
                LogStore.append(IrisListeningService.this, "TTS", "Ready: " + textToSpeech.getDefaultEngine());
            } else {
                LogStore.append(IrisListeningService.this, "TTS", "FAILED (status " + status + ")");
            }
        });
        speakerVerifier = new SpeakerVerifier();
        speakerVerifier.loadModel(this);
        voskEngine = new VoskEngine();
        voskEngine.init(this, new VoskEngine.InitListener() {
            @Override public void onReady() {
                voskReady = true;
                LogStore.append(IrisListeningService.this, "VOSK", "Voice model ready");
            }
            @Override public void onError(String message) {
                voskReady = false;
                LogStore.append(IrisListeningService.this, "VOSK", "Model unavailable, using fallback: " + message);
            }
        });
        // Load the Gemma LLM on a background thread (heavy — may take several seconds)
        llmAgent = new LlmAgent();
        new Thread(() -> {
            boolean ok = llmAgent.loadModel(this);
            llmReady = ok;
            LogStore.append(this, "LLM", ok ? "Gemma AI ready" : "No LLM model, using rule-based chat");
        }, "IRIS-LLM-Load").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopIris("Listening switched off");
            return START_NOT_STICKY;
        }
        if (ACTION_CONFIRM_CALL.equals(action)) {
            cancelCallNotification();
            placeCall(intent.getStringExtra(EXTRA_NAME), intent.getStringExtra(EXTRA_NUMBER));
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL_CALL.equals(action)) {
            cancelCallNotification();
            String name = intent.getStringExtra(EXTRA_NAME);
            LogStore.append(this, "CANCELLED", "Call to " + (name == null ? "contact" : name));
            speak(personalityLine("cancel", name));
            broadcastMessage("Call cancelled. Consider it unsaid.");
            rearmAfterAction();
            return START_STICKY;
        }
        if (ACTION_CHOOSE_CONTACT.equals(action)) {
            requestCallConfirmation(intent.getStringExtra(EXTRA_NAME), intent.getStringExtra(EXTRA_NUMBER));
            return START_STICKY;
        }

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            broadcastMessage("Microphone permission is required.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(LISTENING_NOTIFICATION, listeningNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(LISTENING_NOTIFICATION, listeningNotification());
        }

        if (!isRunning) {
            isRunning = true;
            recognitionLabel = resolveRecognitionLabel();
            microphoneLabel = configureAudioRoute();
            registerAudioChanges();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iris:listening");
                wakeLock.acquire(60 * 60 * 1000L); // 1 hour max
            }
            LogStore.append(this, "START", settings.listeningMode() + " mode through " + microphoneLabel);
        }
        broadcastState(true, phase);
        beginForSelectedMode();
        return START_STICKY;
    }

    private void beginForSelectedMode() {
        String mode = settings.listeningMode();
        if (AppSettings.MODE_WAKE.equals(mode)) startWakeDetection();
        else startCommandRecognition();
    }

    private String resolveRecognitionLabel() {
        if (Build.VERSION.SDK_INT >= 31 && settings.preferOnDevice()
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return "On-device speech ready";
        return settings.preferOnDevice() ? "System speech fallback" : "System speech service";
    }

    private void startWakeDetection() {
        destroyRecognizer();
        ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
        if (!wake.isReady()) {
            broadcastMessage("Train a custom wake phrase first.");
            LogStore.append(this, "SETUP", "Wake mode needs training");
            stopIris("Wake phrase not trained");
            return;
        }
        phase = PHASE_WAKE;
        currentPhase = phase;
        broadcastState(true, phase);

        // Give Vosk a brief chance to load; if not ready, use Android speech
        // recognition for wake (real STT — reliable, rejects noise). Never wait forever.
        if (!voskReady || voskEngine == null) {
            LogStore.append(this, "WAKE", "Vosk not ready — using Android speech recognition for wake");
            startAndroidWakeDetection(wake);
            return;
        }

        updateListeningNotification("Waiting for “" + wake.phrase + "”");
        // Vosk neural grammar-mode wake detection — only fires on the exact phrase
        voskEngine.startWakeDetection(wake.phrase, new VoskEngine.WakeListener() {
            @Override public void onWakeDetected() {
                if (!isRunning || !PHASE_WAKE.equals(phase)) return;
                voskEngine.stop();
                LogStore.append(IrisListeningService.this, "WAKE", wake.phrase + " detected (Vosk)");
                vibrate(45);
                broadcastMessage(timeGreeting() + " What can I do?");
                // Speak THEN listen — never cut off IRIS mid-sentence
                speakThenRun(timeGreeting() + " What can I do?", IrisListeningService.this::startCommandRecognition);
            }
            @Override public void onError(String message) {
                LogStore.append(IrisListeningService.this, "VOSK ERROR", message);
                // If Vosk keeps erroring, fall back to Android STT wake
                startAndroidWakeDetection(wake);
            }
        });
    }

    /**
     * Wake detection using Android's own SpeechRecognizer.
     * Continuously listens; if the recognized text contains the wake phrase,
     * wakes IRIS. Real speech recognition — rejects random noise, works on
     * every phone that has Google speech (which this device confirmed it does).
     */
    private void startAndroidWakeDetection(ProfileStore.WakeProfile wake) {
        if (!isRunning || !PHASE_WAKE.equals(phase)) return;
        updateListeningNotification("Waiting for “" + wake.phrase + "”");
        final String target = ProfileStore.normalize(wake.phrase);
        destroyRecognizer();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            broadcastMessage("Speech recognition unavailable on this device.");
            LogStore.append(this, "WAKE ERROR", "No speech recognition available");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                if (!isRunning || !PHASE_WAKE.equals(phase)) return;
                // Busy or client errors need a fresh recognizer, not just a restart
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        || error == SpeechRecognizer.ERROR_CLIENT) {
                    handler.postDelayed(() -> startAndroidWakeDetection(wake), 600);
                } else {
                    handler.postDelayed(IrisListeningService.this::restartAndroidWake, 350);
                }
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                boolean detected = wakeHeard(matches, target);
                if (detected && isRunning && PHASE_WAKE.equals(phase)) {
                    LogStore.append(IrisListeningService.this, "WAKE", wake.phrase + " detected (Android STT)");
                    vibrate(45);
                    speakThenRun(timeGreeting() + " What can I do?", IrisListeningService.this::startCommandRecognition);
                } else if (isRunning && PHASE_WAKE.equals(phase)) {
                    handler.postDelayed(IrisListeningService.this::restartAndroidWake, 200);
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (wakeHeard(matches, target) && isRunning && PHASE_WAKE.equals(phase)) {
                    try { recognizer.stopListening(); } catch (Exception ignored) { }
                }
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        restartAndroidWake();
    }

    /** True if the wake phrase (or all of its words) appear in any recognition result. */
    private boolean wakeHeard(ArrayList<String> matches, String target) {
        if (matches == null || target.isEmpty()) return false;
        String[] targetWords = target.split("\\s+");
        for (String m : matches) {
            String norm = ProfileStore.normalize(m);
            if (norm.isEmpty()) continue;
            if (norm.contains(target)) return true;      // exact phrase heard
            // Or: all wake words present (handles minor STT word-order/filler)
            boolean all = true;
            for (String w : targetWords) {
                if (w.length() >= 2 && !norm.contains(w)) { all = false; break; }
            }
            if (all && targetWords.length > 0) return true;
        }
        return false;
    }

    private void restartAndroidWake() {
        if (!isRunning || !PHASE_WAKE.equals(phase) || recognizer == null) return;
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.resolvedLanguageTag());
            recognizer.startListening(intent);
        } catch (Exception e) {
            handler.postDelayed(this::restartAndroidWake, 500);
        }
    }

    private void startCommandRecognition() {
        stopWakeEngine();
        if (voskEngine != null) voskEngine.stop();
        phase = PHASE_COMMAND;
        currentPhase = phase;
        broadcastState(true, phase);
        updateListeningNotification("Listening for a call command");

        // Primary: Vosk streaming STT
        if (voskReady && voskEngine != null) {
            final boolean[] handled = {false};
            commandTimeout = () -> {
                if (isRunning && PHASE_COMMAND.equals(phase) && !handled[0]) {
                    voskEngine.stop();
                    broadcastMessage("No command heard. Going back to sleep.");
                    LogStore.append(this, "TIMEOUT", "Command window expired");
                    rearmAfterAction();
                }
            };
            handler.postDelayed(commandTimeout, 12_000);
            voskEngine.startListening(new VoskEngine.SttListener() {
                @Override public void onPartial(String text) {
                    if (!text.isEmpty()) broadcastTranscript(text);
                }
                @Override public void onFinal(String text) {
                    if (handled[0] || text.isEmpty()) return;
                    handled[0] = true;
                    if (commandTimeout != null) { handler.removeCallbacks(commandTimeout); commandTimeout = null; }
                    voskEngine.stop();
                    handleCommand(text);
                }
                @Override public void onError(String message) {
                    if (handled[0]) return;
                    LogStore.append(IrisListeningService.this, "VOSK STT ERROR", message);
                    rearmAfterAction();
                }
            });
            return;
        }

        // Fallback: Android SpeechRecognizer
        createRecognizer();
        if (recognizer == null) {
            broadcastMessage("Speech recognition is unavailable.");
            rearmAfterAction();
            return;
        }
        recognizerIntent = baseRecognizerIntent();
        handler.postDelayed(this::startRecognizerSafely, 180);
        commandTimeout = () -> { if (isRunning && PHASE_COMMAND.equals(phase)) { broadcastMessage("No command heard. Going back to sleep."); LogStore.append(this, "TIMEOUT", "Command window expired"); rearmAfterAction(); } };
        handler.postDelayed(commandTimeout, 20_000);
    }

    private void startConfirmationRecognition() {
        phase = PHASE_CONFIRM;
        currentPhase = phase;
        broadcastState(true, phase);
        createRecognizer();
        if (recognizer == null) return;
        recognizerIntent = baseRecognizerIntent();
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        handler.postDelayed(this::startRecognizerSafely, 350);
        confirmTimeout = () -> { if (isRunning && PHASE_CONFIRM.equals(phase)) { broadcastMessage("No answer. Call cancelled."); LogStore.append(this, "TIMEOUT", "Confirmation expired for " + (pendingName == null ? "contact" : pendingName)); cancelCallNotification(); rearmAfterAction(); } };
        handler.postDelayed(confirmTimeout, 15_000);
    }

    private Intent baseRecognizerIntent() {
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speech.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speech.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, settings.preferOnDevice());
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.resolvedLanguageTag());
        speech.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        return speech;
    }

    private void createRecognizer() {
        destroyRecognizer();
        try {
            if (Build.VERSION.SDK_INT >= 31 && settings.preferOnDevice()
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                recognitionLabel = "On-device speech";
            } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognitionLabel = "System speech service";
            }
            if (recognizer != null) recognizer.setRecognitionListener(this);
        } catch (Exception error) {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
                recognitionLabel = "System speech fallback";
            }
        }
    }

    private void startRecognizerSafely() {
        if (!isRunning || recognizer == null) return;
        try { recognizer.startListening(recognizerIntent); }
        catch (Exception error) {
            LogStore.append(this, "ERROR", "Speech start failed: " + error.getMessage());
            rearmAfterAction();
        }
    }

    private void handleCommand(String heard) {
        if (commandTimeout != null) { handler.removeCallbacks(commandTimeout); commandTimeout = null; }
        String clean = heard == null ? "" : heard.trim();
        if (clean.isEmpty()) {
            rearmAfterAction();
            return;
        }
        // Immediate cancel commands
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.matches("^(stop|cancel|shut up|quiet|go away|never mind|nevermind|nahi|ruk|bas)$")) {
            LogStore.append(this, "CANCELLED", "Voice cancel: " + clean);
            broadcastMessage("Okay, going quiet.");
            speak("Okay.");
            rearmAfterAction();
            return;
        }

        LogStore.append(this, "HEARD", clean);
        broadcastTranscript(clean);

        // 1. Check trained phrases first
        ProfileStore store = new ProfileStore(this);
        ProfileStore.Match learned = store.findMatch(clean);
        if (learned != null) {
            LogStore.append(this, "MATCH", "Learned phrase → " + learned.contactName
                    + " (" + Math.round(learned.confidence * 100) + "%)");
            requestCallConfirmation(learned.contactName, learned.phoneNumber);
            return;
        }

        String normalized = ProfileStore.normalize(clean);

        // 1b. Check memory corrections
        MemoryStore.Memory correction = MemoryStore.findCorrection(this, clean);
        if (correction != null && correction.detail != null && !correction.detail.isEmpty()) {
            LogStore.append(this, "MEMORY", "Correction: " + clean + " → " + correction.value);
            requestCallConfirmation(correction.value, correction.detail);
            return;
        }

        // 2. Relationship resolution ("call my wife", "ring my brother")
        String[] relationship = store.resolveRelationship(normalized.replaceAll("^(?:call|dial|phone|ring|talk to|reach)\\s+", ""));
        if (relationship != null && relationship[0].length() > 0 && relationship[1].length() > 0) {
            LogStore.append(this, "RELATIONSHIP", "Resolved \u2192 " + relationship[0]);
            requestCallConfirmation(relationship[0], relationship[1]);
            return;
        }

        // 2b. Memory voice commands (remember/forget/recall)
        Matcher memoryMatcher = MEMORY_PATTERN.matcher(normalized);
        if (memoryMatcher.matches()) {
            handleRemember(memoryMatcher.group(1).trim());
            return;
        }
        if (FORGET_PATTERN.matcher(normalized).matches()) {
            handleForget();
            return;
        }
        if (RECALL_PATTERN.matcher(normalized).matches()) {
            handleRecall();
            return;
        }

        // 3. Call history queries
        if (HISTORY_PATTERN.matcher(normalized).matches()) {
            handleHistory(normalized, store);
            return;
        }

        // 4. Quick actions (time, battery, stop, help) — lenient matching so
        // "tell me the time", "what's the time", "time now" all work
        if (isQuickAction(normalized)) {
            handleQuickAction(normalized);
            return;
        }

        // 5. Redial / call back
        if (REDIAL_PATTERN.matcher(normalized).matches()) {
            handleRedial(store);
            return;
        }

        // 5b. Open an installed app ("open WhatsApp", "launch camera")
        Matcher openMatcher = OPEN_APP_PATTERN.matcher(normalized);
        if (openMatcher.matches() && !containsCallVerb(normalized)) {
            if (openApp(openMatcher.group(1).trim())) return;
            // if no app matched, fall through to other handlers / chat
        }

        // 5c. Read phone notifications ("read my notifications", "who texted me")
        if (NOTIFICATION_PATTERN.matcher(normalized).matches() && !containsCallVerb(normalized)) {
            handleNotifications(normalized);
            return;
        }

        // 6. Call patterns (English)
        Matcher matcher = CALL_PATTERN.matcher(normalized);
        String requested = null;
        if (matcher.matches()) {
            requested = matcher.group(1).trim();
        }

        // 7. Hindi/Hinglish call patterns
        if (requested == null) {
            Matcher hindiMatcher = HINDI_CALL_PATTERN.matcher(normalized);
            if (hindiMatcher.matches()) requested = hindiMatcher.group(1).trim();
        }

        // 8. No pattern matched
        if (requested == null) {
            // Not a call command — treat as conversation
            handleChat(clean, normalized, store);
            return;
        }

        List<ContactMatch> candidates = resolveContacts(requested);
        if (candidates.isEmpty()) {
            broadcastMessage(hasPermission(Manifest.permission.READ_CONTACTS)
                    ? "I couldn't confidently match “" + requested + "”. Teach me once."
                    : "Contacts permission is needed to find “" + requested + "”.");
            LogStore.append(this, "NO MATCH", requested);
            requestCorrection(clean);
            rearmAfterAction();
            return;
        }
        ContactMatch first = candidates.get(0);
        // Verbally confirm the best match; "no" walks to the next candidate.
        pendingCandidates = candidates;
        pendingCandidateIndex = 0;
        confirmCandidate(0);
    }

    /** Verbally confirm a contact candidate by index; "no" advances to the next. */
    private void confirmCandidate(int index) {
        if (pendingCandidates == null || index >= pendingCandidates.size()) {
            cancelCallNotification();
            broadcastMessage("Okay, I won't call anyone.");
            speakThenRun("Okay, I won't call anyone.", this::rearmAfterAction);
            pendingCandidates = null;
            return;
        }
        pendingCandidateIndex = index;
        ContactMatch c = pendingCandidates.get(index);
        boolean multiple = pendingCandidates.size() > 1;
        String prompt = multiple ? "Did you mean " + c.name + "?" : "Call " + c.name + "?";
        requestCallConfirmation(c.name, c.number, prompt);
    }

    /**
     * Conversational handler — makes IRIS a chatbot, not just a dialer.
     * Answers questions from memory, greets, and chats. Only calls when
     * there's an explicit call command (handled earlier in handleCommand).
     */
    private final java.util.Random chatRandom = new java.util.Random();

    /** Pick a random response from variants for natural variety. */
    private String pick(String... options) {
        return options[chatRandom.nextInt(options.length)];
    }

    private void handleChat(String original, String normalized, ProfileStore store) {
        LogStore.append(this, "CHAT", original);

        // Try the real AI (Gemma) first, on a background thread
        if (llmReady && llmAgent != null && llmAgent.isReady()) {
            broadcastMessage("Thinking…");
            new Thread(() -> {
                String llmOut = llmAgent.generateReply(this, original, conversation.transcript());
                handler.post(() -> {
                    if (llmOut == null || llmOut.isEmpty()) {
                        ruleBasedChat(original, normalized, store); // fallback
                    } else {
                        conversation.add(original, llmOut.replaceAll("\\[.*?\\]", "").trim());
                        handleLlmOutput(llmOut, store);
                    }
                });
            }, "IRIS-LLM-Gen").start();
            return;
        }

        // Fallback: rule-based chat
        ruleBasedChat(original, normalized, store);
    }

    /** Parse the LLM output for action tags, else speak it as a chat reply. */
    private void handleLlmOutput(String out, ProfileStore store) {
        LogStore.append(this, "LLM", "Reply: " + out);
        // [CALL: name]
        java.util.regex.Matcher call = java.util.regex.Pattern
                .compile("\\[CALL:\\s*([^\\]]+)\\]", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(out);
        if (call.find()) {
            String who = call.group(1).trim();
            // Resolve via relationship, then contacts
            String[] rel = store.resolveRelationship(ProfileStore.normalize(who));
            if (rel != null && rel[0].length() > 0 && rel[1].length() > 0) {
                requestCallConfirmation(rel[0], rel[1]);
                return;
            }
            List<ContactMatch> matches = resolveContacts(who);
            if (!matches.isEmpty()) {
                requestCallConfirmation(matches.get(0).name, matches.get(0).number);
            } else {
                broadcastMessage("I couldn't find " + who + " in your contacts.");
                speakThenRun("I couldn't find " + who + " in your contacts.", this::rearmAfterAction);
            }
            return;
        }
        // [TIME]
        if (out.matches(".*\\[TIME\\].*")) { handleQuickAction("time"); return; }
        // [BATTERY]
        if (out.matches(".*\\[BATTERY\\].*")) { handleQuickAction("battery"); return; }
        // [REMEMBER: fact]
        java.util.regex.Matcher rem = java.util.regex.Pattern
                .compile("\\[REMEMBER:\\s*([^\\]]+)\\]", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(out);
        if (rem.find()) { handleRemember(rem.group(1).trim()); return; }

        // Plain conversational reply — strip any stray brackets
        String clean = out.replaceAll("\\[.*?\\]", "").trim();
        if (clean.isEmpty()) clean = "Okay.";
        broadcastMessage(clean);
        if ("Silent".equals(settings.personality())) {
            handler.postDelayed(this::rearmAfterAction, 800);
        } else {
            speakThenRun(clean, this::rearmAfterAction);
        }
    }

    private void ruleBasedChat(String original, String normalized, ProfileStore store) {
        String personality = settings.personality();
        boolean sarcastic = "Sarcastic".equals(personality);
        boolean professional = "Professional".equals(personality);
        boolean warm = "Warm".equals(personality);
        String reply;

        String ownerName = MemoryStore.ownerName(this);
        String namePart = ownerName != null ? " " + ownerName : "";

        // Identity questions
        if (normalized.matches(".*\\b(who am i|what.?s my name|what is my name|my name)\\b.*")) {
            if (ownerName != null) {
                reply = sarcastic ? pick("You're " + ownerName + ". Forgot already?",
                                          "Still " + ownerName + ", last I checked.",
                                          "You're " + ownerName + ". Want me to write it down?")
                        : professional ? "You are " + ownerName + "."
                        : warm ? pick("You're " + ownerName + ", of course!", "You're my favorite person, " + ownerName + "!")
                        : "You're " + ownerName + ".";
            } else {
                reply = "I don't know your name yet. Say \u201Cremember my name is\u2026\u201D to tell me.";
            }
        }
        // Who/what are you
        else if (normalized.matches(".*\\b(who are you|what are you|your name)\\b.*")) {
            reply = sarcastic ? pick("I'm IRIS. The voice in your phone that actually listens.",
                                      "IRIS. Think of me as the smart part of your phone.")
                    : professional ? "I am IRIS, your assistant."
                    : warm ? "I'm IRIS, your personal assistant and I'm always here for you."
                    : "I'm IRIS, your personal assistant. I can chat, remember things, and call your contacts.";
        }
        // Greetings
        else if (normalized.matches("^(hi|hello|hey|yo|hi there|hello there)\\b.*")) {
            reply = sarcastic ? pick("Well, look who's talking to their phone" + namePart + ".",
                                      "Oh hey" + namePart + ". Missed me?",
                                      "You rang" + namePart + "? Figuratively.")
                    : professional ? pick("Hello" + namePart + ". How can I help?", "Hello" + namePart + ". What do you need?")
                    : warm ? pick("Hey" + namePart + "! So good to hear you. What can I do?",
                                  "Hi" + namePart + "! Always a pleasure. What's up?")
                    : pick("Hey" + namePart + "! What can I do for you?", "Hi" + namePart + "! How can I help?");
        }
        // How are you
        else if (normalized.matches(".*\\bhow are you\\b.*")) {
            reply = sarcastic ? pick("Living the dream, trapped in your phone. You?",
                                      "Same as always \u2014 electric. How about you?",
                                      "Can't complain, nobody listens anyway. You?")
                    : professional ? "Functioning normally. How may I assist?"
                    : warm ? pick("I'm wonderful, thank you for asking! How are you?",
                                  "Doing lovely, especially now you're here! And you?")
                    : pick("I'm doing great, thanks! What about you?", "All good here! How about you?");
        }
        // Thanks
        else if (normalized.matches(".*\\b(thank you|thanks|thank u|thankyou)\\b.*")) {
            reply = sarcastic ? pick("Don't mention it. Really, don't.", "That's what I'm here for. Apparently.")
                    : professional ? "You're welcome."
                    : warm ? pick("Anytime! Happy to help.", "Of course! Always here for you.")
                    : pick("You're welcome!", "Anytime!", "No problem at all!");
        }
        // What can you do
        else if (normalized.matches(".*\\b(what can you do|help|your features|what do you do)\\b.*")) {
            reply = "I can call your contacts, tell the time, check battery, remember facts about you, and chat. Try \u201Ccall Mom\u201D or \u201Cremember my wife is Priya\u201D.";
        }
        // "what is my X" — look up in memory
        else if (normalized.matches(".*\\bmy\\s+(\\w+).*")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("my\\s+(\\w+)").matcher(normalized);
            String answer = null;
            if (m.find()) {
                String key = m.group(1);
                for (MemoryStore.Memory mem : MemoryStore.getAll(this)) {
                    if (mem.key.toLowerCase().contains(key) || key.contains(mem.key.toLowerCase())) {
                        answer = "Your " + mem.key + " is " + mem.value + ".";
                        break;
                    }
                }
            }
            reply = answer != null ? answer
                    : "I don't know that yet. You can tell me by saying \u201Cremember\u2026\u201D.";
        }
        // Yes/okay acknowledgements
        else if (normalized.matches("^(yes|yeah|ok|okay|sure|cool|nice)\\b.*")) {
            reply = sarcastic ? pick("Thrilling. What now?", "Riveting. Anything else?")
                    : professional ? "Understood. What would you like?"
                    : pick("Great! What would you like to do?", "Cool! What's next?");
        }
        // Fallback
        else {
            reply = sarcastic ? pick("That went over my circuits. I can call people, tell time, or remember things though.",
                                      "Not sure what that means, but I can call someone or check the time.")
                    : pick("I'm still learning to chat. I can call your contacts, tell the time, or remember things. What would you like?",
                           "I didn't quite get that. I can make calls, tell time, or remember things for you.");
        }

        if ("Silent".equals(personality)) {
            broadcastMessage(reply);
            handler.postDelayed(this::rearmAfterAction, 800);
        } else {
            broadcastMessage(reply);
            speakThenRun(reply, this::rearmAfterAction);
        }
    }

    /** Lenient quick-action detection so many phrasings work. */
    private boolean isQuickAction(String n) {
        if (containsCallVerb(n)) return false; // never swallow a call command
        if (n.matches(".*\\btime\\b.*")) return true;
        if (n.matches(".*\\bbattery\\b.*")) return true;
        if (n.matches("^(?:stop|shut\\s*up|quiet|silence|go\\s+to\\s+sleep|sleep|never\\s*mind)$")) return true;
        if (n.matches(".*\\b(?:what\\s+can\\s+you\\s+do|help\\s+me|^help$)\\b.*")) return true;
        return false;
    }

    private boolean containsCallVerb(String n) {
        return n.matches(".*\\b(?:call|dial|phone|ring)\\b.*");
    }

    private void handleQuickAction(String normalized) {
        if (normalized.matches(".*\\b(time|what time)\\b.*")) {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            String ampm = hour >= 12 ? "PM" : "AM";
            int displayHour = hour % 12 == 0 ? 12 : hour % 12;
            String timeText = "It\u2019s " + displayHour + ":" + String.format(Locale.US, "%02d", minute) + " " + ampm;
            speak(timeText);
            broadcastMessage(timeText);
            LogStore.append(this, "QUICK", "Time: " + timeText);
        } else if (normalized.matches(".*\\b(battery)\\b.*")) {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            String batteryText = level >= 0 ? "Battery is at " + level + " percent" : "I can\u2019t check the battery right now";
            speak(batteryText);
            broadcastMessage(batteryText);
            LogStore.append(this, "QUICK", batteryText);
        } else if (normalized.matches(".*\\b(help|what can you do)\\b.*")) {
            String helpText = "Say \u201CCall\u201D and a name, ask the time or battery, say \u201Copen\u201D and an app, ask me to read your notifications, or say \u201CStop\u201D.";
            speak(helpText);
            broadcastMessage(helpText);
            LogStore.append(this, "QUICK", "Help requested");
        } else {
            // Stop / shut up / quiet
            broadcastMessage("Going back to sleep.");
            LogStore.append(this, "STOP", "Voice command: stop");
            stopIris("Stopped by voice command");
            return;
        }
        rearmAfterAction();
    }

    /**
     * Read recent phone notifications by voice. Supports filtering by app
     * ("whatsapp", "sms/text"). Prompts to grant Notification Access if needed.
     */
    private void handleNotifications(String normalized) {
        if (!notificationAccessGranted()) {
            String msg = "I need notification access first. Opening the settings — enable IRIS there.";
            broadcastMessage(msg);
            speakThenRun(msg, this::rearmAfterAction);
            openNotificationAccessSettings();
            LogStore.append(this, "NOTIF", "Access not granted — opened settings");
            return;
        }

        // Determine an app filter from the request
        String filter = null; String filterLabel = null;
        if (normalized.contains("whatsapp")) { filter = "whatsapp"; filterLabel = "WhatsApp"; }
        else if (normalized.matches(".*\\b(sms|text|texted|texts)\\b.*")) { filter = "sms_family"; filterLabel = "messages"; }

        List<NotificationStore.Item> items;
        if ("sms_family".equals(filter)) {
            items = new ArrayList<>();
            for (NotificationStore.Item it : NotificationStore.recent(50)) {
                String p = it.pkg.toLowerCase();
                if (p.contains("mms") || p.contains("sms") || p.contains("messaging")
                        || it.appLabel.toLowerCase().contains("message")) {
                    items.add(it);
                    if (items.size() >= 5) break;
                }
            }
        } else if (filter != null) {
            items = NotificationStore.recentFor(filter, 5);
        } else {
            items = NotificationStore.recent(5);
        }

        if (items.isEmpty()) {
            String none = filterLabel != null
                    ? "You have no recent " + filterLabel + " notifications."
                    : "You have no recent notifications.";
            broadcastMessage(none);
            speakThenRun(none, this::rearmAfterAction);
            LogStore.append(this, "NOTIF", "None" + (filterLabel != null ? " for " + filterLabel : ""));
            return;
        }

        int count = Math.min(items.size(), 3);
        StringBuilder spoken = new StringBuilder();
        spoken.append("You have ").append(items.size())
              .append(items.size() == 1 ? " recent notification. " : " recent notifications. ");
        for (int i = 0; i < count; i++) {
            NotificationStore.Item it = items.get(i);
            spoken.append("From ").append(it.appLabel);
            if (!it.title.isEmpty()) spoken.append(", ").append(it.title);
            if (!it.text.isEmpty()) spoken.append(": ").append(it.text);
            spoken.append(". ");
        }
        String out = spoken.toString().trim();
        broadcastMessage("\uD83D\uDD14 " + out);
        speakThenRun(out, this::rearmAfterAction);
        LogStore.append(this, "NOTIF", "Read " + count + " of " + items.size()
                + (filterLabel != null ? " (" + filterLabel + ")" : ""));
    }

    private boolean notificationAccessGranted() {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void openNotificationAccessSettings() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            LogStore.append(this, "NOTIF ERROR", "Can't open settings: " + e.getMessage());
        }
    }

    /**
     * Launch an installed app by spoken name. Matches against installed app
     * labels (fuzzy) and launches the best match. Returns true if launched.
     */
    private boolean openApp(String spokenName) {
        String target = ProfileStore.normalize(spokenName);
        if (target.isEmpty()) return false;
        android.content.pm.PackageManager pm = getPackageManager();
        List<android.content.pm.ApplicationInfo> apps =
                pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
        String bestPkg = null; String bestLabel = null; double bestScore = 0;
        for (android.content.pm.ApplicationInfo app : apps) {
            // Only apps that have a launcher entry
            if (pm.getLaunchIntentForPackage(app.packageName) == null) continue;
            String label = ProfileStore.normalize(pm.getApplicationLabel(app).toString());
            if (label.isEmpty()) continue;
            double score;
            if (label.equals(target)) score = 1.0;
            else if (label.contains(target) || target.contains(label)) score = 0.85;
            else score = nameScore(target, label);
            if (score > bestScore) { bestScore = score; bestPkg = app.packageName; bestLabel = pm.getApplicationLabel(app).toString(); }
        }
        if (bestPkg != null && bestScore >= 0.7) {
            Intent launch = pm.getLaunchIntentForPackage(bestPkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(launch);
                    LogStore.append(this, "OPEN APP", bestLabel + " (" + bestPkg + ")");
                    broadcastMessage("Opening " + bestLabel + ".");
                    speakThenRun("Opening " + bestLabel + ".", this::rearmAfterAction);
                    return true;
                } catch (Exception e) {
                    LogStore.append(this, "OPEN APP ERROR", e.getMessage());
                }
            }
        }
        broadcastMessage("I couldn't find an app called " + spokenName + ".");
        speakThenRun("I couldn't find " + spokenName + ".", this::rearmAfterAction);
        return true; // handled (told the user), don't fall through to chat
    }

    private void handleRedial(ProfileStore store) {
        List<ProfileStore.Entry> recent = store.frequentContacts(1);
        if (recent.isEmpty() || recent.get(0).lastCalled == 0) {
            broadcastMessage("I don\u2019t have anyone to call back yet.");
            LogStore.append(this, "REDIAL", "No recent calls");
            rearmAfterAction();
            return;
        }
        ProfileStore.Entry last = recent.get(0);
        LogStore.append(this, "REDIAL", last.contactName);
        requestCallConfirmation(last.contactName, last.phoneNumber);
    }

    private void handleRemember(String statement) {
        MemoryParser.ParsedMemory parsed = MemoryParser.parse(statement);
        if (parsed != null) {
            MemoryStore.Memory memory = new MemoryStore.Memory();
            memory.category = parsed.category;
            memory.key = parsed.key;
            memory.value = parsed.value;
            memory.source = "voice";
            MemoryStore.add(this, memory);
            lastMemoryId = memory.id;
            String response = "Got it. " + parsed.key + " is " + parsed.value + ".";
            speak(response);
            broadcastMessage("\uD83E\uDDE0 " + response);
            LogStore.append(this, "MEMORY", "Voice: " + parsed.key + " = " + parsed.value
                    + " (" + parsed.category + ", confidence " + Math.round(parsed.confidence * 100) + "%)");
        } else {
            String response = "I\u2019ll remember that as a note.";
            MemoryStore.Memory memory = new MemoryStore.Memory();
            memory.category = MemoryStore.CAT_ABOUT_ME;
            memory.key = "note";
            memory.value = statement;
            memory.source = "voice";
            MemoryStore.add(this, memory);
            lastMemoryId = memory.id;
            speak(response);
            broadcastMessage("\uD83E\uDDE0 " + response);
            LogStore.append(this, "MEMORY", "Voice note: " + statement);
        }
        rearmAfterAction();
    }

    private void handleForget() {
        if (lastMemoryId != null) {
            MemoryStore.delete(this, lastMemoryId);
            speak("Done. I forgot it.");
            broadcastMessage("\uD83D\uDDD1 Memory removed.");
            LogStore.append(this, "MEMORY", "Deleted: " + lastMemoryId);
            lastMemoryId = null;
        } else {
            speak("There\u2019s nothing recent to forget.");
            broadcastMessage("Nothing to forget.");
        }
        rearmAfterAction();
    }

    private void handleRecall() {
        int count = MemoryStore.count(this);
        String name = MemoryStore.ownerName(this);
        if (count == 0) {
            speak("I don\u2019t know anything about you yet. Say \u201Cremember\u201D followed by a fact.");
            broadcastMessage("\uD83E\uDDE0 No memories yet.");
        } else {
            StringBuilder response = new StringBuilder();
            if (name != null) response.append("I know your name is ").append(name).append(". ");
            response.append("I have ").append(count).append(" ").append(count == 1 ? "memory" : "memories");
            List<MemoryStore.Memory> people = MemoryStore.getByCategory(this, MemoryStore.CAT_PEOPLE);
            if (!people.isEmpty()) response.append(", including ").append(people.size()).append(" about people");
            response.append(".");
            speak(response.toString());
            broadcastMessage("\uD83E\uDDE0 " + response);
        }
        LogStore.append(this, "RECALL", "Memories: " + count);
        rearmAfterAction();
    }

    private void handleHistory(String normalized, ProfileStore store) {
        if (normalized.matches(".*\\bwho\\s+did\\s+i\\s+call\\s+last\\b.*")) {
            ProfileStore.Entry last = store.lastCalled();
            if (last != null) {
                String text = "Your last call was to " + last.contactName;
                speak(text);
                broadcastMessage(text);
            } else {
                broadcastMessage("I don\u2019t have any call history yet.");
            }
        } else if (normalized.matches(".*\\bwho\\s+did\\s+i\\s+call\\s+today\\b.*") || normalized.matches(".*\\bmy\\s+calls\\b.*") || normalized.matches(".*\\bcall\\s+history\\b.*")) {
            List<ProfileStore.Entry> today = store.calledToday();
            if (today.isEmpty()) {
                broadcastMessage("No calls yet today.");
            } else {
                List<String> names = new ArrayList<>();
                for (ProfileStore.Entry e : today) names.add(e.contactName);
                String text = "Today you called: " + String.join(", ", names);
                speak(text);
                broadcastMessage(text);
            }
        } else if (normalized.matches(".*\\bhow\\s+many\\s+times\\b.*")) {
            String who = normalized.replaceAll(".*how\\s+many\\s+times\\s+did\\s+i\\s+call\\s+", "").trim();
            List<ContactMatch> matches = resolveContacts(who);
            if (!matches.isEmpty()) {
                ContactMatch best = matches.get(0);
                ProfileStore.Entry entry = null;
                for (ProfileStore.Entry e : store.getEntries()) {
                    if (e.contactName.equalsIgnoreCase(best.name)) { entry = e; break; }
                }
                if (entry != null && entry.callCount > 0) {
                    String text = "You\u2019ve called " + entry.contactName + " " + entry.callCount + " time" + (entry.callCount != 1 ? "s" : "") + " total.";
                    speak(text);
                    broadcastMessage(text);
                } else {
                    broadcastMessage("I don\u2019t have call records for " + who + ".");
                }
            } else {
                broadcastMessage("I couldn\u2019t find a contact matching \u201C" + who + "\u201D.");
            }
        } else if (normalized.matches(".*\\bwhen\\s+did\\s+i\\b.*")) {
            String who = normalized.replaceAll(".*when\\s+did\\s+i\\s+(?:last\\s+)?call\\s+", "").trim();
            List<ContactMatch> matches = resolveContacts(who);
            if (!matches.isEmpty()) {
                ContactMatch best = matches.get(0);
                ProfileStore.Entry entry = null;
                for (ProfileStore.Entry e : store.getEntries()) {
                    if (e.contactName.equalsIgnoreCase(best.name)) { entry = e; break; }
                }
                if (entry != null && entry.lastCalled > 0) {
                    String timeAgo = relativeTime(entry.lastCalled);
                    String text = "You last called " + entry.contactName + " " + timeAgo + ".";
                    speak(text);
                    broadcastMessage(text);
                } else {
                    broadcastMessage("I don\u2019t have a record of calling " + who + ".");
                }
            } else {
                broadcastMessage("I couldn\u2019t find a contact matching \u201C" + who + "\u201D.");
            }
        } else {
            broadcastMessage("I didn\u2019t understand the history question.");
        }
        LogStore.append(this, "HISTORY", normalized);
        rearmAfterAction();
    }

    private String relativeTime(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long minutes = diff / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " minute" + (minutes != 1 ? "s" : "") + " ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hour" + (hours != 1 ? "s" : "") + " ago";
        long days = hours / 24;
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    private void handleConfirmation(String heard) {
        String answer = ProfileStore.normalize(heard);
        LogStore.append(this, "CONFIRM VOICE", answer);
        if (answer.matches(".*\\b(yes|call|confirm|do it|haan|ha)\\b.*")) {
            if (confirmTimeout != null) { handler.removeCallbacks(confirmTimeout); confirmTimeout = null; }
            pendingCandidates = null;
            placeCall(pendingName, pendingNumber);
        } else if (answer.matches(".*\\b(no|cancel|stop|nope|nahi|shut up|quiet|ruk|bas|never mind)\\b.*")) {
            if (confirmTimeout != null) { handler.removeCallbacks(confirmTimeout); confirmTimeout = null; }
            cancelCallNotification();
            // If there are more candidates, ask about the next one
            if (pendingCandidates != null && pendingCandidateIndex + 1 < pendingCandidates.size()) {
                int next = pendingCandidateIndex + 1;
                LogStore.append(this, "CONFIRM", "Declined, trying next: "
                        + pendingCandidates.get(next).name);
                handler.postDelayed(() -> confirmCandidate(next), 300);
            } else {
                pendingCandidates = null;
                broadcastMessage("Call cancelled.");
                speakThenRun("Okay, cancelled.", this::rearmAfterAction);
            }
        } else if (confirmationRetries++ < 1) {
            broadcastMessage("Say “Call” or “Cancel”, or use the buttons.");
            handler.postDelayed(this::startConfirmationRecognition, 450);
        } else {
            cancelCallNotification();
            broadcastMessage("I couldn't understand. Call cancelled.");
            LogStore.append(IrisListeningService.this, "CANCELLED", "Unrecognized confirmation for " + (pendingName == null ? "contact" : pendingName));
            rearmAfterAction();
        }
    }

    private List<ContactMatch> resolveContacts(String requested) {
        String target = ProfileStore.normalize(requested);
        if (target.matches("[0-9 ]{3,}")) {
            return Collections.singletonList(new ContactMatch(requested, requested.replace(" ", ""), 1.0));
        }
        List<ContactMatch> matches = new ArrayList<>();
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return matches;
        String[] projection = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cursor = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null)) {
            if (cursor == null) return matches;
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String number = cursor.getString(1);
                double score = nameScore(target, ProfileStore.normalize(name));
                if (score >= .65) matches.add(new ContactMatch(name, number, score));
            }
        } catch (Exception error) {
            LogStore.append(this, "ERROR", "Contact lookup failed: " + error.getMessage());
        }
        matches.sort((a, b) -> Double.compare(b.score, a.score));
        return matches;
    }

    private double nameScore(String target, String candidate) {
        if (target.equals(candidate)) return 1;
        if (candidate.startsWith(target) || target.startsWith(candidate)) return .90;
        if (candidate.contains(target) || target.contains(candidate)) return .82;
        for (String a : target.split(" ")) for (String b : candidate.split(" "))
            if (a.length() > 2 && a.equals(b)) return .76;
        if (soundex(target).equals(soundex(candidate)) && !soundex(target).isEmpty()) return .74;
        return textSimilarity(target, candidate);
    }

    private String soundex(String value) {
        String latin = value.replaceAll("[^a-z]", "");
        if (latin.isEmpty()) return "";
        String map = "01230120022455012623010202";
        StringBuilder result = new StringBuilder().append(latin.charAt(0));
        char previous = map.charAt(latin.charAt(0) - 'a');
        for (int i = 1; i < latin.length() && result.length() < 4; i++) {
            char code = map.charAt(latin.charAt(i) - 'a');
            if (code != '0' && code != previous) result.append(code);
            previous = code;
        }
        while (result.length() < 4) result.append('0');
        return result.toString();
    }

    private double textSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return 1.0 - ((double) previous[b.length()] / Math.max(a.length(), b.length()));
    }

    private void requestDisambiguation(List<ContactMatch> candidates) {
        destroyRecognizer();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> numbers = new ArrayList<>();
        for (ContactMatch candidate : candidates) {
            names.add(candidate.name);
            numbers.add(candidate.number);
        }
        sendBroadcast(new Intent(EVENT_DISAMBIGUATE).setPackage(getPackageName())
                .putStringArrayListExtra(EXTRA_NAMES, names)
                .putStringArrayListExtra(EXTRA_NUMBERS, numbers));
        Intent open = new Intent(this, MainActivity.class)
                .putStringArrayListExtra(EXTRA_NAMES, names)
                .putStringArrayListExtra(EXTRA_NUMBERS, numbers);
        PendingIntent content = PendingIntent.getActivity(this, 14, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("Which contact did you mean?")
                .setContentText(String.join(" • ", names))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(CALL_NOTIFICATION, notification);
        handler.postDelayed(() -> {
            if (isRunning && pendingName == null && PHASE_COMMAND.equals(phase)) {
                broadcastMessage("No selection made. Going back to sleep.");
                LogStore.append(this, "TIMEOUT", "Disambiguation expired");
                cancelCallNotification();
                rearmAfterAction();
            }
        }, 30_000);
    }

    private void requestCorrection(String heard) {
        sendBroadcast(new Intent(EVENT_TEACH).setPackage(getPackageName())
                .putExtra(EXTRA_TEXT, heard));
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_TEACH, true).putExtra(EXTRA_TEXT, heard);
        Notification notification = new Notification.Builder(this, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("Teach IRIS what you meant?")
                .setContentText("“" + heard + "”")
                .setContentIntent(PendingIntent.getActivity(this, 34, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(TEACH_NOTIFICATION, notification);
    }

    private void requestCallConfirmation(String name, String number) {
        requestCallConfirmation(name, number, null);
    }

    private void requestCallConfirmation(String name, String number, String customPrompt) {
        if (name == null || number == null) {
            rearmAfterAction();
            return;
        }
        // Smart call timing checks
        String warning = callTimingWarning(name, number);
        if (warning != null) {
            broadcastMessage(warning);
            speak(warning);
        }
        pendingName = name;
        pendingNumber = number;
        confirmationRetries = 0;
        destroyRecognizer();
        LogStore.append(this, "CONFIRM", "Waiting to call " + name);
        broadcastCallPrompt(name, number);
        showCallNotification(name, number);
        String confirmText = customPrompt != null ? customPrompt : personalityLine("confirm", name);
        if (settings.voiceReplies() && ttsReady && confirmText != null) {
            textToSpeech.speak(confirmText, TextToSpeech.QUEUE_FLUSH, null, "iris_confirm");
            textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) {
                    if ("iris_confirm".equals(utteranceId)) {
                        textToSpeech.setOnUtteranceProgressListener(null);
                        handler.post(() -> startConfirmationRecognition());
                    }
                }
                @Override public void onError(String utteranceId) {
                    textToSpeech.setOnUtteranceProgressListener(null);
                    handler.post(() -> startConfirmationRecognition());
                }
            });
        } else {
            startConfirmationRecognition();
        }
    }

    private void placeCall(String name, String number) {
        if (number == null || number.trim().isEmpty()) {
            broadcastMessage("That contact has no callable number.");
            rearmAfterAction();
            return;
        }
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (settings.requireUnlock() && keyguard != null && keyguard.isDeviceLocked()) {
            cancelCallNotification();
            requestUnlockForCall(name, number);
            return;
        }
        pendingName = name;
        pendingNumber = number;
        speak(personalityLine("calling", name));
        broadcastMessage("Calling " + name + "…");
        handler.postDelayed(() -> performCall(name, number), settings.voiceReplies() ? 500 : 80);
    }

    private void performCall(String name, String number) {
        String digits = number.replaceAll("[^0-9+]", "");
        boolean sensitive = digits.replace("+", "").length() <= 3 || digits.startsWith("1900");
        boolean direct = hasPermission(Manifest.permission.CALL_PHONE) && !sensitive;
        Intent call = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL,
                Uri.parse("tel:" + Uri.encode(digits))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            new ProfileStore(this).recordCall(name, number);
            BehaviorAnalyzer.onCallPlaced(this, name, number);
            LogStore.append(this, direct ? "CALLING" : "DIALER", name == null ? number : name);
            isRunning = false;
            broadcastState(false, "off");
            destroyRecognizer();
            stopWakeEngine();
            stopForeground(STOP_FOREGROUND_REMOVE);
            startActivity(call);
            stopSelf();
        } catch (Exception error) {
            LogStore.append(this, "ERROR", "Call failed: " + error.getMessage());
            broadcastMessage("This device could not place the call.");
            stopIris("Call launch failed");
        }
    }

    private void requestUnlockForCall(String name, String number) {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_AUTH_REQUIRED, true);
        PendingIntent content = PendingIntent.getActivity(this, 24, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("Unlock to call " + name)
                .setContentText("IRIS protects calls while your phone is locked.")
                .setContentIntent(content).setAutoCancel(true).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(CALL_NOTIFICATION, notification);
        LogStore.append(this, "LOCKED", "Authentication required for " + name);
    }

    private void rearmAfterAction() {
        if (commandTimeout != null) { handler.removeCallbacks(commandTimeout); commandTimeout = null; }
        if (confirmTimeout != null) { handler.removeCallbacks(confirmTimeout); confirmTimeout = null; }
        pendingName = null;
        pendingNumber = null;
        destroyRecognizer();
        cancelCallNotification();
        if (!isRunning) return;
        if (AppSettings.MODE_TAP.equals(settings.listeningMode())) stopIris("Tap session completed");
        else if (AppSettings.MODE_WAKE.equals(settings.listeningMode())) handler.postDelayed(this::startWakeDetection, 500);
        else handler.postDelayed(this::startCommandRecognition, 650);
    }

    private String configureAudioRoute() {
        AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return "Phone microphone";
        if (audioManager == null) previousAudioMode = manager.getMode();
        audioManager = manager;
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            String preference = settings.preferredMicrophone();
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo chosen = chooseDevice(audioManager.getAvailableCommunicationDevices(), preference);
                if (chosen != null && audioManager.setCommunicationDevice(chosen)) return readableDeviceName(chosen);
            } else {
                AudioDeviceInfo chosen = chooseDevice(arrayToList(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)), preference);
                if (chosen != null) {
                    if (chosen.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        audioManager.startBluetoothSco();
                        audioManager.setBluetoothScoOn(true);
                    }
                    return readableDeviceName(chosen);
                }
            }
        } catch (Exception ignored) { return "Active system microphone"; }
        return "Phone microphone";
    }

    private AudioDeviceInfo chooseDevice(List<AudioDeviceInfo> devices, String preference) {
        AudioDeviceInfo builtIn = null;
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) builtIn = device;
            boolean bluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
            boolean wired = type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET;
            if ("Bluetooth".equals(preference) && bluetooth) return device;
            if ("Wired / USB".equals(preference) && wired) return device;
            if ("Phone".equals(preference) && builtIn != null) return builtIn;
            if ("Automatic".equals(preference) && (bluetooth || wired)) return device;
        }
        return builtIn;
    }

    private List<AudioDeviceInfo> arrayToList(AudioDeviceInfo[] devices) {
        List<AudioDeviceInfo> result = new ArrayList<>();
        Collections.addAll(result, devices);
        return result;
    }

    private String readableDeviceName(AudioDeviceInfo device) {
        String product = device.getProductName() == null ? "" : device.getProductName().toString();
        if (!product.trim().isEmpty()) return product + " microphone";
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth headset microphone";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset microphone";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset microphone";
            default: return "Phone microphone";
        }
    }

    private void registerAudioChanges() {
        if (audioManager == null || audioDeviceCallback != null) return;
        audioDeviceCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) { refreshAudioRoute(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) { refreshAudioRoute(); }
        };
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
    }

    private void refreshAudioRoute() {
        if (!isRunning) return;
        handler.postDelayed(() -> {
            microphoneLabel = configureAudioRoute();
            broadcastState(true, phase);
            updateListeningNotification("Active through " + microphoneLabel);
        }, 300);
    }

    private void releaseAudioRoute() {
        if (audioManager == null) return;
        try {
            if (audioDeviceCallback != null) audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice();
            else { audioManager.setBluetoothScoOn(false); audioManager.stopBluetoothSco(); }
            audioManager.setMode(previousAudioMode);
        } catch (Exception ignored) { }
        audioDeviceCallback = null;
        audioManager = null;
    }

    private Notification listeningNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, IrisListeningService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, LISTENING_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris).setContentTitle("IRIS is active")
                .setContentText(microphoneLabel).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "Turn off", stopPending).build())
                .build();
    }

    private void updateListeningNotification(String text) {
        Notification notification = new Notification.Builder(this, LISTENING_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris).setContentTitle("IRIS is active")
                .setContentText(text + " • " + microphoneLabel).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(LISTENING_NOTIFICATION, notification);
    }

    private void showCallNotification(String name, String number) {
        Intent confirm = new Intent(this, IrisListeningService.class).setAction(ACTION_CONFIRM_CALL)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number);
        Intent cancel = new Intent(this, IrisListeningService.class).setAction(ACTION_CANCEL_CALL)
                .putExtra(EXTRA_NAME, name);
        PendingIntent yes = PendingIntent.getService(this, 2, confirm,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent no = PendingIntent.getService(this, 3, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris).setContentTitle("Call " + name + "?")
                .setContentText(number + " • Say Call or Cancel")
                .setContentIntent(PendingIntent.getActivity(this, 4,
                        new Intent(this, MainActivity.class).putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "Cancel", no).build())
                .addAction(new Notification.Action.Builder(null, "Call", yes).build())
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(CALL_NOTIFICATION, notification);
    }

    private void cancelCallNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(CALL_NOTIFICATION);
    }

    private void showVoiceChallengeNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra("voice_challenge", true);
        PendingIntent content = PendingIntent.getActivity(this, 44, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("\uD83D\uDD12 Voice verification required")
                .setContentText("Someone triggered your wake phrase. Tap to verify.")
                .setContentIntent(content)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(CALL_NOTIFICATION, notification);
    }

    private void createNotificationChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(LISTENING_CHANNEL,
                "IRIS listening", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(CALL_CHANNEL,
                "IRIS call decisions", NotificationManager.IMPORTANCE_HIGH));
    }

    private void stopIris(String reason) {
        isRunning = false;
        currentPhase = "off";
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        stopWakeEngine();
        cancelCallNotification();
        LogStore.append(this, "STOP", reason);
        broadcastState(false, "off");
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopWakeEngine() {
        if (wakeEngine != null) { wakeEngine.stop(); wakeEngine = null; }
        if (voskEngine != null) voskEngine.stop();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            recognizer.destroy();
            recognizer = null;
        }
    }

    private void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!settings.voiceReplies()) return;
        speakAndroidTts(text);
    }

    private void speakAndroidTts(String text) {
        if (textToSpeech == null) {
            textToSpeech = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    textToSpeech.setLanguage(Locale.getDefault());
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iris_reply");
                } else {
                    LogStore.append(this, "TTS", "Android TTS init failed with status " + status);
                }
            });
            return;
        }
        if (!ttsReady) {
            // TTS exists but not ready — reinitialize
            try { textToSpeech.shutdown(); } catch (Exception ignored) { }
            textToSpeech = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    textToSpeech.setLanguage(Locale.getDefault());
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iris_reply");
                }
            });
            return;
        }
        try {
            int result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iris_reply");
            if (result != TextToSpeech.SUCCESS) {
                LogStore.append(this, "TTS", "speak() returned error " + result);
            }
        } catch (Exception e) {
            LogStore.append(this, "TTS ERROR", e.getMessage());
        }
    }

    /**
     * Speak text and run the callback ONLY after speech finishes.
     * Prevents the mic from cutting off IRIS mid-sentence.
     * If voice replies are off or TTS fails, runs the callback after a short delay.
     */
    private void speakThenRun(String text, Runnable afterSpeaking) {
        if (text == null || text.isEmpty() || !settings.voiceReplies() || textToSpeech == null || !ttsReady) {
            // No speech — just run after a brief pause
            handler.postDelayed(afterSpeaking, 600);
            return;
        }
        final boolean[] ran = {false};
        textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) {
                if ("iris_greet".equals(utteranceId) && !ran[0]) {
                    ran[0] = true;
                    handler.post(() -> { textToSpeech.setOnUtteranceProgressListener(null); afterSpeaking.run(); });
                }
            }
            @Override public void onError(String utteranceId) {
                if (!ran[0]) {
                    ran[0] = true;
                    handler.post(() -> { textToSpeech.setOnUtteranceProgressListener(null); afterSpeaking.run(); });
                }
            }
        });
        try {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iris_greet");
        } catch (Exception e) {
            handler.postDelayed(afterSpeaking, 600);
        }
        // Safety net: if onDone never fires (some TTS engines), run after 6s max
        handler.postDelayed(() -> {
            if (!ran[0]) { ran[0] = true; textToSpeech.setOnUtteranceProgressListener(null); afterSpeaking.run(); }
        }, 6000);
    }

    private String callTimingWarning(String name, String number) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        // Late night warning (11 PM - 6 AM)
        if (hour >= 23 || hour < 6) {
            return "It\u2019s late. Are you sure about calling " + name + "?";
        }
        // DND check
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
                return "Do Not Disturb is on. Still want to call " + name + "?";
            }
        } catch (Exception ignored) { }
        // Memory preference: no calls after X
        String noCallsAfter = MemoryStore.findPreference(this, "no calls after");
        if (noCallsAfter != null) {
            try {
                int cutoff = Integer.parseInt(noCallsAfter.replaceAll("[^0-9]", ""));
                if (cutoff > 0 && cutoff <= 24 && hour >= cutoff) {
                    return "Your rule says no calls after " + cutoff + ". Override for " + name + "?";
                }
            } catch (Exception ignored) { }
        }

        // Repeated calls check
        ProfileStore store = new ProfileStore(this);
        for (ProfileStore.Entry entry : store.getEntries()) {
            if (entry.phoneNumber != null && entry.phoneNumber.replaceAll("[^0-9+]", "").equals(
                    number.replaceAll("[^0-9+]", "")) && entry.callCount >= 3) {
                long hoursSinceLast = (System.currentTimeMillis() - entry.lastCalled) / 3_600_000;
                if (hoursSinceLast < 2) {
                    return "You\u2019ve called " + name + " " + entry.callCount + " times recently. Try again?";
                }
            }
        }
        return null;
    }

    private String timeGreeting() {
        String name = MemoryStore.ownerName(this);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour < 6) greeting = "Up late?";
        else if (hour < 12) greeting = "Good morning";
        else if (hour < 17) greeting = "Good afternoon";
        else if (hour < 21) greeting = "Good evening";
        else greeting = "Still going?";
        return name != null ? greeting + ", " + name + "." : greeting + ". I\u2019m here.";
    }

    private String personalityLine(String event, String name) {
        String personality = settings.personality();
        if ("Silent".equals(personality)) return null;
        if ("Professional".equals(personality)) {
            if ("confirm".equals(event)) return "Call " + name + "?";
            if ("calling".equals(event)) return "Calling " + name;
            return "Call cancelled";
        }
        if ("Warm".equals(personality)) {
            if ("confirm".equals(event)) return "Would you like me to call " + name + "?";
            if ("calling".equals(event)) return "Of course. Calling " + name;
            return "No problem. I cancelled it.";
        }
        if ("confirm".equals(event)) return "Call " + name + "? Your thumbs seem grateful.";
        if ("calling".equals(event)) return "Calling " + name + ". Because typing was apparently too much work.";
        return "Cancelled. Consider it a rehearsal.";
    }

    private void vibrate(long milliseconds) {
        if (!settings.haptics()) return;
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            android.os.VibratorManager vm = (android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator())
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void broadcastState(boolean active, String statePhase) {
        sendBroadcast(new Intent(EVENT_STATE).setPackage(getPackageName())
                .putExtra(EXTRA_ACTIVE, active).putExtra(EXTRA_PHASE, statePhase)
                .putExtra(EXTRA_MIC, microphoneLabel).putExtra(EXTRA_RECOGNITION, recognitionLabel));
    }

    private void broadcastTranscript(String text) {
        sendBroadcast(new Intent(EVENT_TRANSCRIPT).setPackage(getPackageName()).putExtra(EXTRA_TEXT, text));
    }

    private void broadcastCallPrompt(String name, String number) {
        sendBroadcast(new Intent(EVENT_CALL_PROMPT).setPackage(getPackageName())
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number));
    }

    private void broadcastMessage(String text) {
        if (text != null) sendBroadcast(new Intent(EVENT_MESSAGE).setPackage(getPackageName()).putExtra(EXTRA_TEXT, text));
    }

    @Override public void onReadyForSpeech(Bundle params) { broadcastMessage(phase.equals(PHASE_CONFIRM) ? "Say Call or Cancel…" : "Listening…"); }
    @Override public void onBeginningOfSpeech() { broadcastMessage("I hear you…"); }
    @Override public void onRmsChanged(float rmsdB) {
        long now = System.currentTimeMillis();
        if (now - lastLevelBroadcast < 90) return;
        lastLevelBroadcast = now;
        float normalized = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 14f));
        sendBroadcast(new Intent(EVENT_LEVEL).setPackage(getPackageName()).putExtra(EXTRA_LEVEL, normalized));
    }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { broadcastMessage("Thinking…"); }

    @Override
    public void onError(int error) {
        if (!isRunning) return;
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                && error != SpeechRecognizer.ERROR_CLIENT) LogStore.append(this, "LISTEN ERROR", speechError(error));
        if (PHASE_CONFIRM.equals(phase)) {
            if (confirmationRetries++ < 1) handler.postDelayed(this::startConfirmationRecognition, 450);
            return;
        }
        rearmAfterAction();
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String heard = matches == null || matches.isEmpty() ? "" : matches.get(0);
        if (PHASE_CONFIRM.equals(phase)) handleConfirmation(heard);
        else handleCommand(heard);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) broadcastTranscript(matches.get(0));
    }

    @Override public void onEvent(int eventType, Bundle params) { }

    private String speechError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Microphone audio error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission missing";
            case SpeechRecognizer.ERROR_NETWORK: return "Speech service network error";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Speech service is busy";
            default: return "Speech recognition paused (" + error + ")";
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        stopWakeEngine();
        releaseAudioRoute();
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }
        if (textToSpeech != null) textToSpeech.shutdown();
        if (speakerVerifier != null) { speakerVerifier.close(); speakerVerifier = null; }
        if (voskEngine != null) { voskEngine.close(); voskEngine = null; }
        if (llmAgent != null) { llmAgent.close(); llmAgent = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static class ContactMatch {
        final String name;
        final String number;
        final double score;
        ContactMatch(String name, String number, double score) {
            this.name = name; this.number = number; this.score = score;
        }
    }
}
