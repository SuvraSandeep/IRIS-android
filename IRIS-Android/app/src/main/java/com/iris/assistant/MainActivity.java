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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ScrollView;
import android.widget.SeekBar;
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

    // Voice & command training
    private View voiceTrainNormalState;
    private View voiceTrainWizardState;
    private TextView voiceTrainStatus;
    private TextView voiceTrainStep;
    private TextView voiceTrainPrompt;
    private TextView voiceTrainFeedback;
    private Button voiceTrainCancel;
    private Button startVoiceTrainButton;
    private VoskEngine trainVosk;
    private final List<short[]> voiceReadSamples = new ArrayList<>();
    private int trainPhraseIdx;
    private int trainCmdIdx;
    private int learnedAliasCount;
    private boolean voiceTrainCancelled;
    private static final String[] TRAIN_PHRASES = {
            "The quick brown fox jumps over the lazy dog by the river",
            "I would like to call my family and check the weather today",
            "Please set an alarm and remind me about the meeting tomorrow"
    };
    private static final String[] TRAIN_COMMANDS = {
            "call", "text", "message", "whatsapp", "email", "alarm", "timer",
            "reminder", "weather", "battery", "flashlight", "volume",
            "notifications", "location", "open", "search", "time", "stop", "cancel"
    };

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
    private String lastTranscriptSeen = "";
    // ── Command Deck ──
    private SystemTelemetryController telemetry;
    private final java.util.Map<String, View> deckCells = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> deckSeen = new java.util.HashSet<>();
    private String deckRenderedTab = "";
    private String deckSearch = "";
    private int deckCellPosition;
    private TelemetrySnapshot displayedPhoneFacts = TelemetrySnapshot.empty();

    private void placeDeckCell(String key, View cell) {
        deckSeen.add(key); deckCells.put(key, cell);
        int current = deckBody.indexOfChild(cell);
        if (current != deckCellPosition) {
            if (current >= 0) deckBody.removeView(cell);
            deckBody.addView(cell, Math.min(deckCellPosition, deckBody.getChildCount()));
        }
        deckCellPosition++;
    }
    private void finishDeckCells() {
        java.util.Iterator<java.util.Map.Entry<String,View>> it=deckCells.entrySet().iterator();
        while(it.hasNext()){java.util.Map.Entry<String,View> e=it.next();if(!deckSeen.contains(e.getKey())){deckBody.removeView(e.getValue());it.remove();}}
    }
    private void phoneFactRow(PhoneFacts.Field f, TelemetrySnapshot snap) {
        TelemetrySnapshot.Metric metric=snap.get(f.key);
        expandableRow(f.label,metric.display(), "Source: " + metric.source + "\nState: " + metric.availability
                + "\nAsk IRIS: What is my " + f.label.toLowerCase(java.util.Locale.ROOT) + "?"
                + "\nLong-press this row to copy its current value.");
        View v=deckCells.get("expand:"+f.label);
        if(v!=null)v.setOnLongClickListener(x->{copyToClipboard(f.label+": "+metric.display(),"Phone detail copied");return true;});
    }
    private LinearLayout deckBody;
    private TelemetrySparklineView deckSparkline;
    private TextView deckActivity, deckFreshness, deckServiceState;
    private TextView deckTileBattery, deckTileRam, deckTileNetwork, deckTileDevices;
    private final java.util.Map<String, TextView> deckTabViews = new java.util.LinkedHashMap<>();
    private String deckTab = "overview";
    private boolean deckActivityPaused;
    private TelemetryEventLog.Category deckFilter;
    private final java.util.Map<String, TextView> deckFilterViews = new java.util.LinkedHashMap<>();
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
                if (text != null && !text.trim().isEmpty()) lastTranscriptSeen = text.trim();
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
        showTabByIndex(getSharedPreferences("iris_ui", MODE_PRIVATE).getInt("last_tab", 0));
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
        if (selectedTab == 0 && telemetry != null) telemetry.start();
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
        // No dashboard polling or animation while hidden (voice service is unaffected).
        try { if (telemetry != null) telemetry.stop(); } catch (Exception ignored) { }
        try { getSharedPreferences("iris_ui", MODE_PRIVATE).edit().putInt("last_tab", selectedTab).apply(); } catch (Exception ignored) { }
        super.onStop();
    }

    private void showTabByIndex(int i) {
        switch (i) {
            case 1: showTraining(); break;
            case 2: showLogs(); break;
            case 3: showMemory(); break;
            case 4: showSettings(); break;
            default: showAssistant();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    /** Bind a deck toggle; changes apply next time Home is shown. */
    private void bindDeckSwitch(View root, int id, boolean initial, java.util.function.Consumer<Boolean> setter) {
        Switch sw = root.findViewById(id);
        if (sw == null) return;
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, checked) -> {
            setter.accept(checked);
            toast("Saved. Open Home to see it.");
        });
    }

    /** Bind the Command Deck telemetry to the freshly-inflated assistant view. */
    private void setupCommandDeck(View view) {
        deckCells.clear(); deckRenderedTab=""; deckSearch=""; deckTabViews.clear();
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { if(telemetry!=null)telemetry.stop(); }
        });
        AppSettings s = new AppSettings(this);
        // Orb size is user-customisable (spec suggests 190–210dp).
        try {
            View orb = view.findViewById(R.id.irisOrb);
            if (orb != null) {
                int px = Math.round(s.deckOrbSize() * getResources().getDisplayMetrics().density);
                orb.getLayoutParams().width = px;
                orb.getLayoutParams().height = px;
                orb.requestLayout();
                if (orb instanceof IrisOrbView) {
                    // Battery-saving visuals and Reduce motion both stop continuous animation.
                    ((IrisOrbView) orb).setStaticMode(s.deckBatterySaver());
                    ((IrisOrbView) orb).setReduceMotion(s.reduceMotion());
                }
            }
        } catch (Throwable ignored) { }

        // Every section can be turned off.
        toggleVisible(view, R.id.telemetrySection, s.deckTelemetry());
        toggleVisible(view, R.id.activitySection, s.deckActivityStream());
        toggleVisible(view, R.id.deckTiles, s.deckTiles());
        toggleVisible(view, R.id.deckTiles2, s.deckTiles());

        deckBody = view.findViewById(R.id.telemetryBody);
        EditText detailSearch=view.findViewById(R.id.phoneDetailSearch);
        if(detailSearch!=null)detailSearch.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence t,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence t,int start,int before,int count){deckSearch=t.toString().trim();if(telemetry!=null)renderDeckPanel(telemetry.latest());}
            @Override public void afterTextChanged(android.text.Editable e){}
        });
        TextView ask=view.findViewById(R.id.phoneDetailHelp);
        if(ask!=null)ask.setOnClickListener(v->{
            String[] examples={"What is my Wi-Fi IP?","What is my battery health?","What is my screen refresh rate?","Which sensors are being used?","What devices are connected to Bluetooth?","What data is being transmitted?","What is my security patch?"};
            new AlertDialog.Builder(this).setTitle("Ask about this phone").setItems(examples,(dialog,which)->sendTextCommand(examples[which])).setNegativeButton("Close",null).show();
        });
        deckSparkline = view.findViewById(R.id.trafficSparkline);
        deckActivity = view.findViewById(R.id.activityStream);
        deckFreshness = view.findViewById(R.id.deckFreshness);
        deckServiceState = view.findViewById(R.id.deckServiceState);
        deckTileBattery = view.findViewById(R.id.tileBattery);
        deckTileRam = view.findViewById(R.id.tileRam);
        deckTileNetwork = view.findViewById(R.id.tileNetwork);
        deckTileDevices = view.findViewById(R.id.tileDevices);
        if (deckSparkline != null) deckSparkline.setAccent(getColor(R.color.accent));

        deckTab = s.deckDefaultTab();
        int[] tabIds = { R.id.tabOverview, R.id.tabNetwork, R.id.tabDevices, R.id.tabSensors, R.id.tabResources };
        final String[] tabKeys = { "overview", "network", "devices", "sensors", "resources" };
        for (int i = 0; i < tabIds.length; i++) {
            final String key = tabKeys[i];
            TextView t = view.findViewById(tabIds[i]);
            if (t == null) continue;
            deckTabViews.put(key, t);
            t.setOnClickListener(v -> {
                deckTab = key;
                new AppSettings(this).setDeckDefaultTab(key);
                highlightDeckTab();
                if (telemetry != null) telemetry.refreshNow();
                renderDeck(telemetry == null ? TelemetrySnapshot.empty() : telemetry.latest());
            });
        }
        highlightDeckTab();

        TextView pause = view.findViewById(R.id.activityPause);
        if (pause != null) pause.setOnClickListener(v -> {
            deckActivityPaused = !deckActivityPaused;
            pause.setText(deckActivityPaused ? "RESUME" : "PAUSE");
            pause.setTextColor(getColor(deckActivityPaused ? R.color.warning : R.color.deck_text_dim));
        });
        TextView clear = view.findViewById(R.id.activityClear);
        if (clear != null) clear.setOnClickListener(v -> {
            if (telemetry != null) telemetry.events().clear();
            if (deckActivity != null) deckActivity.setText("No events yet.");
        });

        // Category filters (§5). ALL plus the categories that actually produce events.
        LinearLayout filters = view.findViewById(R.id.activityFilters);
        if (filters != null) {
            filters.removeAllViews();
            deckFilterViews.clear();
            addFilterChip(filters, "ALL", null);
            addFilterChip(filters, "NET", TelemetryEventLog.Category.NET);
            addFilterChip(filters, "AUDIO", TelemetryEventLog.Category.AUDIO);
            addFilterChip(filters, "BT", TelemetryEventLog.Category.BT);
            addFilterChip(filters, "WAKE", TelemetryEventLog.Category.WAKE);
            addFilterChip(filters, "SENSOR", TelemetryEventLog.Category.SENSOR);
            addFilterChip(filters, "ACTION", TelemetryEventLog.Category.ACTION);
            highlightFilters();
        }
        // Tap the console to expand the newest event's explanation.
        if (deckActivity != null) deckActivity.setOnClickListener(v -> {
            if (telemetry == null) return;
            java.util.List<TelemetryEventLog.Event> recent =
                    telemetry.events().recent(1, deckFilter);
            if (recent.isEmpty()) return;
            TelemetryEventLog.Event e = recent.get(0);
            new AlertDialog.Builder(this)
                    .setTitle(e.category.tag + " · " + e.stamp())
                    .setMessage(e.message + (e.detail.isEmpty() ? "" : "\n\n" + e.detail))
                    .setPositiveButton("Close", null).show();
        });

        // Collapse the orb into a header dot when scrolled away from the top.
        final View orbHolder = view.findViewById(R.id.orbHolder);
        final View headerDot = view.findViewById(R.id.headerOrbDot);
        final ScrollView scroll = view.findViewById(R.id.deckScroll);
        if (scroll != null && orbHolder != null && headerDot != null) {
            scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
                boolean collapsed = scroll.getScrollY() > orbHolder.getHeight() / 2;
                orbHolder.setAlpha(collapsed ? 0f : 1f);
                headerDot.setVisibility(collapsed ? View.VISIBLE : View.GONE);
            });
        }

        if (telemetry == null) telemetry = new SystemTelemetryController(this);
        telemetry.setListener(this::renderDeck);
        telemetry.start();
        renderDeck(telemetry.latest());
    }

    private void toggleVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void highlightDeckTab() {
        for (java.util.Map.Entry<String, TextView> e : deckTabViews.entrySet()) {
            boolean on = e.getKey().equals(deckTab);
            e.getValue().setTextColor(getColor(on ? R.color.accent : R.color.deck_text_dim));
            e.getValue().setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** Paint the current snapshot. Availability is respected: nothing is invented. */
    private void renderDeck(TelemetrySnapshot snap) {
        if (snap == null || selectedTab != 0) return;
        displayedPhoneFacts = snap;
        long now = android.os.SystemClock.elapsedRealtime();
        if (deckFreshness != null) deckFreshness.setText(snap.freshness(now));
        if (deckServiceState != null) {
            boolean up = IrisListeningService.isRunning;
            deckServiceState.setText(up ? "ACTIVE" : "OFFLINE");
            deckServiceState.setTextColor(getColor(up ? R.color.positive : R.color.deck_text_dim));
        }
        if (deckTileBattery != null) deckTileBattery.setText(snap.display(ResourceTelemetryCollector.K_BATTERY));
        if (deckTileRam != null) deckTileRam.setText(snap.display(ResourceTelemetryCollector.K_RAM_FREE));
        if (deckTileNetwork != null) deckTileNetwork.setText(snap.display(NetworkTelemetryCollector.K_TRANSPORT));
        if (deckTileDevices != null) deckTileDevices.setText(snap.display(BluetoothTelemetryCollector.K_BT_SUMMARY));

        if (deckSparkline != null && telemetry != null) {
            deckSparkline.update(telemetry.networkCollector().rxMeter());
        }
        if (deckActivity != null && telemetry != null && !deckActivityPaused) {
            deckActivity.setText(telemetry.events().render(12, deckFilter));
        }
        if (deckBody != null) renderDeckPanel(snap);
    }

    /** Build the rows for the selected telemetry tab. */
    private void renderDeckPanel(TelemetrySnapshot snap) {
        if (deckBody == null) return;
        deckSeen.clear(); deckCellPosition=0;
        if (!deckTab.equals(deckRenderedTab)) { deckBody.removeAllViews(); deckCells.clear(); deckRenderedTab=deckTab; }
        if (!deckSearch.isEmpty()) {
            java.util.List<PhoneFacts.Field> matches=PhoneFacts.search(deckSearch);
            if(matches.isEmpty())note("No matching phone detail. Try IP, battery, memory, display or sensors.");
            for(PhoneFacts.Field field:matches)phoneFactRow(field,snap);
            finishDeckCells(); return;
        }
        switch (deckTab) {
            case "network":
                row("Active transport", snap.display(NetworkTelemetryCollector.K_TRANSPORT));
                row("Internet", snap.display(NetworkTelemetryCollector.K_INTERNET));
                row("Wi-Fi name", snap.display(NetworkTelemetryCollector.K_WIFI_NAME));
                row("Signal", snap.display(NetworkTelemetryCollector.K_WIFI_SIGNAL));
                row("Frequency", snap.display(NetworkTelemetryCollector.K_WIFI_FREQ));
                row("Wi-Fi link speed", snap.display(NetworkTelemetryCollector.K_WIFI_LINK));
                row("Phone IP (Wi-Fi)", snap.display(NetworkTelemetryCollector.K_PHONE_IP));
                row("Phone IPv6", snap.display(NetworkTelemetryCollector.K_PHONE_IP6));
                row("Cellular interface IP", snap.display(NetworkTelemetryCollector.K_CELL_IP));
                row("Router/gateway", snap.display(NetworkTelemetryCollector.K_GATEWAY));
                row("DNS", snap.display(NetworkTelemetryCollector.K_DNS));
                row("VPN", snap.display(NetworkTelemetryCollector.K_VPN));
                row("Connection cost", snap.display(NetworkTelemetryCollector.K_METERED));
                row("Public IP", snap.display(NetworkTelemetryCollector.K_PUBLIC_IP));
                row("Last network change", snap.display(NetworkTelemetryCollector.K_LAST_CHANGE));
                row("Default interface",snap.display("net_interface"));
                row("MTU",snap.display("net_mtu"));
                row("Private DNS",snap.display("net_private_dns"));
                row("Device traffic since boot",snap.display("device_total_traffic"));
                note("When a VPN is active, default-interface addresses belong to its tunnel and are not labelled as Wi-Fi addresses.");
                break;
            case "devices":
                row("Bluetooth", snap.display(BluetoothTelemetryCollector.K_BT_STATE));
                row("Available audio outputs", snap.display(BluetoothTelemetryCollector.K_AUDIO_OUT));
                row("Microphone route", snap.display(BluetoothTelemetryCollector.K_AUDIO_IN));
                if (telemetry != null) {
                    java.util.List<BluetoothTelemetryCollector.DeviceRow> devices =
                            telemetry.bluetoothCollector().devices();
                    if (devices.isEmpty()) {
                        row("Connected devices", snap.get(BluetoothTelemetryCollector.K_BT_STATE).isAvailable()
                                ? "No connected devices visible to IRIS" : snap.display(BluetoothTelemetryCollector.K_BT_STATE));
                    }
                    for (BluetoothTelemetryCollector.DeviceRow d : devices) {
                        // Tap a device to expand its detail (§4).
                        expandableRow(d.name, d.connection,
                                "Category: " + d.category
                                + "\nState: " + d.connection + " · " + d.detail
                                + "\nActive audio route: not established by endpoint visibility"
                                + (d.battery.isEmpty() ? "\nBattery: not exposed by this device"
                                                       : "\nBattery: " + d.battery));
                    }
                }
                note("Observed GATT connections and available audio endpoints are listed. Hidden addresses "
                        + "can prevent correlating endpoints with physical devices. Some connections are "
                        + "not visible to IRIS; availability does not establish active playback.");
                break;
            case "sensors":
                groupHeader("HARDWARE AVAILABLE");
                for (IrisSensorUsageRegistry.Hardware hw : IrisSensorUsageRegistry.Hardware.values()) {
                    String present = IrisSensorUsageRegistry.availability(this, hw);
                    row(IrisSensorUsageRegistry.label(hw),
                            "present".equals(present) ? "Present"
                                    : "absent".equals(present) ? "Not present" : "Unknown");
                }
                groupHeader("CURRENTLY USED BY IRIS");
                boolean anyActive = false;
                for (IrisSensorUsageRegistry.Hardware hw : IrisSensorUsageRegistry.Hardware.values()) {
                    if (IrisSensorUsageRegistry.isActive(hw)) {
                        anyActive = true;
                        IrisSensorUsageRegistry.Usage u = IrisSensorUsageRegistry.usage(hw);
                        String extra = u == null ? "" : " · " + TelemetrySnapshot.duration(
                                android.os.SystemClock.elapsedRealtime() - u.sinceElapsed);
                        row(IrisSensorUsageRegistry.label(hw),
                                IrisSensorUsageRegistry.status(hw) + extra);
                    }
                }
                if (!anyActive) row("Nothing in use", "IRIS is not sampling any sensor");
                for(PhoneFacts.Field field:PhoneFacts.group("sensors"))phoneFactRow(field,snap);
                note("Only IRIS's own usage is shown. Android does not expose what other apps are "
                        + "doing with sensors, and nothing here is switched on just to animate.");
                break;
            case "resources":
                row("Battery", snap.display(ResourceTelemetryCollector.K_BATTERY));
                row("Power", snap.display(ResourceTelemetryCollector.K_CHARGING));
                row("Battery saver", snap.display(ResourceTelemetryCollector.K_POWER_SAVE));
                row("Battery temperature", snap.display(ResourceTelemetryCollector.K_BATTERY_TEMP));
                row("Thermal status", snap.display(ResourceTelemetryCollector.K_THERMAL));
                row("Free RAM", snap.display(ResourceTelemetryCollector.K_RAM_FREE));
                row("IRIS Java heap", snap.display(ResourceTelemetryCollector.K_RAM_IRIS));
                row("Free storage", snap.display(ResourceTelemetryCollector.K_STORAGE_FREE));
                row("Device", snap.display(ResourceTelemetryCollector.K_DEVICE));
                row("System", snap.display(ResourceTelemetryCollector.K_ANDROID));
                row("App", snap.display(ResourceTelemetryCollector.K_APP_VERSION));
                row("Device uptime", snap.display(ResourceTelemetryCollector.K_DEVICE_UPTIME));
                row("IRIS uptime", snap.display(ResourceTelemetryCollector.K_SERVICE_UPTIME));
                row("Wake phrase", snap.display("wake_phrase"));
                row("Wake model", snap.display("wake_ready"));
                row("Owner check", snap.display("owner_check"));
                groupHeader("MORE PHONE DETAILS · TAP TO EXPLAIN");
                for(PhoneFacts.Field field:PhoneFacts.FIELDS)
                    if(field.group.equals("display") || field.group.equals("system") || field.group.equals("audio")
                        || field.key.equals("battery_health") || field.key.equals("battery_voltage") || field.key.equals("battery_technology")
                        || field.key.equals("ram_total") || field.key.equals("ram_used") || field.key.equals("storage_total") || field.key.equals("storage_used"))
                        phoneFactRow(field,snap);
                note("Battery temperature is the battery, not the CPU. Android exposes no public "
                        + "CPU temperature, so none is shown.");
                break;
            default:
                row("Connection", snap.display(NetworkTelemetryCollector.K_TRANSPORT)
                        + " · " + snap.display(NetworkTelemetryCollector.K_INTERNET));
                row("Phone IP (Wi-Fi)", snap.display(NetworkTelemetryCollector.K_PHONE_IP));
                row("IRIS traffic", "\u2193 " + snap.display(NetworkTelemetryCollector.K_RX_RATE)
                        + "   \u2191 " + snap.display(NetworkTelemetryCollector.K_TX_RATE));
                row("Session traffic", snap.display(NetworkTelemetryCollector.K_SESSION));
                row("Microphone", snap.display("iris_mic"));
                row("Service", snap.display("iris_service"));
                break;
        }
        finishDeckCells();
    }

    /** Add one filter chip to the activity stream. */
    private void addFilterChip(LinearLayout parent, String label, TelemetryEventLog.Category cat) {
        float d = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(9f);
        chip.setTypeface(Typeface.MONOSPACE);
        chip.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
        chip.setMinHeight((int) (48 * d));
        chip.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * d);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            deckFilter = cat;
            highlightFilters();
            if (telemetry != null && deckActivity != null) {
                deckActivity.setText(telemetry.events().render(12, deckFilter));
            }
        });
        deckFilterViews.put(label, chip);
        parent.addView(chip);
    }

    private void highlightFilters() {
        String active = deckFilter == null ? "ALL" : deckFilter.tag;
        for (java.util.Map.Entry<String, TextView> e : deckFilterViews.entrySet()) {
            boolean on = e.getKey().equals(active);
            e.getValue().setTextColor(getColor(on ? R.color.accent : R.color.deck_text_dim));
            e.getValue().setBackgroundResource(on ? R.drawable.bg_deck_card : 0);
        }
    }

    /** A small group label inside a panel (e.g. HARDWARE AVAILABLE). */
    private void groupHeader(String text) {
        View existing=deckCells.get("group:"+text);
        if(existing!=null){placeDeckCell("group:"+text,existing);return;}
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.accent));
        t.setTextSize(9.5f);
        t.setLetterSpacing(0.1f);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, (int) (10 * d), 0, (int) (2 * d));
        placeDeckCell("group:"+text,t);
    }

    /** A row that reveals extra detail when tapped, with a short fade (spec §4/§6). */
    private void expandableRow(String label, String value, String detail) {
        LinearLayout old=(LinearLayout)deckCells.get("expand:"+label);
        if(old!=null){
            TextView val=(TextView)((LinearLayout)old.getChildAt(0)).getChildAt(1);
            if(!val.getText().toString().equals(value))val.setText(value);
            ((TextView)old.getChildAt(1)).setText(detail);
            placeDeckCell("expand:"+label,old);return;
        }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setMinimumHeight((int) (48 * d));       // touch target
        holder.setPadding(0, (int) (4 * d), 0, (int) (4 * d));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = new TextView(this);
        l.setText("▸  " + label);
        l.setTextColor(getColor(R.color.deck_text));
        l.setTextSize(13f);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value == null ? "" : value);
        v.setTextColor(getColor("Connected".equals(value) ? R.color.positive : R.color.deck_text_dim));
        v.setTextSize(13f);
        v.setTypeface(Typeface.MONOSPACE);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
        head.addView(l);
        head.addView(v);
        holder.addView(head);

        final TextView body = new TextView(this);
        body.setText(detail == null ? "" : detail);
        body.setTextColor(getColor(R.color.deck_text_dim));
        body.setTextSize(10.5f);
        body.setPadding((int) (14 * d), (int) (4 * d), 0, 0);
        body.setVisibility(View.GONE);
        holder.addView(body);

        holder.setOnClickListener(x -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setAlpha(0f);
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && !new AppSettings(this).reduceMotion()) body.animate().alpha(1f).setDuration(200).start();
            else body.setAlpha(1f);
            l.setText((show ? "▾  " : "▸  ") + label);
        });
        placeDeckCell("expand:"+label,holder);
    }

    /** One label/value row, dimmed when the value isn't a real reading. */
    private void row(String label, String value) {
        LinearLayout old=(LinearLayout)deckCells.get("row:"+label);
        if(old!=null){TextView v=(TextView)old.getChildAt(1);if(!v.getText().toString().equals(value))v.setText(value);placeDeckCell("row:"+label,old);return;}
        float d = getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, (int) (5 * d), 0, (int) (5 * d));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.deck_text_dim));
        l.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        l.setLayoutParams(lp);
        TextView v = new TextView(this);
        v.setText(value == null ? "" : value);
        boolean unknown = value == null || value.isEmpty()
                || value.startsWith("Not available") || value.startsWith("Permission required")
                || value.contains("(stale)");
        v.setTextColor(getColor(unknown ? R.color.deck_inactive : R.color.deck_text));
        v.setTextSize(13f);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setGravity(android.view.Gravity.END);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));
        r.addView(l);
        r.addView(v);
        placeDeckCell("row:"+label,r);
    }

    /** Small explanatory footnote under a panel. */
    private void note(String text) {
        View existing=deckCells.get("note:"+text);
        if(existing!=null){placeDeckCell("note:"+text,existing);return;}
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.deck_text_dim));
        t.setTextSize(10f);
        t.setPadding(0, (int) (8 * d), 0, 0);
        placeDeckCell("note:"+text,t);
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
        setupCommandDeck(view);
        // Tap the recognized text to copy it.
        if (liveTranscript != null) {
            liveTranscript.setTextIsSelectable(true);
            liveTranscript.setOnClickListener(v -> copyToClipboard(liveTranscript.getText().toString(), "Copied \u2713"));
        }
        if (recognitionText != null) {
            recognitionText.setTextIsSelectable(true);
            recognitionText.setOnClickListener(v -> copyToClipboard(recognitionText.getText().toString(), "Copied \u2713"));
        }
        if (subStatusText != null) {
            subStatusText.setOnClickListener(v -> copyToClipboard(subStatusText.getText().toString(), "Reply copied \u2713"));
        }
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
        // "Feature guide" chip — lists every feature with how-to
        Button guide = new Button(this);
        guide.setText("\uD83D\uDCD6 Guide");
        guide.setAllCaps(false);
        guide.setTextColor(getColorCompat(R.color.text_primary));
        guide.setTextSize(12f);
        guide.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        glp.rightMargin = dp(8);
        guide.setLayoutParams(glp);
        guide.setPadding(dp(16), 0, dp(16), 0);
        guide.setOnClickListener(v -> showFeatureGuide());
        row.addView(guide);

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

        // Voice & command training
        voiceTrainNormalState = view.findViewById(R.id.voiceTrainNormalState);
        voiceTrainWizardState = view.findViewById(R.id.voiceTrainWizardState);
        voiceTrainStatus = view.findViewById(R.id.voiceTrainStatus);
        voiceTrainStep = view.findViewById(R.id.voiceTrainStep);
        voiceTrainPrompt = view.findViewById(R.id.voiceTrainPrompt);
        voiceTrainFeedback = view.findViewById(R.id.voiceTrainFeedback);
        voiceTrainCancel = view.findViewById(R.id.voiceTrainCancel);
        startVoiceTrainButton = view.findViewById(R.id.startVoiceTrainButton);
        int aliasCount = new ProfileStore(this).commandAliasCount();
        boolean enrolled = new ProfileStore(this).getWakeProfile().isVoiceEnrolled();
        voiceTrainStatus.setText((enrolled ? "\u2705 Voice pattern learned" : "\u26A0\uFE0F Voice pattern not set")
                + " \u2022 " + aliasCount + " command pronunciation" + (aliasCount == 1 ? "" : "s"));
        startVoiceTrainButton.setText(enrolled || aliasCount > 0
                ? "\uD83D\uDD04  Retrain Voice & Commands" : "\uD83C\uDF93  Learn My Voice & Commands");
        startVoiceTrainButton.setOnClickListener(v ->
                authenticateThen("\uD83D\uDD12 Voice & command training", this::beginVoiceCommandTraining));
        voiceTrainCancel.setOnClickListener(v -> cancelVoiceCommandTraining());

        // Populate wake phrase state
        ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
        if (!wake.phrase.isEmpty()) wakePhraseInput.setText(wake.phrase);
        int savedProgress = TrainingProgress.peekIndex(this);
        if (savedProgress > 0) {
            wakeTrainingStatus.setText("\u23F8 Paused at " + savedProgress + "/5 \u2014 Resume or start over.");
            testWakeButton.setEnabled(wake.isReady());
        } else if (wake.isReady()) {
            String voiceStatus = wake.isVoiceEnrolled() ? "voice enrolled \u2705" : "voice not enrolled \u26A0\uFE0F";
            wakeTrainingStatus.setText("\u2705  \u201C" + wake.phrase + "\u201D \u2022 " + voiceStatus);
            testWakeButton.setEnabled(true);
        } else {
            wakeTrainingStatus.setText("\u26A0\uFE0F  Not configured yet");
            testWakeButton.setEnabled(false);
        }

        // Wire up buttons
        configureWakeButton();
        TextView micLabelTraining = view.findViewById(R.id.micLabelTraining);
        if (micLabelTraining != null) micLabelTraining.setText("\uD83C\uDF99 Listening mic: " + micLabel());
        testWakeButton.setOnClickListener(v -> testWakePhrase());
        wakeWizardCancel.setOnClickListener(v -> cancelWakeTraining());

        // Extra wake phrases (text-only alternates recognised by Vosk — no separate training).
        EditText altWakeInput = view.findViewById(R.id.altWakeInput);
        Button addAltWakeButton = view.findViewById(R.id.addAltWakeButton);
        TextView altWakeList = view.findViewById(R.id.altWakeList);
        renderAltWakeList(altWakeList);
        if (addAltWakeButton != null && altWakeInput != null) {
            addAltWakeButton.setOnClickListener(v -> {
                String p = altWakeInput.getText().toString().trim();
                if (p.length() < 2) { altWakeInput.setError("Enter a phrase"); return; }
                ProfileStore ps = new ProfileStore(this);
                java.util.List<String> alts = new java.util.ArrayList<>(ps.getWakeProfile().altPhrases);
                if (alts.size() >= 2) { toast("You can add up to 2 extra phrases."); return; }
                if (!alts.contains(p)) alts.add(p);
                ps.setAltWakePhrases(alts);
                altWakeInput.setText("");
                renderAltWakeList(altWakeList);
                toast("Added wake phrase: " + p);
                if (IrisListeningService.isRunning) { stopListeningService(); handler.postDelayed(this::startListeningService, 500); }
            });
        }
        if (altWakeList != null) {
            altWakeList.setOnClickListener(v -> {
                ProfileStore ps = new ProfileStore(this);
                if (ps.getWakeProfile().altPhrases.isEmpty()) return;
                new AlertDialog.Builder(this).setTitle("Clear extra wake phrases?")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Clear", (d, w) -> {
                            ps.setAltWakePhrases(new java.util.ArrayList<>());
                            renderAltWakeList(altWakeList);
                            toast("Extra phrases cleared.");
                            if (IrisListeningService.isRunning) { stopListeningService(); handler.postDelayed(this::startListeningService, 500); }
                        }).show();
            });
        }
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

    // ── Voice picker + self-test ──

    private static boolean isFemaleVoiceName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("female") || n.contains("-f-") || n.endsWith("-f") || n.contains("#female") || n.contains("_f_");
    }

    /** List the device's offline English voices and let the user pick one for IRIS. */
    private void showVoicePicker() {
        final AppSettings settings = new AppSettings(this);
        final android.speech.tts.TextToSpeech[] tts = new android.speech.tts.TextToSpeech[1];
        tts[0] = new android.speech.tts.TextToSpeech(this, status -> {
            if (status != android.speech.tts.TextToSpeech.SUCCESS) { runOnUiThread(() -> toast("TTS not available.")); return; }
            final java.util.List<android.speech.tts.Voice> voices = new ArrayList<>();
            try {
                for (android.speech.tts.Voice v : tts[0].getVoices()) {
                    if (v == null || v.getLocale() == null) continue;
                    if (!"en".equals(v.getLocale().getLanguage())) continue;
                    if (v.isNetworkConnectionRequired()) continue; // offline voices only
                    voices.add(v);
                }
            } catch (Exception ignored) { }
            if (voices.isEmpty()) {
                runOnUiThread(() -> toast("No offline English voices. Install Google TTS voice data first."));
                try { tts[0].shutdown(); } catch (Exception ignored) { }
                return;
            }
            // Female + Indian first, then by name.
            voices.sort((a, b) -> {
                int fa = (isFemaleVoiceName(a.getName()) ? 2 : 0) + ("IN".equals(a.getLocale().getCountry()) ? 1 : 0);
                int fb = (isFemaleVoiceName(b.getName()) ? 2 : 0) + ("IN".equals(b.getLocale().getCountry()) ? 1 : 0);
                if (fa != fb) return fb - fa;
                return a.getName().compareTo(b.getName());
            });
            final String[] labels = new String[voices.size()];
            int sel = -1;
            String saved = settings.ttsVoiceName();
            for (int i = 0; i < voices.size(); i++) {
                android.speech.tts.Voice v = voices.get(i);
                labels[i] = v.getLocale().getCountry() + " \u00B7 " + v.getName() + (isFemaleVoiceName(v.getName()) ? "  \u2640" : "");
                if (v.getName().equals(saved)) sel = i;
            }
            final int selected = sel;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Choose IRIS voice (tap to preview)")
                    .setSingleChoiceItems(labels, selected, (d, which) -> {
                        android.speech.tts.Voice v = voices.get(which);
                        settings.setTtsVoiceName(v.getName());
                        try {
                            tts[0].setVoice(v);
                            tts[0].setPitch(1.12f);
                            tts[0].setSpeechRate(0.98f);
                            tts[0].speak("Hi, this is IRIS.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "pick");
                        } catch (Exception ignored) { }
                    })
                    .setPositiveButton("Use this voice", (d, w) -> {
                        toast("Voice saved \u2705");
                        if (IrisListeningService.isRunning) { stopListeningService(); handler.postDelayed(this::startListeningService, 600); }
                    })
                    .setNeutralButton("Auto", (d, w) -> { settings.setTtsVoiceName(""); toast("Back to automatic voice."); })
                    .setOnDismissListener(d -> { try { tts[0].shutdown(); } catch (Exception ignored) { } })
                    .show());
        });
    }

    /** Speak a sample line with the currently selected voice. */
    private void testCurrentVoice() {
        final AppSettings settings = new AppSettings(this);
        final android.speech.tts.TextToSpeech[] tts = new android.speech.tts.TextToSpeech[1];
        tts[0] = new android.speech.tts.TextToSpeech(this, status -> {
            if (status != android.speech.tts.TextToSpeech.SUCCESS) { runOnUiThread(() -> toast("TTS not available.")); return; }
            try {
                tts[0].setLanguage(new Locale("en", "IN"));
                String saved = settings.ttsVoiceName();
                if (!saved.isEmpty()) {
                    for (android.speech.tts.Voice v : tts[0].getVoices()) {
                        if (v != null && saved.equals(v.getName())) { tts[0].setVoice(v); break; }
                    }
                }
                tts[0].setPitch(1.12f);
                tts[0].setSpeechRate(0.98f);
                String name = PersonalProfile.preferredName(this);
                String line = "Hello" + (name != null ? " " + name : "") + ", this is IRIS. How do I sound?";
                tts[0].setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override public void onStart(String u) { }
                    @Override public void onDone(String u) { try { tts[0].shutdown(); } catch (Exception ignored) { } }
                    @Override public void onError(String u) { try { tts[0].shutdown(); } catch (Exception ignored) { } }
                });
                tts[0].speak(line, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test");
            } catch (Exception e) { try { tts[0].shutdown(); } catch (Exception ignored) { } }
        });
    }

    private String testRow(String label, boolean ok) { return (ok ? "\u2705 " : "\u274C ") + label + "\n"; }
    private String testLine(String label, String value) { return "\u2022 " + label + ": " + value + "\n"; }

    private boolean notificationAccessGranted() {
        try {
            String s = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    /** Run a quick health checklist and show it in a dialog. */
    private void runSelfTest() {
        final AppSettings settings = new AppSettings(this);
        final StringBuilder r = new StringBuilder();
        String appVer;
        try { appVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { appVer = "unknown"; }
        r.append(testLine("IRIS version", appVer));
        r.append(testRow("Microphone permission", hasPermission(Manifest.permission.RECORD_AUDIO)));
        r.append(testRow("SMS permission", hasPermission(Manifest.permission.SEND_SMS)));
        r.append(testRow("Contacts permission", hasPermission(Manifest.permission.READ_CONTACTS)));
        r.append(testRow("Notification access", notificationAccessGranted()));
        boolean spk = new java.io.File(getFilesDir(), "vosk-model-spk-0.4").isDirectory();
        r.append(testRow("Speaker model (voice ID)", spk));
        ProfileStore ps = new ProfileStore(this);
        ProfileStore.WakeProfile wp = ps.getWakeProfile();
        r.append(testRow("Wake phrase trained", wp.isReady()));
        r.append(testRow("Voice enrolled", wp.isVoiceEnrolled()));
        r.append(testLine("Command pronunciations", ps.commandAliasCount() + " learned"));
        r.append(testLine("High-accuracy voice model", settings.highAccuracyVoice() ? "on" : "off"));
        r.append(testLine("Listening service", IrisListeningService.isRunning ? "running" : "stopped"));
        r.append(testLine("Voice output", settings.serverTts() ? "server (Piper)"
                : settings.ttsVoiceName().isEmpty() ? "auto (device)" : settings.ttsVoiceName()));
        if (settings.serverModeEnabled() && !settings.serverUrl().isEmpty()) {
            final String url = settings.serverUrl(), tok = settings.serverToken();
            r.append("\u23F3 Server: checking\u2026\n");
            new Thread(() -> {
                boolean ok = new ServerClient(url, tok).health(4000);
                final String full = r.toString().replace("\u23F3 Server: checking\u2026\n", testRow("Server reachable", ok));
                runOnUiThread(() -> showReport(full));
            }, "IRIS-SelfTest").start();
        } else {
            r.append(testLine("Server mode", "off"));
            showReport(r.toString());
        }
    }

    /** Copy text to the clipboard (strips surrounding quotes), with a toast. */
    private void copyToClipboard(String text, String toastMsg) {
        if (text == null) return;
        String t = text.replaceAll("^[\u201C\"]+|[\u201D\"]+$", "").trim();
        if (t.isEmpty()) return;
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("IRIS", t));
            toast(toastMsg);
        } catch (Exception e) { toast("Couldn't copy."); }
    }

    // {title, how-to (with examples)} — tap an item in the guide to see this.
    private static final String[][] FEATURE_GUIDE = {
        {"🚀 Getting started", "1) Train a wake phrase (Training tab).\n2) Grant Microphone, Contacts, SMS and Notification access (Settings → Run self-test shows what's missing).\n3) Tap the orb or say your wake phrase, wait for the short beep, then speak your command.\nTip: run Self-test anytime to see what's working."},
        {"🎙️ Wake phrase", "What: IRIS wakes when you say your trained phrase.\nHow: Training → “Set Up Wake Phrase”, type it, say it 5×, confirm to save.\nSay: your phrase, e.g. “Hello IRIS”.\nTips: 2–3 syllables recognise best; it wakes on the first try; it won't interrupt music/video."},
        {"➕ Extra wake phrases", "What: add up to 2 more wake phrases besides the main one.\nHow: Training → under the wake card, type a phrase → “＋ Add”. Tap the list to clear.\nSay: any of them, e.g. “IRIS you there”, “wake up IRIS”.\nNote: extras are recognised as text — no separate voice training needed."},
        {"⏸️ Pause & resume training", "What: long training can be done in parts.\nHow: during wake training tap “Pause (resume later)”. Later, the button shows “▶ Resume training (N/5)” — tap to continue or start over.\nProgress auto-saves after each sample."},
        {"📞 Call a contact", "What: fuzzy-matched calling with confirmation.\nSay: “call mom”, “phone the office”, “dial Rahul”.\nIRIS asks “Did you mean …?” — say “no” to hear the next match; “yes” to call. Say “cancel” to stop."},
        {"👨‍👩‍👧 Call by relationship", "What: call people by relationship (from your profile).\nSay: “call my mother”, “ring my brother”.\nSet these up in iris-me.json (family/relationships)."},
        {"🔤 Call by spelling", "What: for names speech keeps mis-hearing.\nSay: “spell the name”, then spell it — letters or NATO.\nExample: “spell the name” → “Mike Alpha Alpha”. IRIS matches contacts and confirms."},
        {"☎️ Call / text a number", "What: dial or text a raw number.\nSay: “call 98765 43210”, “send a text to 9876543210”.\nIRIS reads numbers digit-by-digit and confirms before calling."},
        {"🔁 Redial", "Say: “redial”, “call back”, “call the last person”."},
        {"✉️ Send SMS", "What: sends a real SMS (works even locked).\nSay naturally (no “saying” needed): “text mom I'll be late”, “tell dad I'm coming”.\nForgot details? Say “send an SMS” — IRIS asks who (checks memory, then contacts, offers matches), confirms, then asks what to say and confirms before sending. Say “cancel” anytime."},
        {"📇 Share your info", "What: send a saved profile value to someone.\nSay: “text my office email to mom”, “send my number to dad”.\nPulls the value from iris-me.json and texts it."},
        {"💬 WhatsApp", "What: opens the chat pre-filled; you tap send (WhatsApp allows no auto-send).\nSay: “whatsapp Sam saying on my way”."},
        {"📧 Email", "Say: “email mom about dinner”, “send an email to john@x.com saying hi”. Opens your mail app pre-filled."},
        {"📱 App integrations", "Search: “search Arijit Singh on Spotify”, “find coffee near me in Maps”, “search camera tips on YouTube”. Support depends on the installed app exposing Android search. IRIS tells you if it cannot do it.\nShare: “share the latest video via WhatsApp”, “share via Telegram saying I will be late”. Review the file or exact text, choose the recipient and press Send in the target app. IRIS never claims delivery from opening a screen. Unlock first.\nNotification replies: say “reply to a notification”. Choose an active conversation, type your reply, review it, then tap Send reply. Requires notification access and an app-provided Reply action. No paid API; receiving apps may use the internet."},
        {"⏰ Alarms & timers", "Say: “set an alarm for 7 am”, “wake me at 6:30”, “set alarm 6 am”, “set a timer for 10 minutes”, “timer 5 minutes”."},
        {"🔔 Reminders", "Say: “remind me to <task> in/at <time>”.\nExamples: “remind me to call dad in 10 minutes”, “remind me to take medicine at 9 pm”."},
        {"📅 Calendar", "Say: “add a meeting tomorrow at 5”, “create an event <title> <when>”. Opens your calendar pre-filled."},
        {"🎵 Media & music", "Control any player: “pause”, “resume”, “next” / “next song”, “previous”, “stop music”.\nPlay a local track: “play <song or artist>” (hands off to your music app)."},
        {"🔊 Volume", "Say: “volume up/down”, “mute”, “max volume”, “set volume to 50 percent”."},
        {"🖥 Screenshot & screen recording", "Capture what's on your screen.\nScreenshot: say “take a screenshot” — saved to Pictures/IRIS (or your system Screenshots folder).\nScreen recording: say “record the screen” (defaults to 1 minute) or “record the screen for 2 minutes”; saved to Movies/IRIS with mic audio. Stop early with “stop screen recording” or the Stop button.\nNo pop-up option: enable IRIS in Settings → Accessibility → Installed services, and screenshots are taken instantly with no permission prompt. Otherwise Android shows a one-time “Start recording/casting?” consent (tick “don't ask again”). Secure screens (banking, DRM) appear black by Android's design."},
        {"⌚ Replies on your watch", "Turn on Settings → “Show replies as a notification when the screen is off” and IRIS mirrors its reply to a notification whenever the screen is off — so you can read it on your watch. Add “Only major replies” to limit it to action results (saved files, status, notifications) instead of every small acknowledgement."},
        {"🎬 Video & photo", "Record a short video, saved to Movies/IRIS, or take a still photo, saved to Pictures/IRIS.\nVideo: “start recording 30” (back camera, else 1 minute by default), “record video 20”, “record front camera video 15”, “record selfie video 10”.\nPhoto: “take a photo”, “take a selfie”, “click a picture”, “take a front camera photo”.\nBoth support “front/selfie” or “back/rear” lens choice. Video auto-stops after the time you set; tap the ⏹ Stop button on the recording notification to end early (also on the lock screen/watch). IRIS opens its capture screen above the keyguard without unlocking. If Android blocks it, tap the camera notification within 60 seconds. Grant Camera, Microphone and Notifications while unlocked first. Stop and save is also on the capture screen. Leaving this screen stops capture. Voice listening pauses while recording; use the Stop button. Other apps and protected screen capture may still require unlock or consent."},
        {"⚡ More ways to trigger IRIS", "Besides the wake word:\n• Notification: tap the “🎙 Talk” button on IRIS's notification (works on the lock screen).\n• Quick Settings tile: add the IRIS tile to your shade and tap it.\n• Assistant: set IRIS as your device's Digital Assistant (Settings → Default apps) — then the assist gesture (long-press power/home) opens IRIS anywhere.\n• Shake to talk (optional): enable in Settings, then shake the phone.\n• Headset button (optional): enable in Settings, then double-press your earphone button."},
        {"🎙 Voice recording", "Record a timed voice memo, saved as an .m4a in Recordings/IRIS (or Music/IRIS on older phones).\nSay: “record voice 20”, “voice memo 30”, “record audio for 1 minute”. Stop early with “stop recording” or the Stop button in the notification (works on the lock screen).\nPick a mic: add “using earphone / bluetooth / phone mic” — e.g. “record voice using bluetooth 30”. Otherwise it uses your Settings → Microphone choice. IRIS speaks first so its own voice isn't captured, and confirms where it saved."},
        {"🧭 Remembers what it did", "IRIS keeps a private log of what it actually did, so you can refer back to it.\nAsk: “what did you just do?”, “where did you save it?”, “what have you done?”\nDo: “send the last screenshot”, “send the latest video”, “do that again”, “undo that” (cancels the last reminder it set).\nSee everything in Settings → RECOGNITION & LEARNING → “What IRIS did”, and clear it anytime. Kept 30 days, on this phone only. Before sending a file it tells you which one it found."},
        {"❓ IRIS asks instead of guessing", "If a command is missing a detail, IRIS now asks one short question rather than doing nothing or the wrong thing.\nSay “set an alarm” → “What time should I set the alarm for?” → say “7 am” → alarm set.\nSame for “set a timer” (how long?) and “call” (who?). Say “cancel” to drop it.\nSensitive actions like calls and messages are always read back or confirmed before they happen."},
        {"🩺 Teach IRIS your words", "When IRIS mishears you, teach it once and it remembers — all on this phone.\nAfter two unclear tries (or if you say “that was wrong”), IRIS offers a card: it shows what it heard, you type what you meant, and it learns.\nAnytime: Settings → RECOGNITION & LEARNING → “Fix what IRIS misheard”. Example: heard “call somojit” → you type “call Soumyajit”; next time it reads it correctly.\n“My words & name pronunciations” lists everything learned (and can clear it). It only applies a fix when the whole phrase matches, plus learned name spellings — it never rewrites your dictated message text."},
        {"📈 Recognition report", "Settings → RECOGNITION & LEARNING → “Recognition report” shows how well IRIS is really hearing you: attempts, how many were clear on the first try, average response time, corrections taught, and wrong actions.\nThe goal is zero wrong actions — asking once is better than acting wrongly. A low first-try rate usually means microphone or noise, not your wording. You can reset the stats anytime.\nSay “that was wrong” right after a mistake to log it and teach the fix."},
        {"⏹ Stop / interrupt IRIS", "If IRIS is talking too long (e.g. reading many notifications), tap the ⏹ Stop button on IRIS's notification — it cuts the speech off instantly and works on the lock screen. Saying “stop” also works whenever the mic is open. Example: “read my notifications” → tap ⏹ Stop to halt."},
        {"🧭 Remembers its last action", "IRIS keeps track of what it just did. Ask “what did you just do?” and it tells you. Say “do that again” or “repeat that” to re-run your last command. Example: “take a screenshot” … then “do that again”."},
        {"📊 Phone status", "Ask “What is my Wi-Fi IP?”, “What is my battery health?”, “What is my screen refresh rate?”, “Which sensors are being used?”, or “phone status” and IRIS reports it all: ringer (silent/vibrate/normal), Do Not Disturb, airplane mode, internet (Wi-Fi or mobile data), the actual connected Bluetooth device (not just on/off), battery, device model + Android version, free storage, RAM usage, and uptime.\nAsk “what's connected to Bluetooth?” on its own anytime.\nA dense [SYS]/[PWR]/[NET]-style status also lands in your notifications for a quick-glance look."},
        {"🔕 Phone modes", "Turn modes on/off and check them; IRIS tells you if it's already in that state.\nSay: “silent mode on/off”, “vibrate mode”, “normal mode”, “turn on/off do not disturb”, “airplane mode on/off”.\nAsk: “is silent mode on?”, “is airplane mode on?”, “is DND on?”.\nNotes: silent/vibrate/DND need Do-Not-Disturb access once. Airplane mode can't be toggled by apps — IRIS opens Settings for you (but can tell you if it's on)."},
        {"🔦 Torch / flashlight", "Say: “turn on the flashlight”, “torch off”."},
        {"🌦️ Weather", "Say: “what's the weather”, “weather today”. (Uses your location.)"},
        {"📍 Location", "Say: “where am I”, “my location”."},
        {"📬 Notifications", "Say: “read my notifications”, “what did I miss”, “who texted me”, “clear all notifications”. (Needs Notification access.)"},
        {"🌐 Web & apps", "Say: “search for <query>”, “open <app>”, “navigate to <place>”."},
        {"🧠 Memory", "Say: “remember I like green tea”, “what do you know about me”, “what do you know about my car”, “forget that”.\nAsking about a topic now returns IRIS's best-matching memories instead of a random dump — it actually uses what you've told it in chat and commands.\nEdit any memory in the Memory tab with the ✎ button (not just delete). Profile facts (name, phone, family…) stay read-only in-app — edit iris-me.json to change them; they re-sync on each update."},
        {"🎓 Voice & command training", "What: teach IRIS your accent.\nHow: Training → “Learn My Voice & Commands” — read a few sentences (builds your voice pattern) then say each command word (learns your pronunciation)."},
        {"🎯 High-accuracy voice model", "Settings → “High-accuracy voice model (~1GB)” downloads a bigger offline model for tougher accents (falls back to the small one automatically). Use Wi-Fi."},
        {"👂 Command accuracy (Google)", "Speak in your own accent.\nSettings → Set up Indian English accuracy selects English (India), system speech and no forced on-device preference. Existing explicit settings are preserved until you apply it. System speech may send audio to its provider; IRIS cannot guarantee the same engine as your watch.\nWait until Listening appears/the ready cue sounds, then say the full sentence naturally. Example: “Could you switch on the torch?”, “Give Maa a call”, “Click one screenshot”. A low-confidence result asks you to repeat rather than acting on a guess.\nOffline fallback uses Indian English, not Hindi. For mixed Hindi/English choose the appropriate language setting; support depends on your speech provider. Training stores aliases, not a newly trained speech model."},
        {"🎚️ Choose voice", "Settings → “Choose IRIS voice” to pick a female/other voice; “Test voice” to preview. (Install Google TTS en-IN voices for the best quality.)"},
        {"🎭 Personality", "Settings → Personality picks how IRIS sounds: Sarcastic (dry, witty, uses your name more), Warm (personable, name-heavy), Professional (formal, “sir” only, no first name), or Silent (no speech, no notification text — fully quiet). The tone shows up in casual chat, greetings, and how it addresses you."},
        {"🎩 How IRIS addresses you", "Like a butler, it varies — mostly nothing, sometimes “sir”, occasionally your name — instead of your name every time. How often depends on your chosen Personality."},
        {"🔐 Voice-verified wake", "Wakes only for your voice (Settings → Voice Security). Sensitivity slider: lenient ↔ strict. If it ever won't wake for you, ease it toward lenient or retrain."},
        {"🔒 Lock-screen control", "Settings → lock-screen control lets quick actions run while locked; opening another app's screen still asks for unlock (Android requirement)."},
        {"🛰️ Server mode", "Settings → Server mode: use your own online brain (Ollama + Whisper) when connected; auto-falls back offline. Say “go online” / “go offline”. Setup: server/README.md."},
        {"🩺 Self-test", "Settings → “Run self-test” checks permissions, models, wake/voiceprint, learned commands, voice, and server — a green/red checklist."},
        {"📋 Copy text", "On the Assistant screen, tap the recognized text or IRIS's reply to copy it."},
        {"🛑 Stop / sleep", "Say “stop” or “go to sleep” to dismiss. To fully shut IRIS down: “kill”, “kill yourself”, “kill IRIS”, “self destruct”, or “shut down”. (“turn off” alone also works, but “turn off <something else>” is safely ignored.)"},
        {"🏷️ Version / what's new", "Say “what version are you” or “what's new”."},
        {"🧰 Troubleshooting", "Wake not firing: retrain the phrase; ease Voice Security sensitivity; add an extra phrase.\nMis-hears commands: keep internet on (Google STT), run “Learn My Voice & Commands”, or enable the high-accuracy model.\nDoes nothing after wake: wait for the beep, then speak; run Self-test for red items (mic/permissions)."},
    };

    /** Ordered categories for the guide. */
    private static final String[] GUIDE_ORDER = {
            "Getting started", "Wake & triggers", "Calls & contacts", "Messaging",
            "Recording & capture", "Reminders & calendar", "Media & device control",
            "Info & utilities", "Voice & personalization", "Advanced", "Help"
    };

    /** Map a feature (by its plain title, minus the leading emoji) to a category. */
    private String guideCategory(String title) {
        int sp = title.indexOf(' ');
        String name = sp >= 0 ? title.substring(sp + 1) : title;
        switch (name) {
            case "Getting started": return "Getting started";
            case "Wake phrase": case "Extra wake phrases": case "Pause & resume training":
            case "More ways to trigger IRIS": case "Voice-verified wake": return "Wake & triggers";
            case "Call a contact": case "Call by relationship": case "Call by spelling":
            case "Call / text a number": case "Redial": return "Calls & contacts";
            case "Send SMS": case "Share your info": case "WhatsApp": case "Email": return "Messaging";
            case "Voice recording": case "Video recording":
            case "Screenshot & screen recording": return "Recording & capture";
            case "Alarms & timers": case "Reminders": case "Calendar": return "Reminders & calendar";
            case "Media & music": case "Volume": case "Torch / flashlight":
            case "Phone modes": return "Media & device control";
            case "Phone status": case "Weather": case "Location": case "Notifications":
            case "Web & apps": case "Memory": return "Info & utilities";
            case "Voice & command training": case "High-accuracy voice model":
            case "Command accuracy (Google)": case "Choose voice":
            case "How IRIS addresses you": return "Voice & personalization";
            case "Personality": return "Voice & personalization";
            case "Lock-screen control": case "Server mode": case "Self-test":
            case "Copy text": case "Stop / sleep": case "Version / what's new": return "Advanced";
            case "Troubleshooting": return "Help";
            default: return "Advanced";
        }
    }

    /** A friendly, grouped, tap-to-expand guide instead of a flat list. */
    private void showFeatureGuide() {
        final float d = getResources().getDisplayMetrics().density;
        int[] accents = { R.color.cyan, R.color.magenta, R.color.mint };

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0E1116);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0E1116);
        int pad = (int) (18 * d);
        col.setPadding(pad, (int) (10 * d), pad, pad);
        scroll.addView(col);

        TextView intro = new TextView(this);
        intro.setText("Tap any card to expand it. Wake IRIS (say your phrase or tap the orb), wait for the beep, then speak your command.");
        intro.setTextColor(0xFFB8C0CC);
        intro.setTextSize(12.5f);
        intro.setLineSpacing((int) (3 * d), 1f);
        col.addView(intro);

        boolean firstCard = true;
        int ci = 0;
        for (String category : GUIDE_ORDER) {
            int accent = getColor(accents[ci % accents.length]);
            boolean headerAdded = false;
            for (String[] item : FEATURE_GUIDE) {
                if (!category.equals(guideCategory(item[0]))) continue;
                if (!headerAdded) {
                    TextView header = new TextView(this);
                    header.setText("▍ " + category.toUpperCase(Locale.ROOT));
                    header.setTextColor(accent);
                    header.setTextSize(12.5f);
                    header.setLetterSpacing(0.10f);
                    header.setTypeface(null, Typeface.BOLD);
                    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    hp.topMargin = (int) (20 * d);
                    hp.bottomMargin = (int) (8 * d);
                    header.setLayoutParams(hp);
                    col.addView(header);
                    headerAdded = true;
                }

                final String title = item[0];
                final String body = item[1];

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF1A1F2B);
                bg.setCornerRadius(14 * d);
                bg.setStroke((int) (1.5f * d), (accent & 0x00FFFFFF) | 0x66000000);
                card.setBackground(bg);
                int cp = (int) (14 * d);
                card.setPadding(cp, cp, cp, cp);
                LinearLayout.LayoutParams cpm = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cpm.bottomMargin = (int) (9 * d);
                card.setLayoutParams(cpm);

                final TextView tv = new TextView(this);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(15.5f);
                tv.setTypeface(null, Typeface.BOLD);
                card.addView(tv);

                final TextView bv = new TextView(this);
                bv.setText(body);
                bv.setTextColor(0xFFD7DDE6);
                bv.setTextSize(14f);
                bv.setLineSpacing((int) (5 * d), 1f);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                bp.topMargin = (int) (9 * d);
                bv.setLayoutParams(bp);
                card.addView(bv);

                final boolean startOpen = firstCard;
                bv.setVisibility(startOpen ? View.VISIBLE : View.GONE);
                tv.setText((startOpen ? "▾  " : "▸  ") + title);
                firstCard = false;

                card.setOnClickListener(v -> {
                    boolean show = bv.getVisibility() != View.VISIBLE;
                    bv.setVisibility(show ? View.VISIBLE : View.GONE);
                    tv.setText((show ? "▾  " : "▸  ") + title);
                });
                col.addView(card);
            }
            if (headerAdded) ci++;
        }

        new AlertDialog.Builder(this)
                .setTitle("IRIS — Features & Guide")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showReport(String text) {
        new AlertDialog.Builder(this)
                .setTitle("IRIS self-test")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
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
                        for (MemoryStore.Memory m : results) addMemoryCard(listHost, m, colorForCategory(m.category));
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

            for (MemoryStore.Memory m : catMemories) addMemoryCard(listHost, m, getColor(catColors[c]));
        }
    }

    private int colorForCategory(String category) {
        if (MemoryStore.CAT_PEOPLE.equals(category)) return getColor(R.color.magenta);
        if (MemoryStore.CAT_PREFERENCE.equals(category)) return getColor(R.color.violet);
        if (MemoryStore.CAT_RULE.equals(category)) return getColor(R.color.mint);
        if (MemoryStore.CAT_CORRECTION.equals(category)) return getColor(R.color.amber);
        return getColor(R.color.cyan);
    }

    private void addMemoryCard(LinearLayout host, MemoryStore.Memory memory, int accent) {
        boolean locked = "profile".equals(memory.source);       // seeded from iris-me.json
        boolean auto = "auto_learned".equals(memory.source);

        // Outer row: [accent bar] [content ...........] [action]
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(6);
        row.setLayoutParams(rowParams);

        // Left accent bar (category colour) — terminal / HUD feel
        View bar = new View(this);
        bar.setBackgroundColor(accent);
        row.addView(bar, new LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams contentParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        content.setLayoutParams(contentParams);

        // Key line: monospace, uppercase, accent-coloured, with a lock/auto tag
        TextView keyView = new TextView(this);
        String tag = locked ? "  \uD83D\uDD12" : (auto ? "  \u26A1" : "");
        keyView.setText("\u25B8 " + memory.key.toUpperCase(Locale.ROOT) + tag);
        keyView.setTextColor(accent);
        keyView.setTextSize(11);
        keyView.setLetterSpacing(0.05f);
        keyView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        content.addView(keyView);

        TextView valueView = new TextView(this);
        valueView.setText(memory.value);
        valueView.setTextColor(getColor(R.color.text_primary));
        valueView.setTextSize(14);
        valueView.setPadding(0, dp(3), 0, 0);
        content.addView(valueView);

        if (memory.detail != null && !memory.detail.isEmpty()) {
            TextView detailView = new TextView(this);
            detailView.setText(memory.detail);
            detailView.setTextColor(getColor(R.color.text_muted));
            detailView.setTextSize(12);
            detailView.setPadding(0, dp(2), 0, 0);
            content.addView(detailView);
        }

        row.addView(content);

        if (locked) {
            // Read-only: no delete, tapping explains why.
            TextView lock = new TextView(this);
            lock.setText("\uD83D\uDD12");
            lock.setTextSize(14);
            lock.setGravity(android.view.Gravity.CENTER);
            lock.setOnClickListener(v -> toast("From your profile — edit iris-me.json to change this."));
            row.addView(lock, new LinearLayout.LayoutParams(dp(44), dp(44)));
        } else {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button edit = new Button(this);
            edit.setText("\u270E");
            edit.setTextColor(getColor(R.color.cyan));
            edit.setTextSize(16);
            edit.setBackgroundResource(R.drawable.bg_button_secondary);
            edit.setOnClickListener(v -> showEditMemoryDialog(host, memory, accent));
            actions.addView(edit, new LinearLayout.LayoutParams(dp(44), dp(40)));
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
            actions.addView(delete, new LinearLayout.LayoutParams(dp(44), dp(40)));
            row.addView(actions);
        }

        host.addView(row);
    }

    /** Edit an existing memory's value/detail — wires the previously-dead MemoryStore.update(). */
    private void showEditMemoryDialog(LinearLayout host, MemoryStore.Memory memory, int accent) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(8), dp(22), 0);

        TextView label = new TextView(this);
        label.setText(memory.key.toUpperCase(Locale.ROOT));
        label.setTextColor(accent);
        label.setTextSize(12);
        col.addView(label);

        EditText valueInput = new EditText(this);
        valueInput.setText(memory.value);
        valueInput.setTextColor(getColor(R.color.text_primary));
        col.addView(valueInput);

        EditText detailInput = new EditText(this);
        detailInput.setHint("Optional detail");
        detailInput.setText(memory.detail == null ? "" : memory.detail);
        detailInput.setTextColor(getColor(R.color.text_primary));
        detailInput.setHintTextColor(getColor(R.color.text_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        detailInput.setLayoutParams(lp);
        col.addView(detailInput);

        new AlertDialog.Builder(this).setTitle("Edit memory")
                .setView(col)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> authenticateThen("\uD83D\uDD12 Update memory", () -> {
                    String newValue = valueInput.getText().toString().trim();
                    String newDetail = detailInput.getText().toString().trim();
                    if (newValue.isEmpty()) { toast("Value can't be empty."); return; }
                    MemoryStore.update(this, memory.id, newValue, newDetail.isEmpty() ? null : newDetail);
                    toast("Memory updated.");
                    showMemory();
                })).show();
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
        view.findViewById(R.id.indianAccuracyButton).setOnClickListener(v ->
                new AlertDialog.Builder(this).setTitle("Indian English accuracy")
                        .setMessage("Use English (India) and the system speech recognizer, without forcing offline recognition. This can send speech audio to your installed speech provider. Your contact list is not added to recognition hints.\n\nAfter applying: wake IRIS, wait for Listening, then speak normally. No need to imitate another accent.")
                        .setNegativeButton("Keep current settings", null)
                        .setPositiveButton("Apply", (d, w) -> {
                            settings.setLanguageTag("en-IN");
                            settings.setPreferOnDevice(false);
                            settings.setGoogleSttForCommands(true);
                            restartIfRunning();
                            showSettings();
                            toast("Indian English selected. System speech may use the internet.");
                        }).show());
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
        // Voice security
        android.widget.SeekBar sensSeek = view.findViewById(R.id.sensitivitySeek);
        if (sensSeek != null) {
            sensSeek.setProgress(Math.round(settings.voiceSensitivity() * 100));
            sensSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) { }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    settings.setVoiceSensitivity(sb.getProgress() / 100f);
                    toast("Voice sensitivity: " + sb.getProgress() + "%");
                }
            });
        }
        Switch voiceCueSwitch = view.findViewById(R.id.voiceCueSwitch);
        if (voiceCueSwitch != null) {
            voiceCueSwitch.setChecked(settings.voiceCueEnabled());
            voiceCueSwitch.setOnCheckedChangeListener((b, checked) -> settings.setVoiceCueEnabled(checked));
        }
        Switch shakeSwitch = view.findViewById(R.id.shakeSwitch);
        if (shakeSwitch != null) {
            shakeSwitch.setChecked(settings.shakeToWake());
            shakeSwitch.setOnCheckedChangeListener((b, checked) -> {
                settings.setShakeToWake(checked);
                toast("Shake trigger " + (checked ? "on" : "off") + " — restart IRIS to apply.");
            });
        }
        Switch headsetSwitch = view.findViewById(R.id.headsetSwitch);
        if (headsetSwitch != null) {
            headsetSwitch.setChecked(settings.headsetTrigger());
            headsetSwitch.setOnCheckedChangeListener((b, checked) -> {
                settings.setHeadsetTrigger(checked);
                toast("Headset trigger " + (checked ? "on" : "off") + " — restart IRIS to apply.");
            });
        }
        Switch mirrorSwitch = view.findViewById(R.id.mirrorSwitch);
        if (mirrorSwitch != null) {
            mirrorSwitch.setChecked(settings.mirrorReplies());
            mirrorSwitch.setOnCheckedChangeListener((b, checked) -> settings.setMirrorReplies(checked));
        }
        Switch mirrorAlwaysSwitch = view.findViewById(R.id.mirrorAlwaysSwitch);
        if (mirrorAlwaysSwitch != null) {
            mirrorAlwaysSwitch.setChecked(settings.mirrorAlways());
            mirrorAlwaysSwitch.setOnCheckedChangeListener((b, checked) -> settings.setMirrorAlways(checked));
        }
        Switch mirrorMajorSwitch = view.findViewById(R.id.mirrorMajorSwitch);
        if (mirrorMajorSwitch != null) {
            mirrorMajorSwitch.setChecked(settings.mirrorMajorOnly());
            mirrorMajorSwitch.setOnCheckedChangeListener((b, checked) -> settings.setMirrorMajorOnly(checked));
        }
        TextView voiceprintStatus = view.findViewById(R.id.voiceprintStatus);
        if (voiceprintStatus != null) {
            boolean enrolled = new ProfileStore(this).getVoiceprint() != null;
            voiceprintStatus.setText(enrolled
                    ? "Voiceprint enrolled. Owner wake requires the offline model; media playback pauses wake."
                    : "Voiceprint: not enrolled — train your wake phrase to enroll.");
        }
        Button clearVoiceprintButton = view.findViewById(R.id.clearVoiceprintButton);
        if (clearVoiceprintButton != null) {
            clearVoiceprintButton.setOnClickListener(v -> {
                new ProfileStore(this).setVoiceprint(null);
                if (voiceprintStatus != null)
                    voiceprintStatus.setText("Voiceprint cleared. Voice wake is unavailable until you enroll again.");
                toast("Voice security cleared.");
            });
        }
        // ── Command Deck customisation ──
        bindDeckSwitch(view, R.id.deckTelemetrySwitch, settings.deckTelemetry(), settings::setDeckTelemetry);
        bindDeckSwitch(view, R.id.deckActivitySwitch, settings.deckActivityStream(), settings::setDeckActivityStream);
        bindDeckSwitch(view, R.id.deckTilesSwitch, settings.deckTiles(), settings::setDeckTiles);
        bindDeckSwitch(view, R.id.deckSaverSwitch, settings.deckBatterySaver(), settings::setDeckBatterySaver);
        bindDeckSwitch(view, R.id.deckScanlineSwitch, settings.deckScanline(), settings::setDeckScanline);
        bindDeckSwitch(view, R.id.deckPublicIpSwitch, settings.telemetryPublicIp(), settings::setTelemetryPublicIp);
        final TextView orbLabel = view.findViewById(R.id.deckOrbSizeLabel);
        SeekBar orbSeek = view.findViewById(R.id.deckOrbSizeSeek);
        if (orbSeek != null) {
            orbSeek.setProgress(Math.max(0, Math.min(140, settings.deckOrbSize() - 120)));
            if (orbLabel != null) orbLabel.setText("Orb size: " + settings.deckOrbSize() + "dp");
            orbSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                    int dp = 120 + p;
                    if (orbLabel != null) orbLabel.setText("Orb size: " + dp + "dp");
                    if (fromUser) settings.setDeckOrbSize(dp);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }
        Spinner tabSpinner = view.findViewById(R.id.deckTabSpinner);
        if (tabSpinner != null) {
            final String[] keys = { "overview", "network", "devices", "sensors", "resources" };
            String[] labels = { "Overview", "Network", "Devices", "Sensors", "Power" };
            ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels);
            tabSpinner.setAdapter(ad);
            for (int i = 0; i < keys.length; i++) if (keys[i].equals(settings.deckDefaultTab())) tabSpinner.setSelection(i);
            tabSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                    settings.setDeckDefaultTab(keys[pos]);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
            });
        }

        Button fixMisheard = view.findViewById(R.id.fixMisheardButton);
        if (fixMisheard != null) {
            fixMisheard.setOnClickListener(v -> showHeardMeantCard(lastTranscriptSeen));
        }
        Button vocabularyButton = view.findViewById(R.id.vocabularyButton);
        if (vocabularyButton != null) {
            vocabularyButton.setOnClickListener(v -> {
                PersonalVocabulary vocab = new PersonalVocabulary(this);
                new AlertDialog.Builder(this).setTitle("My words & pronunciations")
                        .setMessage(vocab.summary())
                        .setPositiveButton("Close", null)
                        .setNegativeButton("Clear all", (d, w) -> {
                            vocab.clearAll();
                            toast("Cleared everything IRIS had learned about your wording.");
                        }).show();
            });
        }
        Button recognitionReport = view.findViewById(R.id.recognitionReportButton);
        if (recognitionReport != null) {
            recognitionReport.setOnClickListener(v -> {
                RecognitionStats stats = new RecognitionStats(this);
                new AlertDialog.Builder(this).setTitle("Recognition report")
                        .setMessage(stats.report())
                        .setPositiveButton("Close", null)
                        .setNegativeButton("Reset stats", (d, w) -> {
                            stats.reset();
                            toast("Recognition stats reset.");
                        }).show();
            });
        }
        Button activityTimeline = view.findViewById(R.id.activityTimelineButton);
        if (activityTimeline != null) {
            activityTimeline.setOnClickListener(v -> {
                ActionLedger led = new ActionLedger(this);
                new AlertDialog.Builder(this).setTitle("What IRIS did")
                        .setMessage(led.timeline(30))
                        .setPositiveButton("Close", null)
                        .setNegativeButton("Clear history", (d, w) -> {
                            led.clearAll();
                            toast("Action history cleared.");
                        }).show();
            });
        }
        Button feedbackMissed = view.findViewById(R.id.feedbackMissedButton);
        if (feedbackMissed != null) {
            feedbackMissed.setOnClickListener(v -> authenticateThen("Improve owner voice", () -> {
                new ProfileStore(this).setPendingVoiceSample(null);
                toast("Record fresh samples in a quiet room. Rejected background voices are never added to your profile.");
                beginWakeTraining();
            }));
        }

        Button feedbackFalse = view.findViewById(R.id.feedbackFalseButton);
        if (feedbackFalse != null) {
            feedbackFalse.setOnClickListener(v -> {
                float s = Math.min(1f, settings.voiceSensitivity() + 0.08f);
                settings.setVoiceSensitivity(s);
                if (sensSeek != null) sensSeek.setProgress(Math.round(s * 100));
                toast("Made voice matching stricter.");
                LogStore.append(this, "VOICE FEEDBACK", "false wake → sensitivity " + Math.round(s * 100) + "%");
            });
        }

        Switch highAccSwitch = view.findViewById(R.id.highAccuracyVoiceSwitch);
        if (highAccSwitch != null) {
            highAccSwitch.setChecked(settings.highAccuracyVoice());
            highAccSwitch.setOnCheckedChangeListener((b, checked) -> {
                settings.setHighAccuracyVoice(checked);
                if (checked) {
                    toast("High-accuracy model on. It downloads (~1GB) next time IRIS starts listening, "
                            + "and stays loaded in memory the whole time IRIS is listening — expect higher RAM use. "
                            + "Turn this off if IRIS feels heavy.");
                } else {
                    toast("Back to the fast, low-memory small model.");
                }
                if (IrisListeningService.isRunning) {
                    stopListeningService();
                    handler.postDelayed(this::startListeningService, 600);
                }
            });
        }

        Switch serverModeSwitch = view.findViewById(R.id.serverModeSwitch);
        EditText serverUrlInput = view.findViewById(R.id.serverUrlInput);
        EditText serverTokenInput = view.findViewById(R.id.serverTokenInput);
        Switch serverSttSwitch = view.findViewById(R.id.serverSttSwitch);
        TextView serverStatus = view.findViewById(R.id.serverStatus);
        if (serverModeSwitch != null) serverModeSwitch.setChecked(settings.serverModeEnabled());
        if (serverSttSwitch != null) serverSttSwitch.setChecked(settings.serverStt());
        if (serverUrlInput != null && !settings.serverUrl().isEmpty()) serverUrlInput.setText(settings.serverUrl());
        if (serverTokenInput != null && !settings.serverToken().isEmpty()) serverTokenInput.setText(settings.serverToken());
        if (serverStatus != null) serverStatus.setText(settings.serverModeEnabled()
                ? (settings.serverUrl().isEmpty() ? "On, but no URL set." : "On \u2022 " + settings.serverUrl())
                : "Off (offline).");
        if (serverModeSwitch != null) {
            serverModeSwitch.setOnCheckedChangeListener((b, checked) -> {
                settings.setServerModeEnabled(checked);
                toast(checked ? "Server mode on — falls back offline automatically." : "Server mode off.");
            });
        }
        if (serverSttSwitch != null) {
            serverSttSwitch.setOnCheckedChangeListener((b, checked) -> settings.setServerStt(checked));
        }
        Switch serverTtsSwitch = view.findViewById(R.id.serverTtsSwitch);
        if (serverTtsSwitch != null) {
            serverTtsSwitch.setChecked(settings.serverTts());
            serverTtsSwitch.setOnCheckedChangeListener((b, checked) -> settings.setServerTts(checked));
        }
        Button chooseVoiceButton = view.findViewById(R.id.chooseVoiceButton);
        if (chooseVoiceButton != null) chooseVoiceButton.setOnClickListener(v -> showVoicePicker());
        Button testVoiceButton2 = view.findViewById(R.id.testVoiceButton);
        if (testVoiceButton2 != null) testVoiceButton2.setOnClickListener(v -> testCurrentVoice());
        Button selfTestButton = view.findViewById(R.id.selfTestButton);
        if (selfTestButton != null) selfTestButton.setOnClickListener(v -> runSelfTest());
        Button saveServerButton = view.findViewById(R.id.saveServerButton);
        if (saveServerButton != null && serverUrlInput != null && serverTokenInput != null) {
            saveServerButton.setOnClickListener(v -> {
                settings.setServerUrl(serverUrlInput.getText().toString().trim());
                settings.setServerToken(serverTokenInput.getText().toString().trim());
                toast("Server settings saved.");
                if (serverStatus != null) serverStatus.setText(settings.serverUrl().isEmpty()
                        ? "No URL set." : "Saved \u2022 " + settings.serverUrl());
            });
        }
        Button testServerButton = view.findViewById(R.id.testServerButton);
        if (testServerButton != null) {
            testServerButton.setOnClickListener(v -> {
                String url = serverUrlInput != null ? serverUrlInput.getText().toString().trim() : settings.serverUrl();
                String tok = serverTokenInput != null ? serverTokenInput.getText().toString().trim() : settings.serverToken();
                if (url.isEmpty()) { toast("Enter a server URL first."); return; }
                if (serverStatus != null) serverStatus.setText("Testing\u2026");
                new Thread(() -> {
                    boolean ok = new ServerClient(url, tok).health(4000);
                    runOnUiThread(() -> {
                        toast(ok ? "Connected \u2705" : "Couldn't reach the server.");
                        if (serverStatus != null) serverStatus.setText(ok ? "Connected \u2705 \u2022 " + url : "Unreachable \u2022 " + url);
                    });
                }, "IRIS-ServerTest").start();
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
        speakerVerification.setEnabled(false);
        speakerVerification.setText("Owner verification required for voice wake");

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
        micRouteText.setText("\uD83C\uDF99  Microphone: " + micLabel());
        recognitionText.setText("\uD83E\uDDE0  Recognition: " + lastRecognition);
        updateFrequentContacts();
    }

    /** Best-known mic route: live broadcast → service static → the configured preference. */
    private String micLabel() {
        if (lastMicRoute != null && !lastMicRoute.isEmpty()) return lastMicRoute;
        String s = IrisListeningService.currentMic;
        if (s != null && !s.isEmpty()) return s;
        String pref = new AppSettings(this).preferredMicrophone();
        return (pref == null || pref.isEmpty() || "Automatic".equals(pref)) ? "Phone microphone (auto)" : pref;
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

    private void renderAltWakeList(TextView tv) {
        if (tv == null) return;
        java.util.List<String> alts = new ProfileStore(this).getWakeProfile().altPhrases;
        if (alts.isEmpty()) tv.setText("No extra phrases yet — your main phrase always works.");
        else tv.setText("Extra: " + android.text.TextUtils.join(", ", alts) + "  (tap to clear)");
    }

    /** Set the wake button to Resume (if a partial exists) or Set Up/Retrain. */
    private void configureWakeButton() {
        if (trainWakeButton == null) return;
        int saved = TrainingProgress.peekIndex(this);
        if (saved > 0) {
            trainWakeButton.setText("\u25B6 Resume training (" + saved + "/5)");
            trainWakeButton.setOnClickListener(v -> authenticateThen("\uD83D\uDD12 Resume training", this::showResumeDialog));
        } else {
            ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
            trainWakeButton.setText(wake.isReady() ? "\uD83D\uDD04  Retrain" : "\uD83C\uDFA4  Set Up Wake Phrase");
            trainWakeButton.setOnClickListener(v -> authenticateThen("\uD83D\uDD12 Train wake phrase", this::beginWakeTraining));
        }
        trainWakeButton.setEnabled(true);
    }

    private void showResumeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Resume wake training?")
                .setMessage("You have a partial training saved. Resume where you left off, or start over?")
                .setNeutralButton("Cancel", null)
                .setNegativeButton("Start over", (d, w) -> beginWakeTraining())
                .setPositiveButton("Resume", (d, w) -> resumeWakeTraining())
                .show();
    }

    /** Continue a previously-paused wake training from the saved samples. */
    private void resumeWakeTraining() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingTrainingKind = "wake";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_TRAIN);
            return;
        }
        TrainingProgress.Data d = TrainingProgress.load(this);
        if (d == null || d.phrase.isEmpty()) { beginWakeTraining(); return; }
        resumeAfterWakeTraining = IrisListeningService.isRunning;
        if (resumeAfterWakeTraining) stopListeningService();
        wakePhraseBeingTrained = d.phrase;
        if (wakePhraseInput != null) wakePhraseInput.setText(d.phrase);
        wakeTemplates.clear(); wakeTemplates.addAll(d.templates);
        wakeRawSamples.clear(); wakeRawSamples.addAll(d.rawSamples);
        wakeSampleIndex = Math.min(d.sampleIndex, 5);
        trainWakeButton.setEnabled(false);
        testWakeButton.setEnabled(false);
        if (wakeNormalState != null) wakeNormalState.setVisibility(View.GONE);
        if (wakeWizardState != null) wakeWizardState.setVisibility(View.VISIBLE);
        if (wakeTrainingStatus != null) wakeTrainingStatus.setText("Resuming at " + wakeSampleIndex + "/5\u2026");
        toast("Resuming your wake training.");
        if (wakeSampleIndex >= 5) handler.postDelayed(this::finishWakeTraining, 500);
        else handler.postDelayed(this::captureNextWakeSample, 600);
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
        TrainingProgress.clear(this);   // fresh start — drop any old partial
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

                    // REJECT only near-silent samples — normal speaking voice is fine
                    if (!WakePolicy.usableAudio(audio)) {
                        if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\u274C I didn't hear anything — speak normally toward the phone");
                        wakeTrainingStatus.setText("Rejected: noisy, clipped or too short. Retrying sample " + (wakeSampleIndex + 1) + "...");
                        toast("\u274C Didn't hear you — say it in your normal voice");
                        rejectTone();
                        handler.postDelayed(MainActivity.this::captureNextWakeSample, 1600);
                        return;
                    }

                    float[][] features = WakeWordEngine.extractFeatures(audio);
                    if (features.length < 8) {
                        if (wakeWizardFeedback != null) wakeWizardFeedback.setText("\u274C Didn't catch it \u2014 say the whole phrase clearly");
                        wakeTrainingStatus.setText("Rejected: unclear. Retrying...");
                        toast("\u274C Unclear \u2014 say the full phrase");
                        rejectTone();
                        handler.postDelayed(MainActivity.this::captureNextWakeSample, 1600);
                        return;
                    }

                    float snr = (float) (rmsVal / 300.0);
                    String quality = snr >= 3 ? "Clear" : snr >= 1.2 ? "Usable" : "Quiet";
                    // Accept all captured phrases (even quiet); normal voice is fine.

                    wakeTemplates.add(features);
                    wakeRawSamples.add(audio);
                    wakeSampleIndex++;
                    // Persist progress so training can be resumed later if the user exits.
                    final String ph = wakePhraseBeingTrained;
                    final int idx = wakeSampleIndex;
                    final java.util.List<float[][]> tSnap = new java.util.ArrayList<>(wakeTemplates);
                    final java.util.List<short[]> rSnap = new java.util.ArrayList<>(wakeRawSamples);
                    new Thread(() -> TrainingProgress.save(MainActivity.this, ph, idx, tSnap, rSnap),
                            "IRIS-TrainSave").start();
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
        if (samples == null || samples.isEmpty()) { resumeListeningAfterTraining(); return; }
        // Cap samples to keep memory/CPU bounded during enrollment.
        final java.util.List<short[]> capped = samples.size() > 6
                ? new java.util.ArrayList<>(samples.subList(0, 6)) : samples;
        final VoskEngine ve = new VoskEngine();
        ve.init(this, new VoskEngine.InitListener() {
            @Override public void onReady() {
                ve.initSpeaker(MainActivity.this);
                new Thread(() -> {
                    try {
                        // The speaker model may need a one-time download (initSpeaker) — that
                        // can take well over 10s on a normal connection, which was the actual
                        // reason enrollment silently "failed" after training. Give it real time,
                        // and keep the trainee informed instead of going quiet.
                        long deadline = System.currentTimeMillis() + 45000;
                        int lastPct = -1;
                        while (!ve.isSpeakerReady() && System.currentTimeMillis() < deadline) {
                            try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                            int pct = (int) (100.0 * (System.currentTimeMillis() - (deadline - 45000)) / 45000);
                            if (pct != lastPct && pct % 10 == 0) {
                                lastPct = pct;
                                handler.post(() -> { if (wakeTrainingStatus != null)
                                        wakeTrainingStatus.setText("Preparing voice-lock model\u2026"); });
                            }
                        }
                        java.util.List<float[]> vecs = new java.util.ArrayList<>();
                        if (ve.isSpeakerReady()) {
                            int i = 0;
                            for (short[] s : capped) {
                                float[] e = null;
                                try {
                                    String expected = new ProfileStore(MainActivity.this).getWakeProfile().phrase;
                                    if (WakePolicy.matches(ve.transcribe(s), java.util.Collections.singletonList(expected))) e = ve.embed(s);
                                } catch (Throwable ignored) { }
                                if (e != null && WakePolicy.usableAudio(s)) vecs.add(e);
                                final int pct = (int) (100.0 * (++i) / capped.size());
                                handler.post(() -> { if (wakeTrainingStatus != null)
                                        wakeTrainingStatus.setText("Learning your voice… " + pct + "%"); });
                            }
                        }
                        if (WakePolicy.enrollment(vecs) != null) {
                            new ProfileStore(MainActivity.this).setVoiceprint(WakePolicy.enrollment(vecs));
                            LogStore.append(MainActivity.this, "VOICE", "Enrolled voiceprint from " + vecs.size() + " samples");
                            // Show what IRIS actually heard (engine is already loaded — no extra cost).
                            String heard = "";
                            try { heard = ve.transcribe(capped.get(capped.size() - 1)); } catch (Throwable ignored) { }
                            final String heardF = heard == null ? "" : heard.trim();
                            handler.post(() -> {
                                toast("\uD83D\uDD10 Voice enrolled \u2705");
                                if (wakeTrainingStatus != null && !heardF.isEmpty())
                                    wakeTrainingStatus.setText("\u2705 Saved. I heard: \u201C" + heardF + "\u201D");
                            });
                        } else {
                            LogStore.append(MainActivity.this, "VOICE", "Enrollment skipped (speaker model unavailable)");
                            handler.post(() -> {
                                toast("Voice wake unavailable. Load the offline models and record at least three clear, consistent samples of the full phrase.");
                                if (wakeTrainingStatus != null)
                                    wakeTrainingStatus.setText("\u26A0 Voice wake unavailable: check offline models and retrain in a quiet room.");
                            });
                        }
                    } catch (Throwable t) {
                        LogStore.append(MainActivity.this, "VOICE", "Enrollment error: " + t);
                    } finally {
                        try { ve.close(); } catch (Throwable ignored) { }
                        // Restart the listening service only NOW that this model is freed —
                        // avoids two Vosk models in memory at once (the old crash cause).
                        handler.post(MainActivity.this::resumeListeningAfterTraining);
                    }
                }, "IRIS-Enroll").start();
            }
            @Override public void onError(String message) {
                try { ve.close(); } catch (Throwable ignored) { }
                handler.post(MainActivity.this::resumeListeningAfterTraining);
            }
        });
    }

    /** Restart the wake listener after training finishes (called once enrollment frees its model). */
    private void resumeListeningAfterTraining() {
        if (resumeAfterWakeTraining) {
            resumeAfterWakeTraining = false;
            startListeningService();
        }
    }

    private static float[] averageVectors(java.util.List<float[]> vs) {
        int n = vs.get(0).length;
        float[] a = new float[n];
        for (float[] v : vs) for (int i = 0; i < n && i < v.length; i++) a[i] += v[i];
        for (int i = 0; i < n; i++) a[i] /= vs.size();
        return a;
    }

    // ─────────── Voice & command training ───────────

    private void beginVoiceCommandTraining() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingTrainingKind = "voice";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_TRAIN);
            return;
        }
        voiceTrainCancelled = false;
        trainPhraseIdx = 0; trainCmdIdx = 0; learnedAliasCount = 0;
        voiceReadSamples.clear();
        resumeAfterWakeTraining = IrisListeningService.isRunning;
        if (resumeAfterWakeTraining) stopListeningService();
        if (voiceTrainNormalState != null) voiceTrainNormalState.setVisibility(View.GONE);
        if (voiceTrainWizardState != null) voiceTrainWizardState.setVisibility(View.VISIBLE);
        voiceTrainStep.setText("Preparing…");
        voiceTrainPrompt.setText("Warming up the voice engine — one moment…");
        voiceTrainFeedback.setText("");
        trainVosk = new VoskEngine();
        trainVosk.init(this, new VoskEngine.InitListener() {
            @Override public void onReady() {
                trainVosk.initSpeaker(MainActivity.this);
                handler.postDelayed(() -> { if (!voiceTrainCancelled) trainReadPhraseStep(); }, 400);
            }
            @Override public void onError(String message) {
                handler.post(() -> { toast("Voice engine not ready: " + message); finishVoiceTrainUi(); });
            }
        });
    }

    /** 3-2-1 countdown, a beep, then a timed recording; delivers the PCM (or null) to onDone. */
    private void countdownThenRecord(String sayWhat, int durationMs,
                                     java.util.function.Consumer<short[]> onDone) {
        if (voiceTrainCancelled) return;
        voiceTrainFeedback.setText("Get ready… 3");
        handler.postDelayed(() -> { if (!voiceTrainCancelled) voiceTrainFeedback.setText("Get ready… 2"); }, 600);
        handler.postDelayed(() -> { if (!voiceTrainCancelled) voiceTrainFeedback.setText("Get ready… 1"); }, 1200);
        handler.postDelayed(() -> {
            if (voiceTrainCancelled) return;
            try {
                android.media.ToneGenerator tone = new android.media.ToneGenerator(
                        android.media.AudioManager.STREAM_NOTIFICATION, 90);
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150);
                handler.postDelayed(tone::release, 300);
            } catch (Exception ignored) { }
            voiceTrainFeedback.setText("\uD83D\uDD34 Recording… " + sayWhat);
            TimedRecorder rec = new TimedRecorder();
            timedRecorder = rec;
            rec.record(durationMs, new TimedRecorder.Listener() {
                @Override public void onLevel(float lvl) {
                    if (voiceTrainCancelled) return;
                    int bars = Math.round(lvl * 16);
                    StringBuilder b = new StringBuilder("\uD83D\uDD34 ");
                    for (int i = 0; i < 16; i++) b.append(i < bars ? "\u2593" : "\u2591");
                    voiceTrainFeedback.setText(b.toString());
                }
                @Override public void onComplete(short[] audio) { if (!voiceTrainCancelled) onDone.accept(audio); }
                @Override public void onError(String message) { if (!voiceTrainCancelled) onDone.accept(null); }
            });
        }, 1800);
    }

    private void trainReadPhraseStep() {
        if (voiceTrainCancelled) return;
        if (trainPhraseIdx >= TRAIN_PHRASES.length) { enrollFromReadSamplesThenCommands(); return; }
        int step = trainPhraseIdx + 1;
        voiceTrainStep.setText("\uD83D\uDCD6 Read phrase " + step + " of " + TRAIN_PHRASES.length);
        voiceTrainPrompt.setText("\u201C" + TRAIN_PHRASES[trainPhraseIdx] + "\u201D");
        countdownThenRecord("read the sentence aloud", 5000, audio -> {
            if (audio != null && audio.length > 8000) voiceReadSamples.add(audio);
            trainPhraseIdx++;
            handler.postDelayed(() -> { if (!voiceTrainCancelled) trainReadPhraseStep(); }, 700);
        });
    }

    private void enrollFromReadSamplesThenCommands() {
        if (voiceTrainCancelled) return;
        voiceTrainStep.setText("\uD83E\uDDE0 Learning your voice…");
        voiceTrainPrompt.setText("Building your voice pattern from what you read…");
        voiceTrainFeedback.setText("");
        final java.util.List<short[]> samples = new ArrayList<>(voiceReadSamples);
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 45000;   // model may need a one-time download
            while (trainVosk != null && !trainVosk.isSpeakerReady()
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            }
            java.util.List<float[]> vecs = new java.util.ArrayList<>();
            if (trainVosk != null && trainVosk.isSpeakerReady()) {
                for (short[] s : samples) {
                    float[] e = null;
                    try { e = trainVosk.embed(s); } catch (Throwable ignored) { }
                    if (e != null && WakePolicy.usableAudio(s)) vecs.add(e);
                }
            }
            final boolean enrolled = WakePolicy.enrollment(vecs) != null;
            if (enrolled) {
                new ProfileStore(MainActivity.this).setVoiceprint(WakePolicy.enrollment(vecs));
                LogStore.append(MainActivity.this, "VOICE",
                        "Voice pattern enrolled from " + vecs.size() + " read phrases");
            } else {
                LogStore.append(MainActivity.this, "VOICE",
                        "Voice pattern not saved (speaker model unavailable)");
            }
            handler.post(() -> {
                if (!enrolled) toast("Voice wake unavailable. Check offline models and retrain with at least three clear, consistent samples.");
                if (!voiceTrainCancelled) trainCommandStep();
            });
        }, "IRIS-VoiceTrain-Enroll").start();
    }

    private void trainCommandStep() {
        if (voiceTrainCancelled) return;
        if (trainCmdIdx >= TRAIN_COMMANDS.length) { finishVoiceCommandTraining(); return; }
        final String canonical = TRAIN_COMMANDS[trainCmdIdx];
        voiceTrainStep.setText("\uD83D\uDDE3\uFE0F Command " + (trainCmdIdx + 1) + " of " + TRAIN_COMMANDS.length);
        voiceTrainPrompt.setText("Say this word:\n\n\u201C" + canonical + "\u201D");
        countdownThenRecord("say \u201C" + canonical + "\u201D", 2200, audio -> {
            if (audio == null) {
                trainCmdIdx++;
                handler.postDelayed(() -> { if (!voiceTrainCancelled) trainCommandStep(); }, 500);
                return;
            }
            new Thread(() -> {
                String heard = trainVosk != null ? trainVosk.transcribe(audio) : "";
                if (heard != null && !heard.trim().isEmpty()) {
                    ProfileStore ps = new ProfileStore(MainActivity.this);
                    int before = ps.commandAliasCount();
                    ps.setCommandAlias(canonical, heard.trim());
                    if (ps.commandAliasCount() > before) learnedAliasCount++;
                    LogStore.append(MainActivity.this, "TRAIN",
                            "\"" + canonical + "\" heard as \"" + heard.trim() + "\"");
                }
                handler.post(() -> {
                    trainCmdIdx++;
                    if (!voiceTrainCancelled) handler.postDelayed(
                            () -> { if (!voiceTrainCancelled) trainCommandStep(); }, 500);
                });
            }, "IRIS-VoiceTrain-Cmd").start();
        });
    }

    private void finishVoiceCommandTraining() {
        if (trainVosk != null) { trainVosk.close(); trainVosk = null; }
        voiceTrainStep.setText("\u2705 Training complete");
        voiceTrainPrompt.setText("IRIS learned your voice and " + learnedAliasCount
                + " command pronunciation" + (learnedAliasCount == 1 ? "" : "s") + ".");
        voiceTrainFeedback.setText("");
        toast("\uD83C\uDF93 Voice & commands trained \u2705");
        LogStore.append(this, "TRAIN", "Voice+command training done (" + learnedAliasCount + " aliases)");
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            if (resumeAfterWakeTraining) { resumeAfterWakeTraining = false; startListeningService(); }
            if (selectedTab == 1) showTraining();
        }, 1600);
    }

    private void finishVoiceTrainUi() {
        if (trainVosk != null) { trainVosk.close(); trainVosk = null; }
        if (resumeAfterWakeTraining) { resumeAfterWakeTraining = false; startListeningService(); }
        if (selectedTab == 1) showTraining();
    }

    private void cancelVoiceCommandTraining() {
        voiceTrainCancelled = true;
        if (timedRecorder != null) timedRecorder.stop();
        if (trainVosk != null) { trainVosk.close(); trainVosk = null; }
        toast("Training cancelled.");
        if (resumeAfterWakeTraining) { resumeAfterWakeTraining = false; startListeningService(); }
        if (selectedTab == 1) showTraining();
    }

    private void finishWakeTraining() {
        // Confirm before saving — let the user re-record if they're not happy.
        stopWakeTrainingEngine();
        if (timedRecorder != null) timedRecorder.stop();
        new AlertDialog.Builder(this)
                .setTitle("Save your wake phrase?")
                .setMessage("I captured \u201C" + wakePhraseBeingTrained + "\u201D with "
                        + wakeTemplates.size() + " samples.\n\nSave it, or re-record if that didn't feel right?")
                .setNegativeButton("Re-record", (d, w) -> {
                    wakeTemplates.clear();
                    wakeRawSamples.clear();
                    wakeSampleIndex = 0;
                    if (wakeWizardFeedback != null) wakeWizardFeedback.setText("Let's try again.");
                    handler.postDelayed(this::captureNextWakeSample, 400);
                })
                .setPositiveButton("Save", (d, w) -> finishWakeTrainingConfirmed())
                .setCancelable(false)
                .show();
    }

    private void finishWakeTrainingConfirmed() {
        // One last, longer sample of natural speech greatly improves the voiceprint.
        if (wakeWizardPrompt != null)
            wakeWizardPrompt.setText("\uD83C\uDF99  Last step — say a full sentence in your normal voice");
        if (wakeWizardFeedback != null)
            wakeWizardFeedback.setText("Recording 7 seconds… talk naturally (e.g. tell me about your day)");
        wakeTrainingStatus.setText("Learning your natural voice…");
        TimedRecorder longRec = new TimedRecorder();
        longRec.record(7000, new TimedRecorder.Listener() {
            @Override public void onLevel(float normalizedLevel) {
                if (wakeWizardFeedback != null) {
                    int bars = Math.round(normalizedLevel * 20);
                    StringBuilder bar = new StringBuilder();
                    for (int i = 0; i < 20; i++) bar.append(i < bars ? "\u2593" : "\u2591");
                    wakeWizardFeedback.setText("\uD83C\uDF99 " + bar);
                }
            }
            @Override public void onComplete(short[] audio) {
                if (audio != null && audio.length > 16000) wakeRawSamples.add(audio);
                finalizeWakeTraining();
            }
            @Override public void onError(String message) { finalizeWakeTraining(); }
        });
    }

    private void finalizeWakeTraining() {
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
        TrainingProgress.clear(this);   // training complete — no partial to resume
        if (!wakeRawSamples.isEmpty()) {
            enrollVoiceprintAsync(new java.util.ArrayList<>(wakeRawSamples));  // restarts service when done
        } else {
            enrollStatus = "voice not enrolled (no samples)";
            resumeListeningAfterTraining();   // nothing to enroll — safe to restart now
        }
        ProfileStore.WakeProfile saved = new ProfileStore(this).getWakeProfile();
        LogStore.append(this, "WAKE TRAINED", saved.phrase + " with " + dtwTemplates.size()
                + " acoustic templates, " + enrollStatus);
        wakeTrainingStatus.setText("✅  “" + saved.phrase + "” • "
                + dtwTemplates.size() + " templates • " + enrollStatus);
        trainWakeButton.setText("\uD83D\uDD04  Retrain");
        trainWakeButton.setEnabled(true);
        trainWakeButton.setOnClickListener(v -> authenticateThen("Train owner voice", this::beginWakeTraining));
        testWakeButton.setEnabled(true);
        if (wakeNormalState != null) wakeNormalState.setVisibility(View.VISIBLE);
        if (wakeWizardState != null) wakeWizardState.setVisibility(View.GONE);
        stopWakeTrainingEngine();
        toast("Phrase saved. Wait for owner enrollment to complete, then use Test wake phrase.");
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
    }

    private VoskEngine wakeTestEngine;
    private void testWakePhrase() {
        ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
        if (!wake.isReady() || !WakePolicy.owner(wake.voiceprint, wake.voiceprint, .99)) {
            toast("Enroll your voice before testing."); return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            maybeOpenAppSettings(Manifest.permission.RECORD_AUDIO, "Microphone"); return;
        }
        if (IrisListeningService.isRunning) stopListeningService();
        stopWakeTrainingEngine();
        final VoskEngine engine = new VoskEngine();
        wakeTestEngine = engine;
        wakeTrainingStatus.setText("Preparing the same offline detector used by background listening…");
        engine.init(this, new VoskEngine.InitListener() {
            @Override public void onReady() {
                if (wakeTestEngine != engine) { engine.close(); return; }
                engine.initSpeaker(MainActivity.this);
                final long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (wakeTestEngine != engine) return;
                        if (!engine.isSpeakerReady()) {
                            if (android.os.SystemClock.elapsedRealtime() < deadline) handler.postDelayed(this, 500);
                            else { stopWakeTrainingEngine(); wakeTrainingStatus.setText("Offline speaker model unavailable. Try again after downloading it."); }
                            return;
                        }
                        android.media.AudioManager am = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
                        if (am != null && am.isMusicActive()) {
                            stopWakeTrainingEngine(); wakeTrainingStatus.setText("Pause media before testing, just as in background wake."); return;
                        }
                        wakeTrainingStatus.setText("Say the complete phrase: “" + wake.phrase + "”");
                        engine.startWakeDetection(wake.allPhrases(), new VoskEngine.WakeListener() {
                            @Override public void onWakeDetected(float[] embedding) {
                                if (wakeTestEngine != engine) return;
                                boolean accepted = (am == null || !am.isMusicActive()) && WakePolicy.owner(embedding,
                                        wake.voiceprint, WakePolicy.threshold(new AppSettings(MainActivity.this).voiceSensitivity()));
                                stopWakeTrainingEngine();
                                wakeTrainingStatus.setText(accepted ? "Full phrase and owner verified. Test passed." : "Rejected: voice mismatch or media playing. Retrain if this was you.");
                            }
                            @Override public void onError(String message) {
                                if (wakeTestEngine != engine) return;
                                stopWakeTrainingEngine(); wakeTrainingStatus.setText("Test unavailable: " + message);
                            }
                        });
                        handler.postDelayed(() -> {
                            if (wakeTestEngine == engine) { stopWakeTrainingEngine(); wakeTrainingStatus.setText("No verified wake in 15 seconds. Say the complete phrase or retrain."); }
                        }, 15000);
                    }
                });
            }
            @Override public void onError(String message) {
                if (wakeTestEngine != engine) return;
                stopWakeTrainingEngine(); wakeTrainingStatus.setText("Test unavailable: " + message);
            }
        });
    }

    private void stopWakeTrainingEngine() {
        if (wakeTestEngine != null) { VoskEngine old = wakeTestEngine; wakeTestEngine = null; old.close(); }
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
        configureWakeButton();
        ProfileStore.WakeProfile wake = new ProfileStore(this).getWakeProfile();
        testWakeButton.setEnabled(wake.isReady());
        int saved = TrainingProgress.peekIndex(this);
        if (saved > 0) {
            wakeTrainingStatus.setText("\u23F8 Paused at " + saved + "/5 \u2014 tap Resume to continue.");
            toast("Paused \u2014 resume anytime from Training.");
        }
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
        final String said = heard.trim();
        new AlertDialog.Builder(this).setTitle("Teach IRIS what you meant?")
                .setMessage("IRIS heard:\n\u201C" + said + "\u201D\n\nWhat should it have been?")
                .setNeutralButton("Not now", null)
                .setNegativeButton("It's a contact", (d, w) -> {
                    correctionPhrase = said;
                    requestContactForCorrection();
                })
                .setPositiveButton("Fix the words", (d, w) -> showHeardMeantCard(said))
                .show();
    }

    /** "IRIS heard / I meant" — the Phase 1 correction card. Learns a local, conservative fix. */
    private void showHeardMeantCard(String heard) {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * d);
        col.setPadding(pad, (int) (8 * d), pad, 0);

        TextView label = new TextView(this);
        label.setText("IRIS heard");
        label.setTextColor(getColor(R.color.text_muted));
        label.setTextSize(12f);
        col.addView(label);

        TextView heardView = new TextView(this);
        heardView.setText("\u201C" + (heard == null ? "" : heard) + "\u201D");
        heardView.setTextColor(getColor(R.color.magenta));
        heardView.setTextSize(15f);
        heardView.setTypeface(null, Typeface.BOLD);
        col.addView(heardView);

        TextView label2 = new TextView(this);
        label2.setText("I meant");
        label2.setTextColor(getColor(R.color.text_muted));
        label2.setTextSize(12f);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = (int) (12 * d);
        label2.setLayoutParams(lp2);
        col.addView(label2);

        final EditText input = new EditText(this);
        input.setHint("Type what you actually said");
        input.setText(heard == null ? "" : heard);
        input.setTextColor(getColor(R.color.text_primary));
        input.setTextSize(15f);
        col.addView(input);

        TextView note = new TextView(this);
        note.setText("Saved only on this phone. IRIS applies it when the whole phrase matches, and "
                + "learns name pronunciations \u2014 it never rewrites your dictated messages.");
        note.setTextColor(getColor(R.color.text_muted));
        note.setTextSize(11.5f);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = (int) (10 * d);
        note.setLayoutParams(lp3);
        col.addView(note);

        new AlertDialog.Builder(this).setTitle("Fix what IRIS misheard")
                .setView(col)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Learn it", (dialog, w) -> {
                    String meant = input.getText() == null ? "" : input.getText().toString().trim();
                    if (meant.isEmpty() || heard == null) { toast("Nothing to learn."); return; }
                    PersonalVocabulary vocab = new PersonalVocabulary(this);
                    boolean ok = vocab.addCorrection(heard, meant);
                    // If exactly one word differs, also learn it as a name/word variant.
                    String[] hw = heard.trim().split("\\s+"), mw = meant.split("\\s+");
                    if (hw.length == mw.length) {
                        for (int i = 0; i < hw.length; i++) {
                            if (!hw[i].equalsIgnoreCase(mw[i])) vocab.addNameVariant(mw[i], hw[i]);
                        }
                    }
                    if (ok) new RecognitionStats(this).recordCorrected();
                    LogStore.append(this, "CORRECTION", "\u201C" + heard + "\u201D \u2192 \u201C" + meant + "\u201D");
                    toast(ok ? "Learned. IRIS will read that as \u201C" + meant + "\u201D."
                             : "That's already what IRIS understood.");
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
        String act = intent.getAction();
        if (Intent.ACTION_ASSIST.equals(act) || "android.intent.action.VOICE_COMMAND".equals(act)) {
            intent.setAction(null);
            Intent talk = new Intent(this, IrisListeningService.class).setAction(IrisListeningService.ACTION_TALK);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(talk); else startService(talk);
            } catch (Exception ignored) { }
            return;
        }
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
            String[] f = getAssets().list("model-en-in");
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
            else maybeOpenAppSettings(Manifest.permission.RECORD_AUDIO, "Microphone");
        } else if (requestCode == PERMISSION_TRAIN) {
            String kind = pendingTrainingKind;
            pendingTrainingKind = "";
            if ("wake".equals(kind) && hasPermission(Manifest.permission.RECORD_AUDIO)) beginWakeTraining();
            else if ("contact".equals(kind) && hasPermission(Manifest.permission.RECORD_AUDIO)
                    && hasPermission(Manifest.permission.READ_CONTACTS)) launchContactPicker();
            else if ("correction".equals(kind) && hasPermission(Manifest.permission.READ_CONTACTS)) launchContactPicker();
            else if ("dry".equals(kind) && hasPermission(Manifest.permission.RECORD_AUDIO)) testTrainedCommand();
            else if ("voice".equals(kind) && hasPermission(Manifest.permission.RECORD_AUDIO)) beginVoiceCommandTraining();
            else if (!hasPermission(Manifest.permission.RECORD_AUDIO))
                maybeOpenAppSettings(Manifest.permission.RECORD_AUDIO, "Microphone");
            else if (!hasPermission(Manifest.permission.READ_CONTACTS))
                maybeOpenAppSettings(Manifest.permission.READ_CONTACTS, "Contacts");
        }
    }

    /** When a runtime permission is denied: if the OS will still show the dialog, prompt the user
     *  to retry; if it's blocked ("Don't ask again"), send them straight to the app's Settings page. */
    private void maybeOpenAppSettings(String permission, String label) {
        if (shouldShowRequestPermissionRationale(permission)) {
            toast(label + " permission is needed for this — please allow it and try again.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(label + " permission blocked")
                .setMessage("Android won't show the permission popup anymore, so it can't be granted from here.\n\n"
                        + "Tap Open Settings → Permissions → " + label + " → Allow, then try again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open Settings", (d, w) -> {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Open Settings → Apps → IRIS → Permissions and allow " + label + ".");
                    }
                }).show();
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

