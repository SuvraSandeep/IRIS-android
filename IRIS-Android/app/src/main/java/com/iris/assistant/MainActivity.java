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
    private Button tabMemory;
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
    private TimedRecorder timedRecorder;
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
        try { PersonalProfile.seedInto(this); } catch (Throwable ignored) { }
        // Apply the user's theme (Dark keeps the gradient; AMOLED = pure black).
        try {
            AppSettings appSettings = new AppSettings(this);
            View root = findViewById(R.id.rootLayout);
            if (AppSettings.THEME_AMOLED.equals(appSettings.theme())) {
                if (root != null) root.setBackgroundColor(0xFF000000);
                if (getWindow() != null) {
                    getWindow().setStatusBarColor(0xFF000000);
                    getWindow().setNavigationBarColor(0xFF000000);
                }
            }
        } catch (Throwable ignored) { }
        tabAssistant = findViewById(R.id.tabAssistant);
        tabTraining = findViewById(R.id.tabTraining);
        tabLogs = findViewById(R.id.tabLogs);
        tabMemory = findViewById(R.id.tabMemory);
        tabSettings = findViewById(R.id.tabSettings);
        tabAssistant.setOnClickListener(v -> showAssistant());
        tabTraining.setOnClickListener(v -> showTraining());
        tabLogs.setOnClickListener(v -> showLogs());
        tabMemory.setOnClickListener(v -> showMemory());
        tabSettings.setOnClickListener(v -> showSettings());
        showAssistant();
        handleLaunchIntent(getIntent());
        // Auto-download the AI brain in the background only if the user enabled AI
        try {
            if (new AppSettings(this).aiEnabled()) {
                ModelManager.autoDownloadGemmaIfNeeded(this);
            }
        } catch (Throwable t) {
            android.util.Log.w("IRIS", "Auto model download skipped: " + t.getMessage());
        }
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
        // Apply appearance to the orb
        AppSettings appearance = new AppSettings(this);
        irisOrb.setAccent(ThemeManager.accent(appearance));
        irisOrb.setReduceMotion(appearance.reduceMotion());

        // Text command input + send
        EditText commandInput = view.findViewById(R.id.commandInput);
        Button sendCommandButton = view.findViewById(R.id.sendCommandButton);
        ThemeManager.primaryButton(sendCommandButton, ThemeManager.accent(appearance));
        Runnable sendTyped = () -> {
            String text = commandInput.getText().toString().trim();
            if (text.isEmpty()) return;
            sendTextCommand(text);
            commandInput.setText("");
        };
        sendCommandButton.setOnClickListener(v -> sendTyped.run());
        commandInput.setOnEditorActionListener((tv, actionId, e) -> { sendTyped.run(); return true; });

        // Customizable quick-action chips
        LinearLayout chipsRow = view.findViewById(R.id.quickChipsRow);
        buildQuickChips(chipsRow, appearance);

        updateFrequentContacts();
        updateTabs();
        updateAssistantState(IrisListeningService.isRunning, IrisListeningService.currentPhase);
    }

    /** Map a chip id to its label and the command text it sends. */
    private static final String[][] CHIP_DEFS = {
            {"call", "\u260E Call", "__PROMPT_CALL__"},
            {"text", "\u2709 Text", "__PROMPT_TEXT__"},
            {"alarm", "\u23F0 Alarm", "__PROMPT_ALARM__"},
            {"weather", "\u2600 Weather", "what's the weather"},
            {"torch", "\uD83D\uDD26 Torch", "turn on the flashlight"},
            {"time", "\uD83D\uDD52 Time", "what time is it"},
            {"battery", "\uD83D\uDD0B Battery", "battery level"},
            {"location", "\uD83D\uDCCD Location", "where am i"},
            {"notifications", "\uD83D\uDCEC Notifications", "read my notifications"},
    };

    /** Build the home quick-action chips from the user's saved selection. */
    private void buildQuickChips(LinearLayout row, AppSettings appearance) {
        if (row == null) return;
        row.removeAllViews();
        int accent = ThemeManager.accent(appearance);
        String csv = appearance.homeChips();
        List<String> ids = Arrays.asList(csv.split(","));
        for (String id : ids) {
            String label = null, command = null;
            for (String[] def : CHIP_DEFS) {
                if (def[0].equals(id.trim())) { label = def[1]; command = def[2]; break; }
            }
            if (label == null) continue;
            final String cmd = command;
            Button chip = new Button(this);
            chip.setText(label);
            chip.setAllCaps(false);
            chip.setTextColor(getColorCompat(R.color.text_primary));
            chip.setTextSize(12f);
            chip.setBackgroundResource(R.drawable.bg_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setOnClickListener(v -> onQuickChip(cmd));
            row.addView(chip);
        }
        // Trailing "Edit" chip to customize the row
        Button edit = new Button(this);
        edit.setText("\uFF0B Edit");
        edit.setAllCaps(false);
        edit.setTextColor(getColorCompat(R.color.text_muted));
        edit.setTextSize(12f);
        edit.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        edit.setLayoutParams(elp);
        edit.setPadding(dp(16), 0, dp(16), 0);
        edit.setOnClickListener(v -> showChipEditor());
        row.addView(edit);
    }

    /** Multi-select editor for which quick-action chips appear on the home screen. */
    private void showChipEditor() {
        AppSettings settings = new AppSettings(this);
        List<String> current = Arrays.asList(settings.homeChips().split(","));
        String[] labels = new String[CHIP_DEFS.length];
        boolean[] checked = new boolean[CHIP_DEFS.length];
        for (int i = 0; i < CHIP_DEFS.length; i++) {
            labels[i] = CHIP_DEFS[i][1];
            checked[i] = current.contains(CHIP_DEFS[i][0]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Home quick actions")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < CHIP_DEFS.length; i++) {
                        if (checked[i]) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(CHIP_DEFS[i][0]);
                        }
                    }
                    settings.setHomeChips(sb.toString());
                    if (selectedTab == 0) showAssistant();
                    else toast("Quick actions updated.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Handle a quick-action chip: send a command, or prompt for the missing detail. */
    private void onQuickChip(String command) {
        if ("__PROMPT_CALL__".equals(command)) { promptForCommand("Call who?", "Call "); }
        else if ("__PROMPT_TEXT__".equals(command)) { promptForCommand("Text who, and what?", "Text "); }
        else if ("__PROMPT_ALARM__".equals(command)) { promptForCommand("Alarm for when? (e.g. 7:30 am)", "Set an alarm for "); }
        else sendTextCommand(command);
    }

    /** Show a small input dialog prefilled with a command stub, then send it. */
    private void promptForCommand(String title, String prefix) {
        final EditText input = new EditText(this);
        input.setText(prefix);
        input.setSelection(prefix.length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Send", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) sendTextCommand(text);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Send a typed/chip command to the listening service. */
    private void sendTextCommand(String text) {
        try {
            Intent i = new Intent(this, IrisListeningService.class);
            i.setAction(IrisListeningService.ACTION_PROCESS_TEXT);
            i.putExtra(IrisListeningService.EXTRA_TEXT, text);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            toast("Couldn't run that command.");
        }
    }

    /** Wire the Appearance section: accent swatches, AMOLED, reduce motion, density. */
    private void wireAppearance(View view, AppSettings settings) {
        LinearLayout accentRow = view.findViewById(R.id.accentRow);
        if (accentRow != null) {
            accentRow.removeAllViews();
            int current = ThemeManager.accent(settings);
            for (String[] preset : ThemeManager.ACCENTS) {
                int color;
                try { color = Color.parseColor(preset[1]); } catch (Exception e) { continue; }
                View sw = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
                lp.rightMargin = dp(10);
                sw.setLayoutParams(lp);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                gd.setColor(color);
                if (color == current) gd.setStroke(dp(3), 0xFFFFFFFF);
                sw.setBackground(gd);
                sw.setContentDescription(preset[0] + " accent"
                        + (color == current ? ", selected" : ""));
                final String hex = preset[1];
                sw.setOnClickListener(v -> { settings.setAccentColor(hex); recreate(); });
                accentRow.addView(sw);
            }
        }
        Switch amoled = view.findViewById(R.id.amoledSwitch);
        if (amoled != null) {
            amoled.setChecked(AppSettings.THEME_AMOLED.equals(settings.theme()));
            amoled.setOnCheckedChangeListener((b, checked) -> {
                settings.setTheme(checked ? AppSettings.THEME_AMOLED : AppSettings.THEME_DARK);
                recreate();
            });
        }
        Switch reduce = view.findViewById(R.id.reduceMotionSwitch);
        if (reduce != null) {
            reduce.setChecked(settings.reduceMotion());
            reduce.setOnCheckedChangeListener((b, checked) -> settings.setReduceMotion(checked));
        }
        Switch density = view.findViewById(R.id.densitySwitch);
        if (density != null) {
            density.setChecked(AppSettings.DENSITY_COMPACT.equals(settings.density()));
            density.setOnCheckedChangeListener((b, checked) ->
                    settings.setDensity(checked ? AppSettings.DENSITY_COMPACT : AppSettings.DENSITY_COMFORTABLE));
        }
        Button editChips = view.findViewById(R.id.editChipsButton);
        if (editChips != null) editChips.setOnClickListener(v -> showChipEditor());
    }

    private int getColorCompat(int res) { return getResources().getColor(res, getTheme()); }

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
        trainWakeButton.setOnClickListener(v -> authenticateThen("\uD83D\uDD12 Train wake phrase", this::beginWakeTraining));
        testWakeButton.setOnClickListener(v -> testWakePhrase());
        wakeWizardCancel.setOnClickListener(v -> cancelWakeTraining());
        startTrainingButton.setOnClickListener(v -> authenticateThen("\uD83D\uDD12 Train contact", this::requestContactForTraining));
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
        final String allLogs = logs;
        logText.setText(logs.isEmpty() ? "No activity yet. IRIS is impressively innocent." : logs);
        // Filter chips
        LinearLayout filterRow = view.findViewById(R.id.logFilterRow);
        buildLogFilters(filterRow, logText, allLogs);
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

    /** Category filter definitions for the Activity log: {label, matching tags CSV}. */
    private static final String[][] LOG_FILTERS = {
            {"All", ""},
            {"Calls", "CALL,REDIAL,CONFIRM,DIAL"},
            {"Messages", "SMS,WHATSAPP,NOTIFICATION"},
            {"AI", "LLM,CHAT,MEMORY"},
            {"Actions", "ALARM,TIMER,REMINDER,TORCH,VOLUME,SEARCH,WEATHER,LOCATION,CONNECTIVITY"},
            {"Errors", "ERROR,FAILED,CANCELLED"},
    };

    /** Build the Activity filter chips and wire them to filter the log text. */
    private void buildLogFilters(LinearLayout row, TextView logText, String allLogs) {
        if (row == null) return;
        row.removeAllViews();
        for (String[] f : LOG_FILTERS) {
            final String tagsCsv = f[1];
            Button chip = new Button(this);
            chip.setText(f[0]);
            chip.setAllCaps(false);
            chip.setTextColor(getColorCompat(R.color.text_secondary));
            chip.setTextSize(12f);
            chip.setBackgroundResource(R.drawable.bg_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> logText.setText(filterLogs(allLogs, tagsCsv)));
            row.addView(chip);
        }
    }

    /** Keep only log lines whose tag matches one of the CSV tags (empty CSV = all). */
    private String filterLogs(String allLogs, String tagsCsv) {
        if (allLogs == null || allLogs.isEmpty()) return "No activity yet. IRIS is impressively innocent.";
        if (tagsCsv == null || tagsCsv.isEmpty()) return allLogs;
        String[] tags = tagsCsv.split(",");
        StringBuilder sb = new StringBuilder();
        for (String line : allLogs.split("\n")) {
            String upper = line.toUpperCase(Locale.ROOT);
            for (String tag : tags) {
                if (upper.contains(tag)) { sb.append(line).append("\n"); break; }
            }
        }
        return sb.length() == 0 ? "Nothing here yet." : sb.toString().trim();
    }

    private void showMemory() {
        selectedTab = 3;
        contentHost.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.view_memory, contentHost, false);
        contentHost.addView(view);

        TextView memorySummary = view.findViewById(R.id.memorySummary);
        LinearLayout suggestionsHost = view.findViewById(R.id.suggestionsHost);
        LinearLayout memoryListHost = view.findViewById(R.id.memoryListHost);

        int count = MemoryStore.count(this);
        String name = MemoryStore.ownerName(this);
        String greeting = name != null ? "Hi " + name + "! " : "";
        memorySummary.setText(greeting + count + (count == 1 ? " memory" : " memories")
                + " \u2022 " + (count == 0 ? "Teach me about yourself" : "Tap + or \uD83C\uDF99 to add"));

        // Add memory (with biometric)
        view.findViewById(R.id.addMemoryButton).setOnClickListener(v ->
                authenticateThen("\uD83D\uDD12 Add memory", () -> showAddMemoryDialog(memorySummary, memoryListHost)));

        // Speak to add memory
        view.findViewById(R.id.speakMemoryButton).setOnClickListener(v -> speakToAddMemory(memorySummary, memoryListHost));

        // Search
        view.findViewById(R.id.searchMemoryButton).setOnClickListener(v -> showSearchMemoryDialog(memoryListHost));

        // Export/Import
        view.findViewById(R.id.exportMemoryButton).setOnClickListener(v ->
                authenticateThen("Export IRIS memory", this::createMemoryDocument));
        view.findViewById(R.id.importMemoryButton).setOnClickListener(v ->
                authenticateThen("Import IRIS memory", this::openMemoryDocument));

        // Clear all
        view.findViewById(R.id.clearMemoryButton).setOnClickListener(v ->
                authenticateThen("\uD83D\uDD12 Clear all memories", () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Clear all memories?")
                        .setMessage("This removes everything IRIS knows about you.")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Clear", (d, w) -> {
                            try { SecureStore.write(this, "iris_memory_v1.enc", ""); } catch (Exception ignored) { }
                            toast("All memories cleared.");
                            showMemory();
                        }).show()));

        // Render suggestions
        renderSuggestions(suggestionsHost, memorySummary, memoryListHost);

        // Render memory list
        renderMemoryList(memoryListHost);
        updateTabs();
    }

    private void renderSuggestions(LinearLayout host, TextView summary, LinearLayout listHost) {
        host.removeAllViews();
        List<BehaviorAnalyzer.Suggestion> suggestions = BehaviorAnalyzer.analyze(this);
        if (suggestions.isEmpty()) return;

        TextView header = new TextView(this);
        header.setText("\u26A1 IRIS NOTICED");
        header.setTextColor(getColor(R.color.amber));
        header.setTextSize(11);
        header.setLetterSpacing(0.08f);
        header.setPadding(0, dp(4), 0, dp(8));
        host.addView(header);

        for (BehaviorAnalyzer.Suggestion s : suggestions) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(6);
            card.setLayoutParams(params);

            TextView text = new TextView(this);
            text.setText(s.emoji + "  " + s.title);
            text.setTextColor(getColor(R.color.text_primary));
            text.setTextSize(13);
            card.addView(text);

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, dp(8), 0, 0);

            Button accept = new Button(this);
            accept.setText("Save");
            accept.setTextColor(getColor(R.color.mint));
            accept.setTextSize(11);
            accept.setBackgroundResource(R.drawable.bg_button_secondary);
            accept.setOnClickListener(v -> {
                if (s.value != null && !s.value.isEmpty()) {
                    MemoryStore.Memory m = new MemoryStore.Memory();
                    m.category = s.category;
                    m.key = s.key;
                    m.value = s.value;
                    m.source = "auto_learned";
                    MemoryStore.add(this, m);
                    toast("\u26A1 Saved: " + s.key);
                } else {
                    showAddMemoryDialog(summary, listHost);
                }
                host.removeView(card);
            });

            Button dismiss = new Button(this);
            dismiss.setText("Dismiss");
            dismiss.setTextColor(getColor(R.color.text_muted));
            dismiss.setTextSize(11);
            dismiss.setBackgroundResource(R.drawable.bg_button_secondary);
            dismiss.setOnClickListener(v -> host.removeView(card));

            buttons.addView(accept, new LinearLayout.LayoutParams(dp(80), dp(36)));
            buttons.addView(dismiss, new LinearLayout.LayoutParams(dp(80), dp(36)));
            card.addView(buttons);
            host.addView(card);
        }
    }

    private void speakToAddMemory(TextView summary, LinearLayout listHost) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toast("Microphone permission needed.");
            return;
        }
        if (IrisListeningService.isRunning) stopListeningService();
        destroyDryRunRecognizer();
        dryRunRecognizer = createPreferredRecognizer();
        toast("\uD83C\uDF99 Listening... say what I should remember.");
        dryRunRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { toast("Processing..."); }
            @Override public void onError(int error) { toast("Couldn\u2019t hear you. Try again."); destroyDryRunRecognizer(); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = heard == null || heard.isEmpty() ? "" : heard.get(0);
                if (text.isEmpty()) { toast("Nothing heard."); destroyDryRunRecognizer(); return; }
                MemoryParser.ParsedMemory parsed = MemoryParser.parse(text);
                if (parsed != null && parsed.confidence >= 0.80f) {
                    MemoryStore.Memory m = new MemoryStore.Memory();
                    m.category = parsed.category;
                    m.key = parsed.key;
                    m.value = parsed.value;
                    m.source = "voice";
                    MemoryStore.add(MainActivity.this, m);
                    toast("\uD83E\uDDE0 " + parsed.key + " = " + parsed.value);
                    LogStore.append(MainActivity.this, "MEMORY", "Voice: " + parsed.key + " = " + parsed.value);
                    int count = MemoryStore.count(MainActivity.this);
                    summary.setText(count + " memories \u2022 Last updated just now");
                    renderMemoryList(listHost);
                } else {
                    // Low confidence or unparseable — save as note with confirm
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("\uD83E\uDDE0 Save as memory?")
                        .setMessage("I heard: \u201C" + text + "\u201D")
                        .setNegativeButton("Discard", null)
                        .setPositiveButton("Save as note", (d, w) -> {
                            MemoryStore.Memory m = new MemoryStore.Memory();
                            m.category = MemoryStore.CAT_ABOUT_ME;
                            m.key = "note";
                            m.value = text;
                            m.source = "voice";
                            MemoryStore.add(MainActivity.this, m);
                            toast("\uD83E\uDDE0 Saved as note.");
                            renderMemoryList(listHost);
                        }).show();
                }
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

    private void showAddMemoryDialog(TextView summary, LinearLayout listHost) {
        String[] categories = {"About Me", "People", "Preference", "Rule", "Correction", "Schedule"};
        String[] categoryKeys = {MemoryStore.CAT_ABOUT_ME, MemoryStore.CAT_PEOPLE,
                MemoryStore.CAT_PREFERENCE, MemoryStore.CAT_RULE, MemoryStore.CAT_CORRECTION, MemoryStore.CAT_SCHEDULE};

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(22), dp(8), dp(22), 0);

        Spinner catSpinner = new Spinner(this);
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, categories);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        catSpinner.setAdapter(catAdapter);
        wrapper.addView(catSpinner);

        EditText keyInput = new EditText(this);
        keyInput.setHint("Label (e.g. name, brother, no calls after)");
        keyInput.setSingleLine(true);
        keyInput.setTextColor(getColor(R.color.text_primary));
        keyInput.setHintTextColor(getColor(R.color.text_muted));
        wrapper.addView(keyInput);

        EditText valueInput = new EditText(this);
        valueInput.setHint("Value (e.g. Sandeep, Rahul, 10 PM)");
        valueInput.setSingleLine(true);
        valueInput.setTextColor(getColor(R.color.text_primary));
        valueInput.setHintTextColor(getColor(R.color.text_muted));
        wrapper.addView(valueInput);

        EditText detailInput = new EditText(this);
        detailInput.setHint("Detail (optional, e.g. phone number)");
        detailInput.setSingleLine(true);
        detailInput.setTextColor(getColor(R.color.text_primary));
        detailInput.setHintTextColor(getColor(R.color.text_muted));
        wrapper.addView(detailInput);

        new AlertDialog.Builder(this)
                .setTitle("\uD83E\uDDE0 Teach IRIS something new")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remember", (dialog, which) -> {
                    String key = keyInput.getText().toString().trim();
                    String value = valueInput.getText().toString().trim();
                    String detail = detailInput.getText().toString().trim();
                    if (key.isEmpty() || value.isEmpty()) {
                        toast("Both label and value are required.");
                        return;
                    }
                    MemoryStore.Memory memory = new MemoryStore.Memory();
                    memory.category = categoryKeys[catSpinner.getSelectedItemPosition()];
                    memory.key = key;
                    memory.value = value;
                    if (!detail.isEmpty()) memory.detail = detail;
                    MemoryStore.add(this, memory);
                    toast("\uD83E\uDDE0 Remembered: " + key + " = " + value);
                    LogStore.append(this, "MEMORY", "Added: " + key + " = " + value);
                    int count = MemoryStore.count(this);
                    summary.setText(count + (count == 1 ? " memory" : " memories") + " \u2022 Last updated just now");
                    renderMemoryList(listHost);
                }).show();
    }

    private void showSearchMemoryDialog(LinearLayout listHost) {
        EditText searchInput = new EditText(this);
        searchInput.setHint("Search memories...");
        searchInput.setSingleLine(true);
        searchInput.setTextColor(getColor(R.color.text_primary));
        searchInput.setHintTextColor(getColor(R.color.text_muted));
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(22), dp(8), dp(22), 0);
        wrapper.addView(searchInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("\uD83D\uDD0D Search Memories")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Search", (dialog, which) -> {
                    String query = searchInput.getText().toString().trim();
                    if (query.isEmpty()) return;
                    listHost.removeAllViews();
                    List<MemoryStore.Memory> results = MemoryStore.search(this, query);
                    if (results.isEmpty()) {
                        TextView empty = new TextView(this);
                        empty.setText("No memories match \u201C" + query + "\u201D");
                        empty.setTextColor(getColor(R.color.text_muted));
                        empty.setPadding(0, dp(12), 0, 0);
                        listHost.addView(empty);
                    } else {
                        for (MemoryStore.Memory m : results) addMemoryCard(listHost, m);
                    }
                }).show();
    }

    private void renderMemoryList(LinearLayout listHost) {
        listHost.removeAllViews();
        String[] catOrder = {MemoryStore.CAT_ABOUT_ME, MemoryStore.CAT_PEOPLE,
                MemoryStore.CAT_PREFERENCE, MemoryStore.CAT_RULE,
                MemoryStore.CAT_CORRECTION, MemoryStore.CAT_SCHEDULE};
        String[] catLabels = {"\uD83D\uDC64  ABOUT ME", "\uD83D\uDC65  PEOPLE",
                "\u2699\uFE0F  PREFERENCES", "\uD83D\uDCCB  RULES",
                "\uD83D\uDD27  CORRECTIONS", "\uD83D\uDCC5  SCHEDULE"};
        int[] catColors = {R.color.cyan, R.color.magenta, R.color.violet,
                R.color.mint, R.color.amber, R.color.cyan};

        List<MemoryStore.Memory> all = MemoryStore.getAll(this);
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No memories yet. Tap \u201C+ Add Memory\u201D to teach IRIS about yourself.");
            empty.setTextColor(getColor(R.color.text_muted));
            empty.setTextSize(13);
            empty.setPadding(0, dp(16), 0, 0);
            listHost.addView(empty);
            return;
        }

        for (int c = 0; c < catOrder.length; c++) {
            List<MemoryStore.Memory> catMemories = new ArrayList<>();
            for (MemoryStore.Memory m : all) {
                if (catOrder[c].equals(m.category)) catMemories.add(m);
            }
            if (catMemories.isEmpty()) continue;

            // Category header
            TextView header = new TextView(this);
            header.setText(catLabels[c]);
            header.setTextColor(getColor(catColors[c]));
            header.setTextSize(11);
            header.setTextColor(getColor(catColors[c]));
            header.setPadding(0, dp(14), 0, dp(6));
            header.setLetterSpacing(0.08f);
            listHost.addView(header);

            for (MemoryStore.Memory m : catMemories) addMemoryCard(listHost, m);
        }
    }

    private void addMemoryCard(LinearLayout host, MemoryStore.Memory memory) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(6);
        row.setLayoutParams(rowParams);

        TextView text = new TextView(this);
        String display = memory.key + ": " + memory.value;
        if (memory.detail != null && !memory.detail.isEmpty()) display += "\n" + memory.detail;
        text.setText(display);
        text.setTextColor(getColor(R.color.text_primary));
        text.setTextSize(12);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button delete = new Button(this);
        delete.setText("\u00D7");
        delete.setTextColor(getColor(R.color.danger));
        delete.setTextSize(16);
        delete.setBackgroundResource(R.drawable.bg_button_secondary);
        delete.setOnClickListener(v -> authenticateThen("\uD83D\uDD12 Delete memory", () -> {
            MemoryStore.delete(this, memory.id);
            host.removeView(row);
            toast("Memory removed.");
        }));

        row.addView(text);
        row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(40)));
        host.addView(row);
    }

    private static final int EXPORT_MEMORY = 205;
    private static final int IMPORT_MEMORY = 206;

    private void createMemoryDocument() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "IRIS-memory.irismemory"), EXPORT_MEMORY);
    }

    private void openMemoryDocument() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), IMPORT_MEMORY);
    }

    private void showSettings() {
        selectedTab = 4;
        contentHost.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.view_settings, contentHost, false);
        contentHost.addView(view);
        AppSettings settings = new AppSettings(this);
        wireAppearance(view, settings);
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
        view.findViewById(R.id.testTtsButton).setOnClickListener(v -> {
            android.speech.tts.TextToSpeech testTts = new android.speech.tts.TextToSpeech(this, status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    android.speech.tts.TextToSpeech tts = new android.speech.tts.TextToSpeech(this, s -> {});
                    toast("\u2705 TTS engine: " + tts.getDefaultEngine());
                    tts.shutdown();
                } else {
                    toast("\u274C TTS failed. Check Settings \u2192 Apps \u2192 Google TTS.");
                }
            });
            testTts.setLanguage(java.util.Locale.getDefault());
            testTts.speak("Hello! I am IRIS, your personal assistant. I can hear and speak.",
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test");
            handler.postDelayed(testTts::shutdown, 5000);
        });
        view.findViewById(R.id.downloadModelButton).setOnClickListener(v -> showOfflineSpeechStatus());
        view.findViewById(R.id.testTtsButton).setOnClickListener(v -> testVoice());

        TextView brainStatus = view.findViewById(R.id.brainStatus);
        Button brainButton = view.findViewById(R.id.downloadBrainButton);
        Switch lockControlSwitch = view.findViewById(R.id.lockControlSwitch);
        if (lockControlSwitch != null) {
            lockControlSwitch.setChecked(settings.lockScreenControl());
            lockControlSwitch.setOnCheckedChangeListener((b, checked) -> {
                settings.setLockScreenControl(checked);
                toast(checked
                        ? "Lock-screen control on. Keep IRIS listening (disable battery optimization for reliability)."
                        : "Lock-screen control off.");
            });
        }
        Switch aiEnabledSwitch = view.findViewById(R.id.aiEnabledSwitch);
        if (aiEnabledSwitch != null) {
            aiEnabledSwitch.setChecked(settings.aiEnabled());
            aiEnabledSwitch.setOnCheckedChangeListener((b, checked) -> {
                settings.setAiEnabled(checked);
                if (checked) {
                    toast("AI enabled. Download the brain if needed, then restart IRIS listening.");
                    try { ModelManager.autoDownloadGemmaIfNeeded(this); } catch (Throwable ignored) { }
                } else {
                    toast("AI disabled. Using reliable rule-based replies.");
                }
            });
        }
        EditText hfTokenInput = view.findViewById(R.id.hfTokenInput);
        Button saveTokenButton = view.findViewById(R.id.saveTokenButton);
        if (hfTokenInput != null) {
            String existing = settings.hfToken();
            if (!existing.isEmpty()) hfTokenInput.setText(existing);
        }
        if (saveTokenButton != null) {
            saveTokenButton.setOnClickListener(v -> {
                String tok = hfTokenInput.getText().toString().trim();
                settings.setHfToken(tok);
                toast(tok.isEmpty() ? "Token cleared." : "Token saved \u2705");
            });
        }
        if (ModelManager.gemmaPresent(this)) {
            brainStatus.setText("\uD83E\uDDE0 AI brain: installed \u2705 — conversational AI active");
            brainButton.setText("\uD83E\uDDE0 Re-download AI brain");
        }
        brainButton.setOnClickListener(v -> {
            if (ModelManager.gemmaPresent(this)) {
                toast("AI brain already installed.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("\uD83E\uDDE0 Download AI brain?")
                    .setMessage("This downloads an open AI model (Qwen 2.5, ~550 MB) so IRIS can chat with real AI, fully offline. No account needed.\n\nUse WiFi if you can. This is a one-time download.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download", (d, w) -> {
                        brainButton.setEnabled(false);
                        brainStatus.setText("\u2B07\uFE0F Starting download…");
                        ModelManager.downloadGemma(this, new ModelManager.LlmDownloadListener() {
                            @Override public void onProgress(int percent, long done, long total) {
                                String mb = total > 0
                                        ? (done / 1048576) + " / " + (total / 1048576) + " MB"
                                        : (done / 1048576) + " MB";
                                brainStatus.setText("\u2B07\uFE0F Downloading AI brain: "
                                        + (percent >= 0 ? percent + "% • " : "") + mb);
                            }
                            @Override public void onComplete(java.io.File model) {
                                brainStatus.setText("\uD83E\uDDE0 AI brain installed \u2705 — restart IRIS listening to activate");
                                brainButton.setText("\uD83E\uDDE0 Re-download AI brain");
                                brainButton.setEnabled(true);
                                toast("\u2705 AI brain ready! Restart IRIS to use it.");
                                LogStore.append(MainActivity.this, "LLM", "Gemma model downloaded");
                            }
                            @Override public void onError(String message) {
                                brainStatus.setText("\u274C Download failed: " + message);
                                brainButton.setEnabled(true);
                                toast("Download failed: " + message);
                            }
                        });
                    }).show();
        });

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
        Button[] tabs = {tabAssistant, tabTraining, tabLogs, tabMemory, tabSettings};
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
        addIfMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        if (missing.isEmpty()) startListeningService();
        else new AlertDialog.Builder(this).setTitle("IRIS needs a few doors opened")
                .setMessage("Microphone listens; Contacts resolves names; Phone places confirmed calls; Location powers weather; Bluetooth chooses a headset; Notifications keep listening visible.")
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
        if (timedRecorder != null) timedRecorder.stop();
        if (wakeSampleIndex >= 5) return;
        int step = wakeSampleIndex + 1;
        // Update wizard dots
        if (wakeWizardDots != null) {
            StringBuilder dots = new StringBuilder();
            for (int d = 1; d <= 5; d++) dots.append(d <= step ? "\u25CF" : "\u25CB").append(d < 5 ? " " : "");
            wakeWizardDots.setText(dots.toString());
        }
        if (wakeWizardStep != null) wakeWizardStep.setText("Step " + step + " of 5");

        // Countdown: 3... 2... 1... BEEP + record
        if (wakeWizardPrompt != null) wakeWizardPrompt.setText("Get ready to say \u201C" + wakePhraseBeingTrained + "\u201D...");
        if (wakeWizardFeedback != null) wakeWizardFeedback.setText("3...");
        handler.postDelayed(() -> {
            if (wakeWizardFeedback != null) wakeWizardFeedback.setText("2...");
        }, 700);
        handler.postDelayed(() -> {
            if (wakeWizardFeedback != null) wakeWizardFeedback.setText("1...");
        }, 1400);
        handler.postDelayed(() -> {
            // BEEP
            try {
                android.media.ToneGenerator tone = new android.media.ToneGenerator(
                        android.media.AudioManager.STREAM_NOTIFICATION, 100);
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200);
                handler.postDelayed(tone::release, 400);
            } catch (Exception ignored) { }
            if (wakeWizardPrompt != null) wakeWizardPrompt.setText("\uD83D\uDD34  SAY \u201C" + wakePhraseBeingTrained + "\u201D NOW");
            if (wakeWizardFeedback != null) wakeWizardFeedback.setText("Recording 3 seconds...");
            wakeTrainingStatus.setText("\uD83C\uDF99 Recording sample " + step + "/5...");

            // Record exactly 3 seconds
            timedRecorder = new TimedRecorder();
            timedRecorder.record(3000, new TimedRecorder.Listener() {
                @Override
                public void onLevel(float normalizedLevel) {
                    // Update feedback with level bar
                    int bars = Math.round(normalizedLevel * 20);
                    StringBuilder bar = new StringBuilder();
                    for (int i = 0; i < 20; i++) bar.append(i < bars ? "\u2593" : "\u2591");
                    if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\uD83C\uDF99 " + bar.toString());
                }

                @Override
                public void onComplete(short[] audio) {
                    // Calculate audio level (RMS)
                    double rmsVal = 0;
                    for (short s : audio) rmsVal += (double) s * s;
                    rmsVal = Math.sqrt(rmsVal / Math.max(1, audio.length));

                    // REJECT unclear samples — require clear, loud-enough speech
                    if (rmsVal < 500) {
                        if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\u274C Too quiet \u2014 speak louder and clearer");
                        wakeTrainingStatus.setText("Rejected: too quiet. Retrying sample " + (wakeSampleIndex + 1) + "...");
                        toast("\u274C Too quiet \u2014 say it louder");
                        rejectTone();
                        handler.postDelayed(MainActivity.this::captureNextWakeSample, 1600);
                        return;
                    }

                    float[][] features = WakeWordEngine.extractFeatures(audio);
                    if (features.length < 15) {
                        if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\u274C Didn't catch it \u2014 say the whole phrase clearly");
                        wakeTrainingStatus.setText("Rejected: unclear. Retrying...");
                        toast("\u274C Unclear \u2014 say the full phrase");
                        rejectTone();
                        handler.postDelayed(MainActivity.this::captureNextWakeSample, 1600);
                        return;
                    }

                    float snr = (float) (rmsVal / 300.0);
                    String quality = snr >= 4 ? "Clear" : snr >= 2.5 ? "Usable" : "Quiet";
                    // REJECT Quiet quality — only accept Clear or Usable
                    if ("Quiet".equals(quality)) {
                        if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\u274C Not clear enough \u2014 try again");
                        wakeTrainingStatus.setText("Rejected: not clear. Retrying...");
                        toast("\u274C Not clear enough");
                        rejectTone();
                        handler.postDelayed(MainActivity.this::captureNextWakeSample, 1600);
                        return;
                    }

                    wakeTemplates.add(features);
                    wakeRawSamples.add(audio);
                    wakeSampleIndex++;
                    String icon = "Clear".equals(quality) ? "\u2705" : "\u26A0\uFE0F";
                    if (wakeWizardFeedback != null) wakeWizardFeedback.setText(icon + "  " + quality + " sample accepted!");
                    wakeTrainingStatus.setText(icon + " Sample " + wakeSampleIndex + "/5 done");
                    toast(icon + " Sample " + wakeSampleIndex + "/5 accepted!");
                    // Success chime
                    try {
                        android.media.ToneGenerator tone = new android.media.ToneGenerator(
                                android.media.AudioManager.STREAM_NOTIFICATION, 60);
                        tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 100);
                        handler.postDelayed(tone::release, 250);
                    } catch (Exception ignored) { }
                    if (wakeSampleIndex >= 5) handler.postDelayed(MainActivity.this::finishWakeTraining, 800);
                    else handler.postDelayed(MainActivity.this::captureNextWakeSample, 1200);
                }

                @Override
                public void onError(String message) {
                    if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\u274C " + message);
                    wakeTrainingStatus.setText("Error: " + message);
                    toast("\u274C " + message);
                    trainWakeButton.setText("Retry");
                    trainWakeButton.setEnabled(true);
                }
            });
        }, 2100); // Start recording after 3-2-1 countdown (700ms * 3)
    }

    /** Build a Vosk x-vector voiceprint from recorded samples on a background thread. */
    private void enrollVoiceprintAsync(java.util.List<short[]> samples) {
        if (samples == null || samples.isEmpty()) return;
        final VoskEngine ve = new VoskEngine();
        ve.init(this, new VoskEngine.InitListener() {
            @Override public void onReady() {
                ve.initSpeaker(MainActivity.this);
                new Thread(() -> {
                    long deadline = System.currentTimeMillis() + 10000;
                    while (!ve.isSpeakerReady() && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                    }
                    java.util.List<float[]> vecs = new java.util.ArrayList<>();
                    if (ve.isSpeakerReady()) {
                        for (short[] s : samples) {
                            float[] e = ve.embed(s);
                            if (e != null) vecs.add(e);
                        }
                    }
                    ve.close();
                    if (!vecs.isEmpty()) {
                        float[] avg = averageVectors(vecs);
                        new ProfileStore(MainActivity.this).setVoiceprint(avg);
                        LogStore.append(MainActivity.this, "VOICE", "Enrolled voiceprint from " + vecs.size() + " samples");
                        handler.post(() -> toast("\uD83D\uDD10 Voice enrolled for wake security \u2705"));
                    } else {
                        LogStore.append(MainActivity.this, "VOICE", "Enrollment failed (speaker model unavailable)");
                        handler.post(() -> toast("Voice security not set — speaker model unavailable."));
                    }
                }, "IRIS-Enroll").start();
            }
            @Override public void onError(String message) { ve.close(); }
        });
    }

    private static float[] averageVectors(java.util.List<float[]> vs) {
        int n = vs.get(0).length;
        float[] a = new float[n];
        for (float[] v : vs) for (int i = 0; i < n && i < v.length; i++) a[i] += v[i];
        for (int i = 0; i < n; i++) a[i] /= vs.size();
        return a;
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
        // Enroll speaker voiceprint from the recorded samples using Vosk x-vectors.
        String enrollStatus = "voice enrolling in background\u2026";
        if (!wakeRawSamples.isEmpty()) {
            enrollVoiceprintAsync(new java.util.ArrayList<>(wakeRawSamples));
        } else {
            enrollStatus = "voice not enrolled (no samples)";
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
        toast("\u2705 Wake phrase trained! Say \u201C" + saved.phrase + "\u201D to wake IRIS.");
        // Success melody
        try {
            android.media.ToneGenerator tone = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_NOTIFICATION, 80);
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200);
            handler.postDelayed(() -> {
                tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200);
                handler.postDelayed(tone::release, 400);
            }, 250);
        } catch (Exception ignored) { }
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
            @Override public void onSample(float[][] features, String quality, float signalToNoise, short[] rawAudio) { }
            @Override public void onWakeDetected(double distance, short[] rawAudio) {
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
        if (timedRecorder != null) { timedRecorder.stop(); timedRecorder = null; }
        handler.removeCallbacksAndMessages(null); // Cancel countdown
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
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(22), dp(4), dp(22), 0);

        EditText alias = new EditText(this);
        alias.setHint("Add phrase or nickname, e.g. Ring home");
        alias.setSingleLine(true);
        alias.setTextColor(getColor(R.color.text_primary));
        alias.setHintTextColor(getColor(R.color.text_muted));
        wrapper.addView(alias, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Relationship label spinner
        TextView labelHeader = new TextView(this);
        labelHeader.setText("\uD83C\uDFF7\uFE0F  Set relationship label");
        labelHeader.setTextColor(getColor(R.color.cyan));
        labelHeader.setTextSize(12);
        labelHeader.setPadding(0, dp(14), 0, dp(4));
        wrapper.addView(labelHeader);

        String[] labels = {"None", "Wife", "Husband", "Mom", "Dad", "Brother", "Sister",
                "Boss", "Doctor", "Office", "Friend", "Partner", "Son", "Daughter"};
        Spinner labelSpinner = new Spinner(this);
        ArrayAdapter<String> labelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        labelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        labelSpinner.setAdapter(labelAdapter);

        // Check if this contact already has a label
        java.util.Map<String, String[]> relationships = new ProfileStore(this).getRelationships();
        String currentLabel = "None";
        for (java.util.Map.Entry<String, String[]> rel : relationships.entrySet()) {
            if (rel.getValue()[1].replaceAll("[^0-9+]", "").equals(
                    entry.phoneNumber.replaceAll("[^0-9+]", ""))) {
                currentLabel = rel.getKey().substring(0, 1).toUpperCase() + rel.getKey().substring(1);
                break;
            }
        }
        int labelIndex = java.util.Arrays.asList(labels).indexOf(currentLabel);
        labelSpinner.setSelection(Math.max(0, labelIndex), false);
        wrapper.addView(labelSpinner);

        new AlertDialog.Builder(this).setTitle(entry.contactName)
                .setMessage(entry.phoneNumber + "\n\nLearned:\n" +
                        (entry.phrases.isEmpty() ? "No custom phrases yet" : String.join("\n", entry.phrases)))
                .setView(wrapper)
                .setNegativeButton("Close", null)
                .setNeutralButton("Save label", (dialog, which) -> {
                    String selectedLabel = labels[labelSpinner.getSelectedItemPosition()];
                    ProfileStore store = new ProfileStore(this);
                    // Remove any existing label for this number
                    for (java.util.Map.Entry<String, String[]> rel : store.getRelationships().entrySet()) {
                        if (rel.getValue()[1].replaceAll("[^0-9+]", "").equals(
                                entry.phoneNumber.replaceAll("[^0-9+]", ""))) {
                            store.removeRelationship(rel.getKey());
                        }
                    }
                    if (!"None".equals(selectedLabel)) {
                        store.setRelationship(selectedLabel, entry.contactName, entry.phoneNumber);
                        toast("\uD83C\uDFF7\uFE0F  " + entry.contactName + " is now your " + selectedLabel.toLowerCase());
                    } else {
                        toast("Label removed.");
                    }
                })
                .setPositiveButton("Add phrase", (dialog, which) -> {
                    String phrase = alias.getText().toString().trim();
                    if (!phrase.isEmpty()) {
                        new ProfileStore(this).addTraining(entry.contactName, entry.phoneNumber,
                                java.util.Collections.singletonList(phrase));
                        toast("Added \u201C" + phrase + "\u201D.");
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

    private android.speech.tts.TextToSpeech testTts;

    /** Speak a short sample so the user can hear IRIS's voice. */
    private void testVoice() {
        if (testTts != null) { speakVoiceSample(); return; }
        toast("Preparing voice\u2026");
        testTts = new android.speech.tts.TextToSpeech(this, status -> {
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                try { testTts.setLanguage(new Locale("en", "IN")); } catch (Exception ignored) { }
                speakVoiceSample();
            } else {
                runOnUiThread(() -> toast("Text-to-speech isn't available on this phone."));
            }
        });
    }

    private void speakVoiceSample() {
        if (testTts == null) return;
        testTts.speak("Hello, I'm IRIS, your assistant. This is how I sound.",
                android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "iris_voice_test");
    }

    /** Report the status of IRIS's own offline speech model (Vosk, Indian English). */
    private void showOfflineSpeechStatus() {
        boolean bundled = false;
        try {
            String[] f = getAssets().list("model-en-us");
            bundled = f != null && f.length > 0;
        } catch (Exception ignored) { }
        java.io.File extracted = new java.io.File(getFilesDir(), "vosk-model-en-in-0.4");
        boolean downloaded = extracted.exists() && extracted.list() != null && extracted.list().length > 0;
        boolean ready = bundled || downloaded;
        String status = ready
                ? "\u2705 Ready. IRIS uses its own offline Indian-English speech model "
                  + (bundled ? "(built into the app)." : "(downloaded).")
                : "\u2b07\uFE0F Not yet downloaded. It downloads automatically the first time IRIS listens, then works fully offline.";
        new AlertDialog.Builder(this)
                .setTitle("Offline speech model")
                .setMessage(status + "\n\nEverything runs on-device \u2014 no internet needed for recognition. There's nothing you need to do here.")
                .setPositiveButton("OK", null)
                .show();
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
            } else if (requestCode == EXPORT_MEMORY) {
                writeText(uri, MemoryStore.exportJson(this));
                toast("IRIS memory exported.");
            } else if (requestCode == IMPORT_MEMORY) {
                int count = MemoryStore.importAndMerge(this, readText(uri));
                toast("Imported " + count + " memories.");
                LogStore.append(this, "MEMORY IMPORT", count + " memories merged");
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

    private void rejectTone() {
        try {
            android.media.ToneGenerator tone = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_NOTIFICATION, 90);
            tone.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 250);
            handler.postDelayed(tone::release, 400);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyTrainingRecognizer();
        destroyDryRunRecognizer();
        if (testTts != null) { testTts.stop(); testTts.shutdown(); testTts = null; }
        if (timedRecorder != null) { timedRecorder.stop(); timedRecorder = null; }
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
