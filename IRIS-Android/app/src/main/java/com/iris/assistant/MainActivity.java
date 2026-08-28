package com.iris.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PERMISSION_START = 100;
    private static final int PERMISSION_TRAIN = 101;
    private static final int PICK_CONTACT = 200;
    private static final int EXPORT_PROFILE = 201;
    private static final int IMPORT_PROFILE = 202;
    private static final int EXPORT_LOGS = 203;
    private static final int AUTH_CREDENTIAL = 204;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout contentHost;
    private Button tabAssistant;
    private Button tabTraining;
    private Button tabLogs;
    private Button tabSettings;
    private int selectedTab;

    private IrisOrbView irisOrb;
    private TextView statusText;
    private TextView subStatusText;
    private TextView liveTranscript;
    private TextView micRouteText;
    private TextView recognitionText;
    private TextView phaseChip;
    private TextView frequentContactsText;
    private String lastMicRoute = "Phone microphone";
    private String lastRecognition = "Checking speech support…";

    private EditText wakePhraseInput;
    private TextView wakeTrainingStatus;
    private Button trainWakeButton;
    private Button testWakeButton;
    private View wakeNormalState;
    private View wakeWizardState;
    private TextView wakeWizardStep;
    private TextView wakeWizardDots;
    private TextView wakeWizardPrompt;
    private TextView wakeWizardFeedback;
    private Button wakeWizardCancel;
    private View contactNormalState;
    private View contactWizardState;
    private TextView contactWizardStep;
    private TextView contactWizardDots;
    private TextView contactWizardFeedback;
    private Button contactWizardCancel;
    private WakeWordEngine wakeTrainingEngine;
    private final List<float[][]> wakeTemplates = new ArrayList<>();
    private final List<short[]> wakeRawSamples = new ArrayList<>();
    private int wakeSampleIndex;
    private String wakePhraseBeingTrained;
    private boolean resumeAfterWakeTraining;

    private TextView trainingStep;
    private TextView trainingContact;
    private TextView trainingPrompt;
    private TextView profileSummary;
    private LinearLayout profileListHost;
    private Button startTrainingButton;
    private SpeechRecognizer trainingRecognizer;
    private SpeechRecognizer dryRunRecognizer;
    private final List<String> trainingSamples = new ArrayList<>();
    private final List<String> trainingQualities = new ArrayList<>();
    private int trainingSampleIndex;
    private int trainingErrorCount;
    private float trainingPeakRms;
    private String selectedContactName;
    private String selectedContactNumber;
    private String correctionPhrase;
    private boolean resumeAfterContactTraining;
    private String pendingTrainingKind = "";
    private boolean confirmationShowing;
    private Runnable pendingSecureAction;

    private final BroadcastReceiver irisEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (IrisListeningService.EVENT_STATE.equals(action)) {
                boolean active = intent.getBooleanExtra(IrisListeningService.EXTRA_ACTIVE, false);
                String mic = intent.getStringExtra(IrisListeningService.EXTRA_MIC);
                String recognition = intent.getStringExtra(IrisListeningService.EXTRA_RECOGNITION);
                String phase = intent.getStringExtra(IrisListeningService.EXTRA_PHASE);
                if (mic != null && !mic.isEmpty()) lastMicRoute = mic;
                if (recognition != null && !recognition.isEmpty()) lastRecognition = recognition;
                updateAssistantState(active, phase);
            } else if (IrisListeningService.EVENT_TRANSCRIPT.equals(action)) {
                String text = intent.getStringExtra(IrisListeningService.EXTRA_TEXT);
                if (liveTranscript != null && text != null) liveTranscript.setText("“" + text + "”");
            } else if (IrisListeningService.EVENT_MESSAGE.equals(action)) {
                showAssistantMessage(intent.getStringExtra(IrisListeningService.EXTRA_TEXT));
            } else if (IrisListeningService.EVENT_CALL_PROMPT.equals(action)) {
                showCallConfirmation(intent.getStringExtra(IrisListeningService.EXTRA_NAME),
                        intent.getStringExtra(IrisListeningService.EXTRA_NUMBER), false);
            } else if (IrisListeningService.EVENT_DISAMBIGUATE.equals(action)) {
                showDisambiguation(intent.getStringArrayListExtra(IrisListeningService.EXTRA_NAMES),
                        intent.getStringArrayListExtra(IrisListeningService.EXTRA_NUMBERS));
            } else if (IrisListeningService.EVENT_TEACH.equals(action)) {
                offerCorrection(intent.getStringExtra(IrisListeningService.EXTRA_TEXT));
            } else if (IrisListeningService.EVENT_LEVEL.equals(action)) {
                if (irisOrb != null) irisOrb.setVoiceLevel(intent.getFloatExtra(IrisListeningService.EXTRA_LEVEL, 0));
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        float scale = new AppSettings(newBase).textScale();
        if (Math.abs(scale - 1f) < .01f) {
            super.attachBaseContext(newBase);
            return;
        }
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.fontScale = scale;
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        contentHost = findViewById(R.id.contentHost);
        tabAssistant = findViewById(R.id.tabAssistant);
        tabTraining = findViewById(R.id.tabTraining);
        tabLogs = findViewById(R.id.tabLogs);
        tabSettings = findViewById(R.id.tabSettings);
        tabAssistant.setOnClickListener(v -> showAssistant());
        tabTraining.setOnClickListener(v -> showTraining());
        tabLogs.setOnClickListener(v -> showLogs());
        tabSettings.setOnClickListener(v -> showSettings());
        showAssistant();
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(IrisListeningService.EVENT_STATE);
        filter.addAction(IrisListeningService.EVENT_TRANSCRIPT);
        filter.addAction(IrisListeningService.EVENT_CALL_PROMPT);
        filter.addAction(IrisListeningService.EVENT_DISAMBIGUATE);
        filter.addAction(IrisListeningService.EVENT_TEACH);
        filter.addAction(IrisListeningService.EVENT_LEVEL);
        filter.addAction(IrisListeningService.EVENT_MESSAGE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(irisEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(irisEvents, filter);
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(irisEvents); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void showAssistant() {
        selectedTab = 0;
        contentHost.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.view_assistant, contentHost, false);
        contentHost.addView(view);
        irisOrb = view.findViewById(R.id.irisOrb);
        statusText = view.findViewById(R.id.statusText);
        subStatusText = view.findViewById(R.id.subStatusText);
        liveTranscript = view.findViewById(R.id.liveTranscript);
        micRouteText = view.findViewById(R.id.micRouteText);
        recognitionText = view.findViewById(R.id.recognitionText);
        phaseChip = view.findViewById(R.id.phaseChip);
        frequentContactsText = view.findViewById(R.id.frequentContactsText);
        irisOrb.setOnClickListener(v -> toggleIris());
        updateFrequentContacts();
        updateTabs();
        updateAssistantState(IrisListeningService.isRunning, IrisListeningService.currentPhase);
    }

    private void showTraining() {
        selectedTab = 1;
        contentHost.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.view_training, contentHost, false);
        contentHost.addView(view);

        // Wake phrase section
        wakePhraseInput = view.findViewById(R.id.wakePhraseInput);
        wakeTrainingStatus = view.findViewById(R.id.wakeTrainingStatus);
        trainWakeButton = view.findViewById(R.id.trainWakeButton);
        testWakeButton = view.findViewById(R.id.testWakeButton);
        wakeNormalState = view.findViewById(R.id.wakeNormalState);
        wakeWizardState = view.findViewById(R.id.wakeWizardState);
        wakeWizardStep = view.findViewById(R.id.wakeWizardStep);
        wakeWizardDots = view.findViewById(R.id.wakeWizardDots);
        wakeWizardPrompt = view.findViewById(R.id.wakeWizardPrompt);
        wakeWizardFeedback = view.findViewById(R.id.wakeWizardFeedback);
        wakeWizardCancel = view.findViewById(R.id.wakeWizardCancel);

        // Contact section
        profileSummary = view.findViewById(R.id.profileSummary);
        startTrainingButton = view.findViewById(R.id.startTrainingButton);
        contactNormalState = view.findViewById(R.id.contactNormalState);
        contactWizardState = view.findViewById(R.id.contactWizardState);
        contactWizardStep = view.findViewById(R.id.contactWizardStep);
        contactWizardDots = view.findViewById(R.id.contactWizardDots);
        trainingContact = view.findViewById(R.id.trainingContact);
        trainingPrompt = view.findViewById(R.id.trainingPrompt);
        contactWizardFeedback = view.findViewById(R.id.contactWizardFeedback);
        contactWizardCancel = view.findViewById(R.id.contactWizardCancel);

        // Profile list
        profileListHost = view.findViewById(R.id.profileListHost);

        // Populate wake phrase state
        ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
        if (!wake.phrase.isEmpty()) wakePhraseInput.setText(wake.phrase);
        if (wake.isReady()) {
            String voiceStatus = wake.isVoiceEnrolled() ? "voice enrolled \u2705" : "voice not enrolled \u26A0\uFE0F";
            wakeTrainingStatus.setText("\u2705  \u201C" + wake.phrase + "\u201D \u2022 " + voiceStatus);
            trainWakeButton.setText("\uD83D\uDD04  Retrain");
            testWakeButton.setEnabled(true);
        } else {
            wakeTrainingStatus.setText("\u26A0\uFE0F  Not configured yet");
            trainWakeButton.setText("\uD83C\uDFA4  Set Up Wake Phrase");
            testWakeButton.setEnabled(false);
        }

        // Wire up buttons
        trainWakeButton.setOnClickListener(v -> beginWakeTraining());
        testWakeButton.setOnClickListener(v -> testWakePhrase());
        wakeWizardCancel.setOnClickListener(v -> cancelWakeTraining());
        startTrainingButton.setOnClickListener(v -> requestContactForTraining());
        contactWizardCancel.setOnClickListener(v -> cancelContactTraining());
        view.findViewById(R.id.testWakeButton2).setOnClickListener(v -> testWakePhrase());
        view.findViewById(R.id.testCommandsButton).setOnClickListener(v -> testTrainedCommand());
        view.findViewById(R.id.exportProfileButton).setOnClickListener(v ->
                authenticateThen("Export IRIS profile", this::createProfileDocument));
        view.findViewById(R.id.importProfileButton).setOnClickListener(v ->
                authenticateThen("Import IRIS profile", this::openProfileDocument));

        updateProfileSummary();
        renderProfileManager();
        updateTabs();
    }

    private void showLogs() {
        selectedTab = 2;
        contentHost.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.view_logs, contentHost, false);
        contentHost.addView(view);
        TextView logText = view.findViewById(R.id.logText);
        String logs = LogStore.readNewestFirst(this);
        logText.setText(logs.isEmpty() ? "No activity yet. IRIS is impressively innocent." : logs);
        view.findViewById(R.id.exportLogsButton).setOnClickListener(v ->
                authenticateThen("Export private activity", this::createLogDocument));
        view.findViewById(R.id.clearLogsButton).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear IRIS activity?")
                .setMessage("This removes the encrypted timeline. Training stays untouched.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    LogStore.clear(this);
                    logText.setText("No activity yet. IRIS is impressively innocent.");
                }).show());
        updateTabs();
    }

    private void showSettings() {
        selectedTab = 3;
        contentHost.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.view_settings, contentHost, false);
        contentHost.addView(view);
        AppSettings settings = new AppSettings(this);
        RadioGroup modes = view.findViewById(R.id.listeningModeGroup);
        RadioButton wake = view.findViewById(R.id.modeWake);
        RadioButton tap = view.findViewById(R.id.modeTap);
        RadioButton continuous = view.findViewById(R.id.modeContinuous);
        if (AppSettings.MODE_TAP.equals(settings.listeningMode())) tap.setChecked(true);
        else if (AppSettings.MODE_CONTINUOUS.equals(settings.listeningMode())) continuous.setChecked(true);
        else wake.setChecked(true);
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = checkedId == R.id.modeTap ? AppSettings.MODE_TAP
                    : checkedId == R.id.modeContinuous ? AppSettings.MODE_CONTINUOUS : AppSettings.MODE_WAKE;
            if (AppSettings.MODE_CONTINUOUS.equals(selected)) {
                new AlertDialog.Builder(this).setTitle("Continuous mode is experimental")
                        .setMessage("It can use substantially more battery and the system recognizer may use network data. Custom wake phrase mode is the private default.")
                        .setNegativeButton("Use wake mode", (d, w) -> wake.setChecked(true))
                        .setPositiveButton("Use continuous", (d, w) -> saveListeningMode(selected)).show();
            } else saveListeningMode(selected);
        });

        Spinner mic = view.findViewById(R.id.microphoneSpinner);
        String[] microphones = {"Automatic", "Bluetooth", "Wired / USB", "Phone"};
        setSpinner(mic, microphones, settings.preferredMicrophone());
        mic.setOnItemSelectedListener(new SimpleItemSelected(position -> {
            settings.setPreferredMicrophone(microphones[position]);
            restartIfRunning();
        }));

        Switch onDevice = view.findViewById(R.id.onDeviceSwitch);
        onDevice.setChecked(settings.preferOnDevice());
        onDevice.setOnCheckedChangeListener((button, checked) -> settings.setPreferOnDevice(checked));
        Spinner language = view.findViewById(R.id.languageSpinner);
        String[] languageLabels = {"System language", "English (India)", "Hindi (India)", "Hinglish"};
        String selectedLanguage = "en-IN".equals(settings.languageTag()) ? "English (India)"
                : "hi-IN".equals(settings.languageTag()) ? "Hindi (India)"
                : "hinglish".equals(settings.languageTag()) ? "Hinglish" : "System language";
        setSpinner(language, languageLabels, selectedLanguage);
        language.setOnItemSelectedListener(new SimpleItemSelected(position -> settings.setLanguageTag(
                position == 1 ? "en-IN" : position == 2 ? "hi-IN" : position == 3 ? "hinglish" : "system")));
        view.findViewById(R.id.downloadModelButton).setOnClickListener(v -> downloadOfflineModel());

        Spinner personality = view.findViewById(R.id.personalitySpinner);
        String[] personalities = {"Sarcastic", "Warm", "Professional", "Silent"};
        setSpinner(personality, personalities, settings.personality());
        personality.setOnItemSelectedListener(new SimpleItemSelected(position -> settings.setPersonality(personalities[position])));
        Spinner textSize = view.findViewById(R.id.textSizeSpinner);
        String[] textSizes = {"Standard", "Large", "Extra large"};
        String currentSize = settings.textScale() > 1.22f ? "Extra large" : settings.textScale() > 1.05f ? "Large" : "Standard";
        setSpinner(textSize, textSizes, currentSize);
        textSize.setOnItemSelectedListener(new SimpleItemSelected(position -> {
            float chosen = position == 1 ? 1.15f : position == 2 ? 1.30f : 1.0f;
            if (Math.abs(chosen - settings.textScale()) > .01f) {
                settings.setTextScale(chosen);
                recreate();
            }
        }));
        Switch voice = view.findViewById(R.id.voiceRepliesSwitch);
        voice.setChecked(settings.voiceReplies());
        voice.setOnCheckedChangeListener((button, checked) -> settings.setVoiceReplies(checked));
        Switch haptics = view.findViewById(R.id.hapticsSwitch);
        haptics.setChecked(settings.haptics());
        haptics.setOnCheckedChangeListener((button, checked) -> settings.setHaptics(checked));
        Switch speakerVerification = view.findViewById(R.id.speakerVerificationSwitch);
        speakerVerification.setChecked(settings.speakerVerification());
        speakerVerification.setOnCheckedChangeListener((button, checked) -> settings.setSpeakerVerification(checked));

        Switch requireUnlock = view.findViewById(R.id.requireUnlockSwitch);
        requireUnlock.setChecked(settings.requireUnlock());
        requireUnlock.setOnCheckedChangeListener((button, checked) -> settings.setRequireUnlock(checked));

        Spinner logMode = view.findViewById(R.id.logModeSpinner);
        String[] logLabels = {"Commands only", "Full transcripts", "Off"};
        String currentLog = AppSettings.LOG_FULL.equals(settings.logMode()) ? "Full transcripts"
                : AppSettings.LOG_OFF.equals(settings.logMode()) ? "Off" : "Commands only";
        setSpinner(logMode, logLabels, currentLog);
        logMode.setOnItemSelectedListener(new SimpleItemSelected(position -> settings.setLogMode(
                position == 1 ? AppSettings.LOG_FULL : position == 2 ? AppSettings.LOG_OFF : AppSettings.LOG_COMMANDS)));
        Spinner retention = view.findViewById(R.id.retentionSpinner);
        String[] retentionLabels = {"1 day", "7 days", "30 days", "Never automatically"};
        String currentRetention = settings.retentionDays() == 1 ? "1 day" : settings.retentionDays() == 30
                ? "30 days" : settings.retentionDays() <= 0 ? "Never automatically" : "7 days";
        setSpinner(retention, retentionLabels, currentRetention);
        retention.setOnItemSelectedListener(new SimpleItemSelected(position ->
                settings.setRetentionDays(position == 0 ? 1 : position == 1 ? 7 : position == 2 ? 30 : 0)));
        updateTabs();
    }

    private void saveListeningMode(String mode) {
        new AppSettings(this).setListeningMode(mode);
        if (IrisListeningService.isRunning) {
            stopListeningService();
            toast("Listening mode changed. Tap the orb to arm IRIS again.");
        }
    }

    private void setSpinner(Spinner spinner, String[] values, String current) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int index = Arrays.asList(values).indexOf(current);
        spinner.setSelection(Math.max(0, index), false);
    }

    private void updateTabs() {
        Button[] tabs = {tabAssistant, tabTraining, tabLogs, tabSettings};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackgroundResource(i == selectedTab
                    ? R.drawable.bg_tab_active : R.drawable.bg_tab_inactive);
            tabs[i].setTextColor(i == selectedTab ? Color.WHITE : getColor(R.color.text_muted));
        }
    }

    private void toggleIris() {
        if (IrisListeningService.isRunning) stopListeningService();
        else ensureStartPermissions();
    }

    private void ensureStartPermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.RECORD_AUDIO);
        addIfMissing(missing, Manifest.permission.READ_CONTACTS);
        addIfMissing(missing, Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= 31) addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        if (missing.isEmpty()) startListeningService();
        else new AlertDialog.Builder(this).setTitle("IRIS needs a few doors opened")
                .setMessage("Microphone listens; Contacts resolves names; Phone places confirmed calls; Bluetooth chooses a headset; Notifications keep listening visible.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue", (dialog, which) ->
                        requestPermissions(missing.toArray(new String[0]), PERMISSION_START)).show();
    }

    private void startListeningService() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toast("Microphone permission is required.");
            return;
        }
        AppSettings settings = new AppSettings(this);
        if (AppSettings.MODE_WAKE.equals(settings.listeningMode())
                && !new ProfileStore(this).getWakeProfile().isReady()) {
            showTraining();
            toast("Train your custom wake phrase first.");
            return;
        }
        startForegroundService(new Intent(this, IrisListeningService.class)
                .setAction(IrisListeningService.ACTION_START));
        updateAssistantState(true, AppSettings.MODE_WAKE.equals(settings.listeningMode()) ? "wake" : "command");
    }

    private void stopListeningService() {
        startService(new Intent(this, IrisListeningService.class).setAction(IrisListeningService.ACTION_STOP));
        updateAssistantState(false, "off");
    }

    private void restartIfRunning() {
        if (!IrisListeningService.isRunning) return;
        stopListeningService();
        handler.postDelayed(this::startListeningService, 450);
    }

    private void updateAssistantState(boolean active, String phase) {
        if (irisOrb == null) return;
        String safePhase = active ? (phase == null ? "wake" : phase) : "off";
        irisOrb.setPhase(safePhase);
        if (!active) {
            statusText.setText("IRIS is resting");
            subStatusText.setText("Tap the orb when you need me.");
            phaseChip.setText("OFFLINE");
            phaseChip.setTextColor(getColor(R.color.text_muted));
            liveTranscript.setText("Say “Call <contact name>”");
        } else if ("wake".equals(safePhase)) {
            ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
            statusText.setText("IRIS is standing by");
            subStatusText.setText("Say “" + wake.phrase + "”, then give the call command.");
            phaseChip.setText("CUSTOM WAKE PHRASE ARMED");
            phaseChip.setTextColor(getColor(R.color.magenta));
        } else if ("confirm".equals(safePhase)) {
            statusText.setText("Decision time");
            subStatusText.setText("Say Call or Cancel—or tap a button.");
            phaseChip.setText("CONFIRMATION");
            phaseChip.setTextColor(getColor(R.color.magenta));
        } else {
            statusText.setText("IRIS is listening");
            subStatusText.setText("Ask me to call someone.");
            phaseChip.setText("COMMAND WINDOW");
            phaseChip.setTextColor(getColor(R.color.cyan));
        }
        statusText.setTextColor(active ? getColor(R.color.cyan) : getColor(R.color.text_primary));
        micRouteText.setText("\uD83C\uDF99  Microphone: " + lastMicRoute);
        recognitionText.setText("\uD83E\uDDE0  Recognition: " + lastRecognition);
        updateFrequentContacts();
    }

    private void showAssistantMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        if (subStatusText != null) subStatusText.setText(message);
        if (irisOrb != null && message.toLowerCase(Locale.ROOT).contains("thinking")) irisOrb.setPhase("thinking");
    }

    private void updateFrequentContacts() {
        if (frequentContactsText == null) return;
        List<ProfileStore.Entry> frequent = new ProfileStore(this).frequentContacts(3);
        List<String> called = new ArrayList<>();
        for (ProfileStore.Entry entry : frequent) if (entry.callCount > 0)
            called.add(entry.contactName + " ×" + entry.callCount);
        frequentContactsText.setText(called.isEmpty()
                ? "Frequent calls will appear here after IRIS earns some history."
                : "MOST CALLED  •  " + String.join("   •   ", called));
    }

    private void beginWakeTraining() {
        String phrase = wakePhraseInput.getText().toString().trim();
        if (phrase.length() < 2) {
            wakePhraseInput.setError("Give IRIS at least two characters to listen for.");
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingTrainingKind = "wake";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_TRAIN);
            return;
        }
        resumeAfterWakeTraining = IrisListeningService.isRunning;
        if (resumeAfterWakeTraining) stopListeningService();
        wakePhraseBeingTrained = phrase;
        wakeTemplates.clear();
        wakeRawSamples.clear();
        wakeSampleIndex = 0;
        trainWakeButton.setEnabled(false);
        testWakeButton.setEnabled(false);
        if (wakeNormalState != null) wakeNormalState.setVisibility(View.GONE);
        if (wakeWizardState != null) wakeWizardState.setVisibility(View.VISIBLE);
        handler.postDelayed(this::captureNextWakeSample, resumeAfterWakeTraining ? 700 : 150);
    }

    private void captureNextWakeSample() {
        stopWakeTrainingEngine();
        if (wakeSampleIndex >= 5) return;
        int step = wakeSampleIndex + 1;
        if (wakeWizardDots != null) {
            StringBuilder dots = new StringBuilder();
            for (int d = 1; d <= 5; d++) dots.append(d <= step ? "\u25CF" : "\u25CB").append(d < 5 ? " " : "");
            wakeWizardDots.setText(dots.toString());
        }
        if (wakeWizardStep != null) wakeWizardStep.setText("Step " + step + " of 5");
        if (wakeWizardPrompt != null) wakeWizardPrompt.setText("Say \u201C" + wakePhraseBeingTrained + "\u201D now");
        if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\uD83D\uDD34  Recording\u2026");
        wakeTrainingStatus.setText("Recording sample " + step + " of 5\u2026");
        wakeTrainingEngine = new WakeWordEngine(this);
        wakeTrainingEngine.captureOne(new WakeWordEngine.Listener() {
            @Override public void onStatus(String status) { wakeTrainingStatus.setText(status); }
            @Override public void onSample(float[][] features, String quality, float signalToNoise, short[] rawAudio) {
                if ("Too noisy".equals(quality)) {
                    if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\u274C  Too noisy \u2014 try again in a quieter spot");
                    if (wakeWizardPrompt != null) wakeWizardPrompt.setText("Say \u201C" + wakePhraseBeingTrained + "\u201D again");
                    wakeTrainingStatus.setText("Too noisy. Retrying\u2026");
                    handler.postDelayed(MainActivity.this::captureNextWakeSample, 1200);
                    return;
                }
                wakeTemplates.add(features);
                wakeRawSamples.add(rawAudio);
                wakeSampleIndex++;
                String icon = "Clear".equals(quality) ? "\u2705" : "\u26A0\uFE0F";
                if (wakeWizardFeedback != null) wakeWizardFeedback.setText(icon + "  " + quality + " \u2022 " + Math.round(signalToNoise * 10) / 10f + "\u00D7 noise");
                wakeTrainingStatus.setText(quality + " sample recorded");
                if (wakeSampleIndex >= 5) finishWakeTraining();
                else handler.postDelayed(MainActivity.this::captureNextWakeSample, 750);
            }
            @Override public void onWakeDetected(double distance) { }
            @Override public void onError(String message) {
                wakeTrainingStatus.setText(message);
                trainWakeButton.setText("Retry sample");
                trainWakeButton.setEnabled(true);
            }
        });
    }

    private void finishWakeTraining() {
        // Only use first 3 templates for DTW (remaining are for voice enrollment)
        List<float[][]> dtwTemplates = wakeTemplates.size() > 3
                ? wakeTemplates.subList(0, 3) : wakeTemplates;
        if (!new ProfileStore(this).setWakeProfile(wakePhraseBeingTrained, dtwTemplates)) {
            wakeTrainingStatus.setText("IRIS could not securely save those samples. Please retry.");
            trainWakeButton.setEnabled(true);
            return;
        }
        // Enroll speaker voiceprint from all samples
        SpeakerVerifier verifier = new SpeakerVerifier();
        String enrollStatus = "voice not enrolled (model missing)";
        if (verifier.loadModel(this) && !wakeRawSamples.isEmpty()) {
            float[] voiceprint = verifier.enrollFromSamples(wakeRawSamples.toArray(new short[0][]));
            if (voiceprint != null) {
                new ProfileStore(this).setVoiceprint(voiceprint);
                enrollStatus = "voice enrolled \u2705";
            }
            verifier.close();
        }
        ProfileStore.WakeProfile saved = new ProfileStore(this).getWakeProfile();
        LogStore.append(this, "WAKE TRAINED", saved.phrase + " with " + dtwTemplates.size()
                + " acoustic templates, " + enrollStatus);
        wakeTrainingStatus.setText("✅  “" + saved.phrase + "” • "
                + dtwTemplates.size() + " templates • " + enrollStatus);
        trainWakeButton.setText("\uD83D\uDD04  Retrain");
        trainWakeButton.setEnabled(true);
        trainWakeButton.setOnClickListener(v -> beginWakeTraining());
        testWakeButton.setEnabled(true);
        if (wakeNormalState != null) wakeNormalState.setVisibility(View.VISIBLE);
        if (wakeWizardState != null) wakeWizardState.setVisibility(View.GONE);
        stopWakeTrainingEngine();
        if (resumeAfterWakeTraining) {
            resumeAfterWakeTraining = false;
            handler.postDelayed(this::startListeningService, 500);
        }
    }

    private void testWakePhrase() {
        ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
        if (!wake.isReady()) return;
        if (IrisListeningService.isRunning) stopListeningService();
        stopWakeTrainingEngine();
        wakeTrainingStatus.setText("Test armed • Say “" + wake.phrase + "”. Nothing will be called.");
        wakeTrainingEngine = new WakeWordEngine(this);
        WakeWordEngine active = wakeTrainingEngine;
        active.detect(wake.templates, wake.threshold, new WakeWordEngine.Listener() {
            @Override public void onStatus(String status) { }
            @Override public void onSample(float[][] features, String quality, float signalToNoise) { }
            @Override public void onWakeDetected(double distance) {
                wakeTrainingStatus.setText("Matched “" + wake.phrase + "” • distance "
                        + Math.round(distance * 100) / 100.0 + ". Test passed.");
                stopWakeTrainingEngine();
            }
            @Override public void onError(String message) { wakeTrainingStatus.setText(message); }
        });
        handler.postDelayed(() -> {
            if (wakeTrainingEngine == active) {
                stopWakeTrainingEngine();
                wakeTrainingStatus.setText("No match in 10 seconds. Try again or retrain in the room where you normally use IRIS.");
            }
        }, 10_000);
    }

    private void stopWakeTrainingEngine() {
        if (wakeTrainingEngine != null) { wakeTrainingEngine.stop(); wakeTrainingEngine = null; }
    }

    private void cancelWakeTraining() {
        stopWakeTrainingEngine();
        wakeTemplates.clear();
        wakeRawSamples.clear();
        wakeSampleIndex = 0;
        if (wakeNormalState != null) wakeNormalState.setVisibility(View.VISIBLE);
        if (wakeWizardState != null) wakeWizardState.setVisibility(View.GONE);
        trainWakeButton.setEnabled(true);
        ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
        testWakeButton.setEnabled(wake.isReady());
        if (resumeAfterWakeTraining) {
            resumeAfterWakeTraining = false;
            startListeningService();
        }
    }

    private void cancelContactTraining() {
        destroyTrainingRecognizer();
        trainingSamples.clear();
        trainingQualities.clear();
        trainingSampleIndex = 0;
        if (contactNormalState != null) contactNormalState.setVisibility(View.VISIBLE);
        if (contactWizardState != null) contactWizardState.setVisibility(View.GONE);
        startTrainingButton.setEnabled(true);
        if (resumeAfterContactTraining) {
            resumeAfterContactTraining = false;
            startListeningService();
        }
    }

    private void requestContactForTraining() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO) || !hasPermission(Manifest.permission.READ_CONTACTS)) {
            pendingTrainingKind = "contact";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS}, PERMISSION_TRAIN);
            return;
        }
        launchContactPicker();
    }

    private void launchContactPicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PICK_CONTACT);
        } catch (Exception error) { toast("No contacts app is available."); }
    }

    private void beginContactTraining() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("No speech recognition service is available.");
            return;
        }
        resumeAfterContactTraining = IrisListeningService.isRunning;
        if (resumeAfterContactTraining) stopListeningService();
        destroyTrainingRecognizer();
        trainingSamples.clear();
        trainingQualities.clear();
        trainingSampleIndex = 0;
        trainingErrorCount = 0;
        trainingRecognizer = createPreferredRecognizer();
        trainingRecognizer.setRecognitionListener(new TrainingListener());
        if (trainingContact != null) trainingContact.setText("Training: " + selectedContactName);
        startTrainingButton.setEnabled(false);
        if (contactNormalState != null) contactNormalState.setVisibility(View.GONE);
        if (contactWizardState != null) contactWizardState.setVisibility(View.VISIBLE);
        handler.postDelayed(this::recordNextTrainingSample, resumeAfterContactTraining ? 700 : 200);
    }

    private SpeechRecognizer createPreferredRecognizer() {
        if (Build.VERSION.SDK_INT >= 31 && new AppSettings(this).preferOnDevice()
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        return SpeechRecognizer.createSpeechRecognizer(this);
    }

    private void recordNextTrainingSample() {
        if (trainingRecognizer == null || trainingSampleIndex >= 3) return;
        trainingPeakRms = -20;
        int step = trainingSampleIndex + 1;
        if (contactWizardStep != null) contactWizardStep.setText("Sample " + step + " of 3");
        if (contactWizardDots != null) contactWizardDots.setText((step >= 1 ? "\u25CF" : "\u25CB")
                + " " + (step >= 2 ? "\u25CF" : "\u25CB")
                + " " + (step >= 3 ? "\u25CF" : "\u25CB"));
        if (contactWizardFeedback != null) contactWizardFeedback.setText("\uD83D\uDD34  Listening\u2026");
        trainingPrompt.setText("Say how you\u2019d ask IRIS to call " + selectedContactName);
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speech.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speech.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, new AppSettings(this).preferOnDevice());
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE, new AppSettings(this).resolvedLanguageTag());
        try { trainingRecognizer.startListening(speech); }
        catch (Exception error) { showTrainingRetry("The microphone blinked. Retry this sample."); }
    }

    private void showTrainingRetry(String message) {
        if (contactWizardFeedback != null) contactWizardFeedback.setText("\u26A0\uFE0F  " + message);
        trainingPrompt.setText("Tap below to retry, or cancel.");
        contactWizardCancel.setText("Retry");
        contactWizardCancel.setTextColor(getColor(R.color.cyan));
        contactWizardCancel.setOnClickListener(v -> {
            trainingErrorCount = 0;
            contactWizardCancel.setText("Cancel");
            contactWizardCancel.setTextColor(getColor(R.color.danger));
            contactWizardCancel.setOnClickListener(v2 -> cancelContactTraining());
            recordNextTrainingSample();
        });
    }

    private void finishContactTraining() {
        new ProfileStore(this).addTraining(selectedContactName, selectedContactNumber, trainingSamples);
        LogStore.append(this, "TRAINED", selectedContactName + " with " + trainingSamples.size() + " phrases");
        if (contactNormalState != null) contactNormalState.setVisibility(View.VISIBLE);
        if (contactWizardState != null) contactWizardState.setVisibility(View.GONE);
        List<String> learned = new ArrayList<>();
        for (int i = 0; i < trainingSamples.size(); i++) learned.add("“" + trainingSamples.get(i) + "” — " + trainingQualities.get(i));
        trainingPrompt.setText(String.join("\n", learned));
        startTrainingButton.setText("\uFF0B  Train New Contact");
        startTrainingButton.setEnabled(true);
        startTrainingButton.setOnClickListener(v -> requestContactForTraining());
        destroyTrainingRecognizer();
        updateProfileSummary();
        renderProfileManager();
        if (resumeAfterContactTraining) {
            resumeAfterContactTraining = false;
            handler.postDelayed(this::startListeningService, 500);
        }
    }

    private void updateProfileSummary() {
        if (profileSummary == null) return;
        ProfileStore store = new ProfileStore(this);
        int contacts = store.getEntries().size();
        int phrases = store.phraseCount();
        profileSummary.setText(contacts + (contacts == 1 ? " trained contact" : " trained contacts")
                + "  •  " + phrases + (phrases == 1 ? " phrase" : " phrases"));
    }

    private void renderProfileManager() {
        if (profileListHost == null) return;
        profileListHost.removeAllViews();
        for (ProfileStore.Entry entry : new ProfileStore(this).getEntries()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            row.setLayoutParams(rowParams);
            TextView text = new TextView(this);
            text.setText(entry.contactName + "\n" + (entry.phrases.isEmpty() ? "No custom phrases" : String.join(" • ", entry.phrases)));
            text.setTextColor(getColor(R.color.text_primary));
            text.setTextSize(12);
            text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            text.setOnClickListener(v -> showProfileDetails(entry));
            Button delete = new Button(this);
            delete.setText("Delete");
            delete.setTextColor(getColor(R.color.danger));
            delete.setTextSize(11);
            delete.setBackgroundResource(R.drawable.bg_button_secondary);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Forget " + entry.contactName + "?")
                    .setMessage("This deletes learned phrases for this contact.")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Forget", (dialog, which) -> {
                        new ProfileStore(this).deleteContact(entry.phoneNumber);
                        updateProfileSummary();
                        renderProfileManager();
                    }).show());
            row.addView(text);
            row.addView(delete, new LinearLayout.LayoutParams(dp(92), dp(48)));
            profileListHost.addView(row);
        }
    }

    private void showProfileDetails(ProfileStore.Entry entry) {
        EditText alias = new EditText(this);
        alias.setHint("Add phrase or nickname, e.g. Ring home");
        alias.setSingleLine(true);
        alias.setTextColor(getColor(R.color.text_primary));
        alias.setHintTextColor(getColor(R.color.text_muted));
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(22), dp(4), dp(22), 0);
        wrapper.addView(alias, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(entry.contactName)
                .setMessage(entry.phoneNumber + "\n\nLearned:\n" +
                        (entry.phrases.isEmpty() ? "No custom phrases yet" : String.join("\n", entry.phrases)))
                .setView(wrapper).setNegativeButton("Close", null)
                .setPositiveButton("Add phrase", (dialog, which) -> {
                    String phrase = alias.getText().toString().trim();
                    if (!phrase.isEmpty()) {
                        new ProfileStore(this).addTraining(entry.contactName, entry.phoneNumber,
                                java.util.Collections.singletonList(phrase));
                        toast("Added “" + phrase + "”.");
                        updateProfileSummary();
                        renderProfileManager();
                    }
                }).show();
    }

    private void testTrainedCommand() {
        if (new ProfileStore(this).phraseCount() == 0) {
            toast("Train at least one contact phrase first.");
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingTrainingKind = "dry";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_TRAIN);
            return;
        }
        if (IrisListeningService.isRunning) stopListeningService();
        destroyDryRunRecognizer();
        dryRunRecognizer = createPreferredRecognizer();
        trainingStep.setText("SAFE TEST — NO CALL");
        trainingPrompt.setText("Say one of your trained call commands. IRIS will only show the match.");
        dryRunRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { trainingPrompt.setText("Listening for a trained command…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { trainingPrompt.setText("Testing the match…"); }
            @Override public void onError(int error) { toast("No command matched. Try the test again."); destroyDryRunRecognizer(); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = heard == null || heard.isEmpty() ? "" : heard.get(0);
                ProfileStore.Match match = new ProfileStore(MainActivity.this).findMatch(text);
                if (match == null) new AlertDialog.Builder(MainActivity.this).setTitle("No trained match")
                        .setMessage("IRIS heard “" + text + "”. You can retrain or teach this correction.")
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Teach it", (d, w) -> offerCorrection(text)).show();
                else new AlertDialog.Builder(MainActivity.this).setTitle("Matched " + match.contactName)
                        .setMessage("Heard: “" + text + "”\nConfidence: " + Math.round(match.confidence * 100) + "%\n\nNo call was placed.")
                        .setPositiveButton("Perfect", null).show();
                destroyDryRunRecognizer();
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, new AppSettings(this).preferOnDevice())
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, new AppSettings(this).resolvedLanguageTag());
        dryRunRecognizer.startListening(speech);
    }

    private void offerCorrection(String heard) {
        if (heard == null || heard.trim().isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Teach IRIS what you meant?")
                .setMessage("I heard “" + heard + "”. Choose the contact this phrase should call.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Choose contact", (dialog, which) -> {
                    correctionPhrase = heard.trim();
                    requestContactForCorrection();
                }).show();
    }

    private void requestContactForCorrection() {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            pendingTrainingKind = "correction";
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PERMISSION_TRAIN);
        } else launchContactPicker();
    }

    private void showCallConfirmation(String name, String number, boolean authRequired) {
        if (confirmationShowing || name == null || number == null) return;
        if (irisOrb != null) {
            irisOrb.setPhase("confirm");
            irisOrb.setContactImage(loadContactPhoto(number));
        }
        confirmationShowing = true;
        new AlertDialog.Builder(this).setTitle("Call " + name + "?")
                .setMessage(number + "\n\nSay Call or Cancel, or decide here.")
                .setNegativeButton("Cancel", (dialog, which) -> sendCallDecision(false, name, number))
                .setPositiveButton("Call", (dialog, which) -> {
                    if (authRequired) authenticateThen("Unlock to call " + name,
                            () -> sendCallDecision(true, name, number));
                    else sendCallDecision(true, name, number);
                })
                .setOnCancelListener(dialog -> sendCallDecision(false, name, number))
                .setOnDismissListener(dialog -> confirmationShowing = false).show();
    }

    private Bitmap loadContactPhoto(String number) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null;
        Uri lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor cursor = getContentResolver().query(lookup,
                new String[]{ContactsContract.PhoneLookup.PHOTO_URI}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String photo = cursor.getString(0);
            if (photo == null) return null;
            try (InputStream input = getContentResolver().openInputStream(Uri.parse(photo))) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ignored) { return null; }
    }

    private void showDisambiguation(ArrayList<String> names, ArrayList<String> numbers) {
        if (names == null || numbers == null || names.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Which contact did you mean?")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    Intent choose = new Intent(this, IrisListeningService.class)
                            .setAction(IrisListeningService.ACTION_CHOOSE_CONTACT)
                            .putExtra(IrisListeningService.EXTRA_NAME, names.get(which))
                            .putExtra(IrisListeningService.EXTRA_NUMBER, numbers.get(which));
                    startService(choose);
                }).setNegativeButton("Cancel", (dialog, which) ->
                        startService(new Intent(this, IrisListeningService.class)
                                .setAction(IrisListeningService.ACTION_CANCEL_CALL))).show();
    }

    private void sendCallDecision(boolean confirmed, String name, String number) {
        startService(new Intent(this, IrisListeningService.class)
                .setAction(confirmed ? IrisListeningService.ACTION_CONFIRM_CALL : IrisListeningService.ACTION_CANCEL_CALL)
                .putExtra(IrisListeningService.EXTRA_NAME, name)
                .putExtra(IrisListeningService.EXTRA_NUMBER, number));
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(IrisListeningService.EXTRA_TEACH, false)) {
            String heard = intent.getStringExtra(IrisListeningService.EXTRA_TEXT);
            intent.removeExtra(IrisListeningService.EXTRA_TEACH);
            intent.removeExtra(IrisListeningService.EXTRA_TEXT);
            handler.postDelayed(() -> offerCorrection(heard), 250);
            return;
        }
        ArrayList<String> names = intent.getStringArrayListExtra(IrisListeningService.EXTRA_NAMES);
        ArrayList<String> numbers = intent.getStringArrayListExtra(IrisListeningService.EXTRA_NUMBERS);
        if (names != null && numbers != null) {
            intent.removeExtra(IrisListeningService.EXTRA_NAMES);
            intent.removeExtra(IrisListeningService.EXTRA_NUMBERS);
            handler.postDelayed(() -> showDisambiguation(names, numbers), 250);
            return;
        }
        String name = intent.getStringExtra(IrisListeningService.EXTRA_NAME);
        String number = intent.getStringExtra(IrisListeningService.EXTRA_NUMBER);
        boolean auth = intent.getBooleanExtra(IrisListeningService.EXTRA_AUTH_REQUIRED, false);
        if (name != null && number != null) {
            intent.removeExtra(IrisListeningService.EXTRA_NAME);
            intent.removeExtra(IrisListeningService.EXTRA_NUMBER);
            handler.postDelayed(() -> showCallConfirmation(name, number, auth), 250);
        }
    }

    private void authenticateThen(String title, Runnable action) {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            action.run();
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                    .setTitle(title).setSubtitle("IRIS protects private profiles, logs, and locked-screen calls.");
            if (Build.VERSION.SDK_INT >= 29) builder.setDeviceCredentialAllowed(true);
            else builder.setNegativeButton("Use screen lock", getMainExecutor(), (dialog, which) -> launchCredentialPrompt(title, action));
            BiometricPrompt prompt = builder.build();
            prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { action.run(); }
                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    if (Build.VERSION.SDK_INT >= 29) toast(errString.toString());
                }
            });
        } else launchCredentialPrompt(title, action);
    }

    private void launchCredentialPrompt(String title, Runnable action) {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent credential = keyguard == null ? null : keyguard.createConfirmDeviceCredentialIntent(title,
                "Confirm it is really you.");
        if (credential == null) action.run();
        else {
            pendingSecureAction = action;
            startActivityForResult(credential, AUTH_CREDENTIAL);
        }
    }

    private void downloadOfflineModel() {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            toast("This phone does not expose downloadable on-device speech models to IRIS.");
            return;
        }
        try {
            SpeechRecognizer recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, new AppSettings(this).resolvedLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizer.triggerModelDownload(speech);
            handler.postDelayed(recognizer::destroy, 1000);
            toast("Offline language-model request sent. Android may ask for approval.");
        } catch (Exception error) { toast("Model request failed: " + error.getMessage()); }
    }

    private void createProfileDocument() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "IRIS-profile-v2.irisprofile"), EXPORT_PROFILE);
    }

    private void openProfileDocument() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), IMPORT_PROFILE);
    }

    private void createLogDocument() {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "IRIS-activity-" + day + ".txt"), EXPORT_LOGS);
    }

    private void writeText(Uri uri, String text) throws Exception {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Could not open destination");
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readText(Uri uri) throws Exception {
        StringBuilder result = new StringBuilder();
        try (InputStream input = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
                if (result.length() > 3_000_000) throw new IllegalArgumentException("IRIS profile is unexpectedly large.");
            }
        }
        return result.toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AUTH_CREDENTIAL) {
            if (resultCode == RESULT_OK && pendingSecureAction != null) pendingSecureAction.run();
            pendingSecureAction = null;
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == PICK_CONTACT) {
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        selectedContactName = cursor.getString(0);
                        selectedContactNumber = cursor.getString(1);
                        if (correctionPhrase != null) {
                            new ProfileStore(this).addTraining(selectedContactName, selectedContactNumber,
                                    java.util.Collections.singletonList(correctionPhrase));
                            LogStore.append(this, "CORRECTION", "“" + correctionPhrase + "” → " + selectedContactName);
                            toast("Learned. “" + correctionPhrase + "” now means " + selectedContactName + ".");
                            correctionPhrase = null;
                            updateProfileSummary();
                            renderProfileManager();
                        } else beginContactTraining();
                    }
                }
            } else if (requestCode == EXPORT_PROFILE) {
                writeText(uri, new ProfileStore(this).exportJson());
                toast("IRIS profile exported.");
            } else if (requestCode == IMPORT_PROFILE) {
                int count = new ProfileStore(this).importAndMerge(readText(uri));
                LogStore.append(this, "IMPORT", count + " profile entries merged");
                updateProfileSummary();
                renderProfileManager();
                toast("Merged " + count + " trained contacts and portable wake data.");
            } else if (requestCode == EXPORT_LOGS) {
                String logs = LogStore.readNewestFirst(this);
                writeText(uri, logs.isEmpty() ? "IRIS has no recorded activity.\n" : logs);
                toast("Activity log exported.");
            }
        } catch (Exception error) { toast("That didn't work: " + error.getMessage()); }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_START) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) startListeningService();
            else toast("IRIS cannot listen without microphone access.");
        } else if (requestCode == PERMISSION_TRAIN) {
            String kind = pendingTrainingKind;
            pendingTrainingKind = "";
            if ("wake".equals(kind) && hasPermission(Manifest.permission.RECORD_AUDIO)) beginWakeTraining();
            else if ("contact".equals(kind) && hasPermission(Manifest.permission.RECORD_AUDIO)
                    && hasPermission(Manifest.permission.READ_CONTACTS)) launchContactPicker();
            else if ("correction".equals(kind) && hasPermission(Manifest.permission.READ_CONTACTS)) launchContactPicker();
            else if ("dry".equals(kind) && hasPermission(Manifest.permission.RECORD_AUDIO)) testTrainedCommand();
        }
    }

    private class TrainingListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {
            if (contactWizardFeedback != null) contactWizardFeedback.setText("\uD83D\uDD34  Listening\u2026");
            trainingPrompt.setText("Say it naturally.");
        }
        @Override public void onBeginningOfSpeech() {
            if (contactWizardFeedback != null) contactWizardFeedback.setText("\uD83C\uDF99  Got you\u2026");
            trainingPrompt.setText("Keep going\u2026");
        }
        @Override public void onRmsChanged(float rmsdB) { trainingPeakRms = Math.max(trainingPeakRms, rmsdB); }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() {
            if (contactWizardFeedback != null) contactWizardFeedback.setText("\uD83E\uDDE0  Checking\u2026");
            trainingPrompt.setText("Processing sample\u2026");
        }
        @Override public void onError(int error) {
            if (trainingRecognizer == null) return;
            if (++trainingErrorCount < 3) handler.postDelayed(MainActivity.this::recordNextTrainingSample, 700);
            else showTrainingRetry("The speech service is having a moment. Check the mic and retry.");
        }
        @Override public void onResults(Bundle results) {
            ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (heard == null || heard.isEmpty() || heard.get(0).trim().isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
            String phrase = heard.get(0).trim();
            String quality = trainingPeakRms >= 7 ? "Clear" : trainingPeakRms >= 3 ? "Usable" : "Quiet";
            trainingSamples.add(phrase);
            trainingQualities.add(quality);
            trainingSampleIndex++;
            trainingErrorCount = 0;
            if (trainingSampleIndex >= 3) finishContactTraining();
            else {
                String icon = "Clear".equals(quality) ? "\u2705" : "Usable".equals(quality) ? "\u26A0\uFE0F" : "\uD83D\uDD07";
                if (contactWizardFeedback != null) contactWizardFeedback.setText(icon + "  " + quality + " \u2022 \u201C" + phrase + "\u201D");
                trainingPrompt.setText("Great! Next sample\u2026");
                handler.postDelayed(MainActivity.this::recordNextTrainingSample, 750);
            }
        }
        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> heard = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (heard != null && !heard.isEmpty()) {
                trainingPrompt.setText("\u201C" + heard.get(0) + "\u201D");
            }
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    }

    private void destroyTrainingRecognizer() {
        if (trainingRecognizer != null) {
            trainingRecognizer.cancel();
            trainingRecognizer.destroy();
            trainingRecognizer = null;
        }
    }

    private void destroyDryRunRecognizer() {
        if (dryRunRecognizer != null) {
            try { dryRunRecognizer.cancel(); } catch (Exception ignored) { }
            dryRunRecognizer.destroy();
            dryRunRecognizer = null;
        }
    }

    private void addIfMissing(List<String> list, String permission) {
        if (!hasPermission(permission)) list.add(permission);
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyTrainingRecognizer();
        destroyDryRunRecognizer();
        stopWakeTrainingEngine();
        super.onDestroy();
    }

    private static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        interface Selection { void onSelected(int position); }
        private final Selection selection;
        private boolean initialized;
        SimpleItemSelected(Selection selection) { this.selection = selection; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            if (!initialized) { initialized = true; return; }
            selection.onSelected(position);
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }
}
