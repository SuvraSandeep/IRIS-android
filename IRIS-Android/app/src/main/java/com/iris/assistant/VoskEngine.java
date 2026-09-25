package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.SpeakerModel;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

import org.vosk.android.StorageService;

import java.io.File;

/**
 * Vosk-based voice engine — the robust, offline core of IRIS.
 *
 * Provides:
 *  - Continuous wake-word detection via grammar-constrained recognition
 *    (only accepts the trained phrase; random noise scores as [unk])
 *  - Continuous speech-to-text for commands
 *
 * 100% offline, 100% free (Apache 2.0). Model bundled in assets/model-en-in.
 */
public final class VoskEngine {
    private static final float SAMPLE_RATE = 16_000f;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final String MODEL_DIR_NAME = "vosk-model-en-in-0.4";
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip";
    // Optional high-accuracy model (~1GB), downloaded on first use when the user opts in.
    private static final String LARGE_DIR_NAME = "vosk-model-en-in-0.5";
    private static final String LARGE_URL =
            "https://alphacephei.com/vosk/models/vosk-model-en-in-0.5.zip";
    private static final String SPK_DIR_NAME = "vosk-model-spk-0.4";
    private static final String SPK_URL =
            "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip";

    static final String OWNER_DECODER="vosk-model-small-en-us-0.15";
    private static final Object OWNER_MODEL_INSTALL=new Object();
    private volatile boolean closed;
    private Context captureContext;
    private Model model;
    private volatile boolean modelLoaded;
    private SpeakerModel spkModel; // Required API: checked against the packaged dependency at build time.
    private volatile boolean spkReady;
    private volatile String speakerHash="";
    public String speakerFingerprint(){return speakerHash;}
    private volatile String speakerError="";
    public String speakerLoadError(){return speakerError;}
    // Guards against a second initSpeaker() call starting a concurrent load thread before the
    // first finishes — without this, two overlapping calls could both pass "if (spkReady) return"
    // and both delete+re-extract SPK_DIR at once, corrupting the on-disk speaker model.
    private static final Object SPEAKER_INSTALL_LOCK = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean spkLoading = new java.util.concurrent.atomic.AtomicBoolean();
    private ManagedSpeechService speechService;
    // All mutable engine state that's read/written from both the calling thread (UI/service) and
    // the Vosk recognition callback thread must go through this lock — startWakeDetection/stop/
    // startListening were previously unsynchronized, so two overlapping calls (e.g. the Test Wake
    // screen and the live service both arming at once) could both pass the generation check and
    // both fire, since `fired` is a new AtomicBoolean per call and doesn't dedupe across calls.
    private final Object stateLock = new Object();

    public interface InitListener {
        void onReady();
        void onError(String message);
    }

    public interface WakeListener {
        /** @param ecapaEmbedding Dedicated ECAPA-TDNN speaker embedding (192-dim) for the wake
         *   utterance, or null if unavailable (model not loaded, or speaker verification off).
         *  @param voskEmbedding Vosk speaker x-vector (128-dim) for the same utterance, or null
         *   if unavailable. Both may be null when speaker verification is off (phrase-only wake). */
        void onWakeDetected(float[] ecapaEmbedding, float[] voskEmbedding);
        default void onRejected(String reason) { }
        void onError(String message);
    }

    public interface SttListener {
        default void onReady(){}
        default void onUnclear(String text){onError("Unclear speech");}
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
    }

    /** Load the Vosk model: bundled assets first, else download at runtime. */
    public void initOwner(Context context,InitListener listener) {
        if(ecapaEngine==null){ecapaEngine=new EcapaEmbedding();ecapaEngine.load(context,new EcapaEmbedding.InitListener(){
            public void onReady(){ecapaError="";}public void onError(String message){ecapaError=message;}
        });}loadOwnerEnglish(context,listener);
    }
    private volatile String ecapaError="";
    boolean ecapaReady(){return ecapaEngine!=null&&ecapaEngine.isReady();}
    String ecapaError(){return ecapaError;}
    String ecapaFingerprint(){return ecapaEngine==null?"":ecapaEngine.fingerprint();}
    boolean profileModelMatches(OwnerVoiceProfile p){return p.hash().equals(speakerFingerprint())&&(!p.usesEcapa()||(ecapaReady()&&p.data.optString("ecapaModelHash").equals(ecapaFingerprint())));}

    private void loadOwnerEnglish(Context context,InitListener listener){
        captureContext=context.getApplicationContext();
        if(closed){listener.onError("Voice engine is closed");return;}
        if(modelLoaded){main.post(listener::onReady);return;}
        new Thread(()->{
            File target=new File(captureContext.getFilesDir(),OWNER_DECODER);
            try{
                synchronized(OWNER_MODEL_INSTALL){
                    if(closed)throw new java.io.IOException("Training was cancelled");
                    if(!isValidModelDir(target)){
                        File stage=new File(captureContext.getFilesDir(),"owner-english-stage");deleteRecursive(stage);
                        boolean bundled=false;try{bundled=unzipAsset(captureContext,"model-en-us.zip",stage);}catch(Exception ignored){}
                        if(!bundled){
                            File zip=new File(captureContext.getCacheDir(),"owner-english.zip");
                            try{downloadFile("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",zip);deleteRecursive(stage);unzip(zip,stage);}finally{zip.delete();}
                        }
                        File[] kids=stage.listFiles();File src=kids!=null&&kids.length==1&&kids[0].isDirectory()?kids[0]:stage;
                        if(!isValidModelDir(src))throw new java.io.IOException("English voice pack is incomplete");
                        deleteRecursive(target);if(!src.renameTo(target))throw new java.io.IOException("Cannot install English voice pack");deleteRecursive(stage);
                    }
                }
                if(!installModel(new Model(target.getAbsolutePath())))throw new java.io.IOException("Training was cancelled");
                main.post(listener::onReady);
            }catch(Exception error){main.post(()->listener.onError("English voice pack: "+error.getMessage()));}
        },"IRIS-OwnerEnglish").start();
    }
    public void init(Context context, InitListener listener) {
        if(closed){listener.onError("Voice engine is closed");return;}
        captureContext=context.getApplicationContext();
        if (modelLoaded) { main.post(listener::onReady); return; }
        Context app = context.getApplicationContext();
        // High-accuracy path (opt-in): use the large en-IN model, downloaded on first use.
        // Skipped entirely under IRIS battery saver — the ~1GB model stays resident the whole
        // time IRIS listens, which is exactly the RAM cost battery saver exists to avoid.
        boolean large = false;
        try { large = new AppSettings(app).highAccuracyVoice() && !new AppSettings(app).irisPowerSaver(); } catch (Throwable ignored) { }
        if (large) {
            File lg = new File(app.getFilesDir(), LARGE_DIR_NAME);
            if (isValidModelDir(lg)) { loadFromPath(lg.getAbsolutePath(), listener); return; }
            downloadAndLoadLarge(app, listener);
            return;
        }
        // 0. If we've already downloaded/extracted the model before, load it directly.
        File extracted = new File(app.getFilesDir(), MODEL_DIR_NAME);
        if (isValidModelDir(extracted)) {
            loadFromPath(extracted.getAbsolutePath(), listener);
            return;
        }
        loadBundledIndianOrDownload(app, listener);
    }

    /** The upstream ZIP has no StorageService uuid asset. Unpack the bundled zip asset (never
     * loose nested asset files — see unzipAsset()) into a staged Indian-only directory. */
    private void loadBundledIndianOrDownload(Context app, InitListener listener) {
        new Thread(() -> {
            File staging = new File(app.getFilesDir(), "vosk-indian-bundle-staging");
            File target = new File(app.getFilesDir(), MODEL_DIR_NAME);
            try {
                deleteRecursive(staging);
                boolean present;
                try { present = unzipAsset(app, "model-en-in.zip", staging); }
                catch (Throwable t) { present = false; }
                if (!present) { deleteRecursive(staging); downloadAndLoad(app, listener); return; }
                // The upstream archive has one top-level folder (e.g. vosk-model-small-en-in-0.4/);
                // unwrap it so staging itself is the model root, same as the download path below.
                File[] kids = staging.listFiles();
                File modelRoot = (kids != null && kids.length == 1 && kids[0].isDirectory()) ? kids[0] : staging;
                if (!isValidModelDir(modelRoot)) throw new java.io.IOException("Incomplete Indian voice bundle");
                if (!isValidModelDir(target)) {
                    deleteRecursive(target);
                    if (!modelRoot.renameTo(target)) throw new java.io.IOException("Could not install Indian voice bundle");
                }
                deleteRecursive(staging);
                // installModel() returns false only when this engine was close()d while this
                // background load was still in flight (e.g. training UI torn down/recreated
                // mid-load). Previously this silently returned with NO callback at all, leaving
                // any caller still waiting on init() (like the wake-training wizard) stuck on
                // "Preparing speech model" forever — no error, no timeout recovery, no retry.
                if(!installModel(new Model(target.getAbsolutePath()))){
                    main.post(()->listener.onError("Voice engine was closed before the model finished loading."));
                    return;
                }
                main.post(listener::onReady);
            } catch (Throwable t) {
                deleteRecursive(staging);
                android.util.Log.w("IRIS", "Indian voice bundle unavailable: " + t.getMessage());
                downloadAndLoad(app, listener);
            }
        }, "IRIS-IndianVoice-Install").start();
    }

    private void loadFromPath(String path, InitListener listener) {
        new Thread(() -> {
            try {
                // See the identical fix in loadBundledIndianOrDownload above: installModel()
                // returning false (engine closed mid-load) must never be a silent no-op.
                if(!installModel(new Model(path))){
                    main.post(()->listener.onError("Voice engine was closed before the model finished loading."));
                    return;
                }
                main.post(listener::onReady);
            } catch (Throwable t) {
                modelLoaded = false;
                main.post(() -> listener.onError(t.getMessage()));
            }
        }, "Vosk-Load").start();
    }

    /** Download the small English model (~40 MB) and load it. One-time, then offline. */
    private void downloadAndLoad(Context context, InitListener listener) {
        new Thread(() -> {
            File zip = new File(context.getCacheDir(), "vosk-model.zip");
            try {
                File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
                if (!isValidModelDir(modelDir)) {
                    android.util.Log.i("IRIS", "Downloading Vosk model…");
                    downloadFile(MODEL_URL, zip);
                    File tmp = new File(context.getFilesDir(), "vosk-tmp");
                    deleteRecursive(tmp);
                    unzip(zip, tmp);
                    // The zip contains a single top-level folder; move it to modelDir
                    File[] children = tmp.listFiles();
                    File src = (children != null && children.length == 1 && children[0].isDirectory())
                            ? children[0] : tmp;
                    deleteRecursive(modelDir);
                    if (!src.renameTo(modelDir)) copyRecursive(src, modelDir);
                    deleteRecursive(tmp);
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                }
                if(!installModel(new Model(modelDir.getAbsolutePath()))){
                    main.post(()->listener.onError("Voice engine was closed before the model finished loading."));
                    return;
                }
                android.util.Log.i("IRIS", "Vosk model ready (downloaded)");
                main.post(listener::onReady);
            } catch (Throwable t) {
                // A partial/corrupt download must not sit in cache forever — the next retry
                // reuses the same cache path, so a leftover truncated zip either gets silently
                // re-extracted (masking the real failure) or wastes disk indefinitely.
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
                modelLoaded = false;
                android.util.Log.e("IRIS", "Vosk model download/load failed: " + t.getMessage());
                main.post(() -> listener.onError(t.getMessage()));
            }
        }, "Vosk-Download").start();
    }

    // A real Vosk acoustic model file (am/final.mdl) is always several hundred KB at minimum —
    // a corrupted/truncated download (e.g. connection dropped mid-transfer) still produces a
    // nonzero-length file that a simple length>0 check would wrongly accept, only to fail later
    // at Model construction with a cryptic native error the user never sees. This minimum is
    // comfortably below any real model's size but well above what a truncated download leaves.
    private static final long MIN_PLAUSIBLE_MODEL_FILE_BYTES = 50_000;

    private static boolean isValidModelDir(File dir) {
        return dir.isDirectory() && new File(dir, "am/final.mdl").length() > MIN_PLAUSIBLE_MODEL_FILE_BYTES
                && new File(dir, "conf/model.conf").length() > 0
                && new File(dir, "graph").isDirectory();
    }

    /** Download the large en-IN model (~1GB) and load it; fall back to the small model on any failure. */
    private void downloadAndLoadLarge(Context context, InitListener listener) {
        new Thread(() -> {
            File zip = new File(context.getCacheDir(), "vosk-large.zip");
            try {
                File modelDir = new File(context.getFilesDir(), LARGE_DIR_NAME);
                if (!isValidModelDir(modelDir)) {
                    android.util.Log.i("IRIS", "Downloading large Vosk model (~1GB, one-time)…");
                    downloadFile(LARGE_URL, zip);
                    File tmp = new File(context.getFilesDir(), "vosk-large-tmp");
                    deleteRecursive(tmp);
                    unzip(zip, tmp);
                    File[] children = tmp.listFiles();
                    File src = (children != null && children.length == 1 && children[0].isDirectory())
                            ? children[0] : tmp;
                    deleteRecursive(modelDir);
                    if (!src.renameTo(modelDir)) copyRecursive(src, modelDir);
                    deleteRecursive(tmp);
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                }
                if(!installModel(new Model(modelDir.getAbsolutePath()))){
                    main.post(()->listener.onError("Voice engine was closed before the model finished loading."));
                    return;
                }
                android.util.Log.i("IRIS", "Large Vosk model ready");
                main.post(listener::onReady);
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
                android.util.Log.w("IRIS", "Large model unavailable, falling back to small: " + t.getMessage());
                main.post(() -> fallbackSmall(context, listener));
            }
        }, "Vosk-Large-Download").start();
    }

    /** Load the bundled/small model (used as the automatic fallback). */
    private void fallbackSmall(Context app, InitListener listener) {
        File extracted = new File(app.getFilesDir(), MODEL_DIR_NAME);
        if (isValidModelDir(extracted)) { loadFromPath(extracted.getAbsolutePath(), listener); return; }
        loadBundledIndianOrDownload(app, listener);
    }

    private static void downloadFile(String url, File dest) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        if (conn.getResponseCode() / 100 != 2) throw new Exception("HTTP " + conn.getResponseCode());
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private static void unzip(File zip, File targetDir) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        targetDir.mkdirs();
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
            unzipEntries(zis, targetDir);
        }
    }

    // Unpacks a bundled asset zip (e.g. "model-en-in.zip") straight from AssetManager.open(),
    // never via AssetManager.list(). Listing compressed nested asset directories is unreliable
    // on-device (can silently return null/incomplete entries), which previously produced
    // corrupt, partially-copied model directories that crashed the native Vosk model loader.
    // A single zip asset read through a stream has no such failure mode.
    private static boolean unzipAsset(Context c, String assetName, File targetDir) throws Exception {
        try (java.io.InputStream raw = c.getAssets().open(assetName)) {
            //noinspection ResultOfMethodCallIgnored
            targetDir.mkdirs();
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw))) {
                unzipEntries(zis, targetDir);
            }
            return true;
        } catch (java.io.FileNotFoundException absent) {
            return false;
        }
    }

    private static void unzipEntries(java.util.zip.ZipInputStream zis, File targetDir) throws Exception {
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(targetDir, entry.getName());
            // Zip-slip guard
            if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)) continue;
            if (entry.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.mkdirs();
            } else {
                //noinspection ResultOfMethodCallIgnored
                outFile.getParentFile().mkdirs();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
            zis.closeEntry();
        }
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()));
        } else {
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public boolean isReady() { return !closed && modelLoaded && model != null; }

    /**
     * Start continuous wake-word detection.
     * Uses a grammar limited to the wake phrase, so only that phrase
     * (spoken as actual speech) triggers detection.
     */
    public void startWakeDetection(String phrase, WakeListener listener) {
        startWakeDetection(java.util.Collections.singletonList(phrase), listener);
    }

    /** Minimum per-word confidence to accept a wake phrase, scaled by sensitivity (0..1).
     *  0.85 was tuned for normal-volume speech and reliably rejected whispers and quiet rooms —
     *  Vosk's confidence score tracks acoustic clarity, so quiet speech legitimately scores
     *  lower even when the words are heard correctly. Sensitivity 0.5 (default) now needs 0.55,
     *  not 0.85; turning sensitivity up relaxes it further for whisper-level detection.
     *  NOTE — this is deliberately a SEPARATE formula from WakePolicy.threshold(), which scales
     *  the same voiceSensitivity() setting for the owner-voice cosine match instead of phrase
     *  confidence: this field goes 0.65 (low sensitivity) DOWN to 0.30 (max, easier to pass),
     *  and WakePolicy.threshold() goes 0.85 (low sensitivity) DOWN to 0.65 (max, also easier to
     *  pass) — both formulas now descend the same direction as sensitivity rises, they just use
     *  different numeric bounds because cosine similarity and word confidence aren't the same
     *  scale. Both move sensitivity in the same "easier to wake at max" direction; see
     *  WakePolicy.threshold()'s doc comment for the full cross-reference. */
    private volatile long wakeGeneration;
    private double minWordConfidence = 0.55;
    public void setSensitivity(float sensitivity) {
        float s = Math.max(0f, Math.min(1f, sensitivity));
        minWordConfidence = 0.65 - s * 0.35;   // 0.65 (low sensitivity) down to 0.30 (max)
    }
    public void startWakeDetection(java.util.List<String> phrases, WakeListener listener) {
        startWakeDetection(phrases, listener, true);
    }
    /**
     * @param requireSpeakerModel When false, wake fires on the phrase alone: no speaker model
     *   is attached to the recognizer, and the spk_frames gate below is skipped. This is what
     *   makes the "phrase alone is enough" design (8.4.0) actually true end-to-end — previously
     *   this method unconditionally required isSpeakerReady() and unconditionally attached the
     *   speaker model, so wake could never fire without it even when the caller (and the user's
     *   own settings) never asked for voice verification at all.
     */
    public void startWakeDetection(java.util.List<String> phrases, WakeListener listener, boolean requireSpeakerModel) {
        startWakeDetection(phrases, listener, requireSpeakerModel, true);
    }
    /**
     * @param allowBluetooth false for always-on wake listening — forcing Bluetooth SCO the
     *   instant this starts drops a connected headset's music from full A2DP quality to
     *   call-quality narrowband the whole time IRIS is merely awaiting the wake phrase, which is
     *   the actual mechanism behind "music sounds bad while IRIS is awake" reports. True for a
     *   short, explicit session (a manual "test my voice" check, training) where honoring the
     *   user's actual microphone preference is expected and there's no ongoing playback concern.
     */
    public void startWakeDetection(java.util.List<String> phrases, WakeListener listener, boolean requireSpeakerModel, boolean allowBluetooth) {
        if(!isReady()||!isSpeakerReady()){listener.onError("Offline owner model not ready");return;}
        synchronized(stateLock){
            stop();
            final long generation=wakeGeneration;
            final OwnerVoiceProfile profile=new ProfileStore(captureContext).ownerEvidence();
            if(profile==null){listener.onError("Record your phrase again for this version; existing profile has no compatible sound evidence");return;}
            if(!profileModelMatches(profile)){listener.onError("Owner model changed; retrain your phrase");return;}
            final WakeAnalysisQueue analyses=new WakeAnalysisQueue();
            wakeAnalyses=analyses;
            final java.util.concurrent.atomic.AtomicBoolean fired=new java.util.concurrent.atomic.AtomicBoolean();
            try{
                Recognizer rec=new Recognizer(model,SAMPLE_RATE);
                ManagedSpeechService capture=new ManagedSpeechService(captureContext,rec,SAMPLE_RATE,allowBluetooth);
                capture.setClipListener((pcm,route)->{
                    if(generation!=wakeGeneration||fired.get()){java.util.Arrays.fill(pcm,(short)0);return;}
                    analyses.offer(pcm,()->{
                        String failure="",diagnostic="Input "+route;float[] vector=null,ecapaVector=null;float[][] pattern=null;
                        try{
                            synchronized(VoskEngine.this){
                                if(generation!=wakeGeneration||fired.get())return;
                                if(route==AudioRouteController.Route.UNCONFIRMED)failure="INPUT_UNCONFIRMED";
                                else if(route==AudioRouteController.Route.HEADSET&&profile.headset==null)failure="HEADSET_PROFILE_REQUIRED";
                                else {
                                    pattern=SoundPattern.extract(pcm);
                                    vector=embedRecorded(pcm);if(profile.usesEcapa())ecapaVector=embedEcapa(pcm);
                                    boolean headset=route==AudioRouteController.Route.HEADSET;
                                    RecordedPhrase phrase=headset?profile.headset.phraseEvidence:profile.phraseEvidence;
                                    float[] centroid=headset?profile.headset.voskCentroid():profile.voskCentroid();
                                    double policy=Math.max(profile.threshold(),new AppSettings(captureContext).ownerThreshold());
                                    failure=RecordedWakeCheck.rejectEnsemble(pattern,phrase.accepts(pattern),ecapaVector,vector,headset?profile.headset.ecapaCentroid():profile.ecapaCentroid(),centroid,policy);
                                    diagnostic="Input "+route+"; "+(phrase.variants()?RecordedWakeCheck.variantDiagnostic(pattern,phrase.samples,phrase.threshold,vector,centroid,policy):RecordedWakeCheck.diagnostic(pattern,phrase.samples,phrase.threshold,vector,centroid,policy));
                                    if(failure.isEmpty()&&!(headset?profile.acceptsHeadset(ecapaVector,vector,policy):profile.accepts(ecapaVector,vector,policy)))failure="OWNER_REJECTED";
                                }
                            }
                        }catch(Throwable error){failure="ANALYSIS_ERROR";}
                        finally{java.util.Arrays.fill(pcm,(short)0);}
                        final String reason=failure,detail=diagnostic;final float[] embedding=vector,ecapaEmbedding=ecapaVector;final float[][] capturedPattern=pattern;
                        main.post(()->{
                            if(generation!=wakeGeneration||fired.get())return;
                            if(!profile.revision().equals(new ProfileStore(captureContext).ownerRevision())){listener.onError("Profile changed; restarting wake");return;}
                            lastWakeDiagnostic=detail;
                            lastWakeRoute=route;
                            lastWakeEvent=WakeEventStore.add(reason.isEmpty()?"MATCHED":reason,profile.revision(),ecapaEmbedding,embedding,false,capturedPattern,route);
                            if(!reason.isEmpty()){listener.onRejected(reason);return;}
                            lastWakeRoute=route;
                            if(fired.compareAndSet(false,true))listener.onWakeDetected(ecapaEmbedding,embedding);
                        });
                    });
                });
                speechService=capture;
                capture.startListening(new RecognitionListener(){
                    public void onPartialResult(String result){} public void onResult(String result){} public void onFinalResult(String result){}
                    public void onTimeout(){}
                    public void onError(Exception error){if(generation==wakeGeneration)listener.onError(error.getMessage());}
                });
            }catch(Exception error){analyses.close();listener.onError(error.getMessage());}
        }
    }
    private WakeAnalysisQueue wakeAnalyses;
    private WakeEventStore.Event lastWakeEvent;
    void recordWakeOutcome(String reason,boolean accepted){WakeEventStore.outcome(lastWakeEvent,reason,accepted);}
    private volatile String lastWakeDiagnostic="No completed wake check";
    String lastWakeDiagnostic(){return lastWakeDiagnostic;}
    private volatile AudioRouteController.Route lastWakeRoute=AudioRouteController.Route.UNCONFIRMED;
    AudioRouteController.Route lastWakeRoute(){return lastWakeRoute;}
    /** Fixed preprocessing for schema 8. Optional models cannot change enrollment/live behavior. */
    float[] embedEcapa(short[] pcm){return ecapaEngine!=null?ecapaEngine.extract(QuietAudioProcessor.prepare(SoundPattern.speakerClip(pcm))):null;}
    float[] embedRecorded(short[] pcm){return embed(QuietAudioProcessor.prepare(SoundPattern.speakerClip(pcm)));}
    /** Dedicated ECAPA-TDNN speaker-embedding model — the PRIMARY identity signal (see
     *  WakePolicy.finalScore()). Lazily attached by the caller (IrisListeningService), which
     *  owns the model's lifecycle; may be null (e.g. not yet loaded, or the ONNX model file
     *  isn't sourced yet — see EcapaEmbedding's known blocker doc) in which case wake detection
     *  gracefully degrades to Vosk's x-vector alone rather than failing outright. */
    private EcapaEmbedding ecapaEngine;
    private SileroVad vadEngine;
    public void attachEnsembleModels(EcapaEmbedding ecapa, SileroVad vad) { this.ecapaEngine = ecapa; this.vadEngine = vad; }

    /**
     * Start continuous speech-to-text for command recognition.
     * Uses the full vocabulary model.
     */
    public void startListening(SttListener listener) {
        if (!isReady()) { listener.onError("Voice model not ready"); return; }
        synchronized (stateLock) {
        stop();
        try {
            Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);
            recognizer.setWords(true);
            speechService = new ManagedSpeechService(captureContext,recognizer, SAMPLE_RATE);
            speechService.setReadyListener(listener::onReady);
            speechService.startListening(new RecognitionListener() {
                @Override public void onPartialResult(String hypothesis) {
                    String text = extractText(hypothesis, "partial");
                    if (!text.isEmpty()) listener.onPartial(text);
                }
                @Override public void onResult(String hypothesis) {
                    String text = extractText(hypothesis, "text");
                    if (!text.isEmpty()){if(CommandEvidence.clear(hypothesis))listener.onFinal(text);else listener.onUnclear(text);}
                }
                @Override public void onFinalResult(String hypothesis) {
                    String text = extractText(hypothesis, "text");
                    if (!text.isEmpty()){if(CommandEvidence.clear(hypothesis))listener.onFinal(text);else listener.onUnclear(text);}
                }
                @Override public void onError(Exception e) {
                    listener.onError(e.getMessage());
                }
                @Override public void onTimeout() { }
            });
        } catch (Exception e) {
            listener.onError(e.getMessage());
        }
        }
    }

    final class Decoder implements AutoCloseable {
        final Recognizer recognizer;private boolean released;
        Decoder(Recognizer r){recognizer=r;}
        public void close(){synchronized(stateLock){if(released)return;released=true;try{recognizer.close();}finally{externalDecoders--;stateLock.notifyAll();}}}
    }
    private int externalDecoders;
    Decoder decoder()throws Exception {synchronized(stateLock){if(!isReady())throw new IllegalStateException("Command model not ready");Recognizer r=new Recognizer(model,SAMPLE_RATE);r.setWords(true);externalDecoders++;return new Decoder(r);}}
    void streamingOutcome(OwnerVoiceProfile p,float[][] pattern,float[] ecapa,float[] vosk,AudioRouteController.Route route,boolean accepted,String reason,double distance){
        lastWakeRoute=route;lastWakeDiagnostic="Input "+route+"; streaming candidate="+distance+"; "+reason;
        lastWakeEvent=WakeEventStore.add(reason,p.revision(),ecapa,vosk,accepted,pattern,route);
    }
    /** Stop any active recognition. */
    private final java.util.List<ManagedSpeechService> retiringCaptures=new java.util.ArrayList<>();
    public void stop() {
        synchronized (stateLock) {
            wakeGeneration++;
            if(wakeAnalyses!=null){wakeAnalyses.close();wakeAnalyses=null;}
            if (speechService != null) {
                speechService.stop();retiringCaptures.removeIf(ManagedSpeechService::stopped);retiringCaptures.add(speechService);
                speechService = null;
            }
        }
    }

    /** Release all resources. */
    public void close() {
        final java.util.List<ManagedSpeechService> retiring;
        synchronized(stateLock){if(closed)return;closed=true;stop();retiring=new java.util.ArrayList<>(retiringCaptures);retiringCaptures.clear();}
        new Thread(()->{
            for(ManagedSpeechService capture:retiring)capture.awaitStopped();
            synchronized(stateLock){while(externalDecoders>0)try{stateLock.wait();}catch(InterruptedException ignored){}}
            synchronized(VoskEngine.this){synchronized(stateLock){
                if(model!=null){model.close();model=null;}
                if(spkModel!=null){spkModel.close();spkModel=null;}
                spkReady=false;modelLoaded=false;if(ecapaEngine!=null){ecapaEngine.close();ecapaEngine=null;}
            }}
        },"IRIS-VoiceCleanup").start();
    }
    private boolean installModel(Model candidate){
        synchronized(stateLock){if(closed){candidate.close();return false;}model=candidate;modelLoaded=true;return true;}
    }


    // ─── Speaker model (voice verification) ───

    public boolean isSpeakerReady() { return spkReady && spkModel != null; }

    /** Load the Vosk speaker model (bundled in assets/spk-model, else downloaded). Non-fatal. */
    public void initSpeaker(Context context) {
        if (closed || spkReady) return;
        speakerError="";
        // compareAndSet, not "if (spkLoading.get())" — the check-then-set must be atomic so two
        // threads calling initSpeaker() at nearly the same time can't both observe "not loading"
        // and both start a load thread, which would race on deleting/re-extracting SPK_DIR.
        if (!spkLoading.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            File zip = new File(app.getCacheDir(), "vosk-spk.zip");
            try {
              synchronized (SPEAKER_INSTALL_LOCK) {
                if (closed) return;
                File dir = new File(app.getFilesDir(), SPK_DIR_NAME);
                if (!isValidSpkDir(dir)) {
                    // Try the bundled asset zip "spk-model.zip" → unpack straight into files
                    // (never via AssetManager.list() on loose nested files — see unzipAsset()).
                    File spkStaging = new File(app.getFilesDir(), "vosk-spk-bundle-staging");
                    deleteRecursive(spkStaging);
                    boolean present;
                    try { present = unzipAsset(app, "spk-model.zip", spkStaging); }
                    catch (Throwable t) { present = false; }
                    if (present) {
                        File[] spkKids = spkStaging.listFiles();
                        File spkRoot = (spkKids != null && spkKids.length == 1 && spkKids[0].isDirectory()) ? spkKids[0] : spkStaging;
                        deleteRecursive(dir);
                        if (!spkRoot.renameTo(dir)) copyRecursive(spkRoot, dir);
                    }
                    deleteRecursive(spkStaging);
                }
                if (!isValidSpkDir(dir)) {
                    // Fall back to a one-time download.
                    downloadFile(SPK_URL, zip);
                    File tmp = new File(app.getFilesDir(), "spk-tmp");
                    deleteRecursive(tmp);
                    unzip(zip, tmp);
                    File[] kids = tmp.listFiles();
                    File src = (kids != null && kids.length == 1 && kids[0].isDirectory()) ? kids[0] : tmp;
                    deleteRecursive(dir);
                    if (!src.renameTo(dir)) copyRecursive(src, dir);
                    deleteRecursive(tmp);
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                }
                if (isValidSpkDir(dir)) {
                    java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                    for(String name:new String[]{"final.ext.raw","mfcc.conf","mean.vec","transform.mat"}) {
                        digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        try(java.io.InputStream in=new java.io.FileInputStream(new File(dir,name))){byte[] block=new byte[16384];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);}
                    }
                    StringBuilder fingerprint=new StringBuilder();for(byte b:digest.digest())fingerprint.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                    SpeakerModel candidate = new SpeakerModel(dir.getAbsolutePath());
                    synchronized(stateLock){
                        if(closed){candidate.close();return;}
                        spkModel=candidate;speakerHash=fingerprint.toString();spkReady=true;
                    }
                    android.util.Log.i("IRIS", "Vosk speaker model ready");
                }
              }
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
                spkReady = false;
                speakerError="Offline speaker model failed to load: "+t.getClass().getSimpleName()+". Check storage and model download availability.";
                android.util.Log.w("IRIS", "Speaker model unavailable (owner verification cannot proceed): " + t.getMessage());
            } finally {
                if(!closed&&!spkReady&&speakerError.isEmpty())speakerError="The speaker model is missing or incomplete. Check the model download and available storage.";
                spkLoading.set(false);
            }
        }, "Vosk-Spk-Load").start();
    }

    private static boolean isValidSpkDir(File dir) {
        return dir != null && new File(dir, "final.ext.raw").length() > MIN_PLAUSIBLE_MODEL_FILE_BYTES
                && new File(dir, "mfcc.conf").length() > 0
                && new File(dir, "mean.vec").length() > 0
                && new File(dir, "transform.mat").length() > 0;
    }

    /** Transcribe a PCM clip (16kHz mono) with the full vocabulary — used to learn how the
     *  user pronounces a command word. Returns lowercase text, or "" on failure. */
    public String transcribe(short[] pcm) {
        if(!isReady() || pcm==null || pcm.length<1600)return "";
        Recognizer rec=null;
        try {
            rec=new Recognizer(model,SAMPLE_RATE);StringBuilder text=new StringBuilder();
            for(int offset=0;offset<pcm.length;offset+=1600){
                short[] part=java.util.Arrays.copyOfRange(pcm,offset,Math.min(pcm.length,offset+1600));
                if(rec.acceptWaveForm(part,part.length)){String t=extractText(rec.getResult(),"text");if(!t.isEmpty())text.append(t).append(' ');}
            }
            text.append(extractText(rec.getFinalResult(),"text"));return text.toString().trim();
        }catch(Throwable e){return "";}finally{if(rec!=null)rec.close();}
    }

    /** Two unconstrained decodes, not a grammar forced to return the requested phrase. */
    public PhraseEvidence analyzePhrase(short[] raw,String expected) {
        if(!WakePolicy.usableAudio(raw))return new PhraseEvidence("","","AUDIO_QUALITY: too little usable speech, clipping or noise. Check the microphone.",null);
        short[] processed=QuietAudioProcessor.prepare(raw);
        String enhanced=transcribe(processed);
        String original=transcribe(raw);
        boolean enhancedMatch=PhraseEvidence.complete(expected,enhanced),rawMatch=PhraseEvidence.complete(expected,original);
        // An exact full-vocabulary decode can rescue a gain-distorted take. Neither decoder gets
        // the target phrase as its grammar, so merely typing a phrase cannot force acceptance.
        if((enhancedMatch||rawMatch)&&(WakePolicy.normalize(original).split(" ").length>WakePolicy.normalize(expected).split(" ").length||WakePolicy.normalize(enhanced).split(" ").length>WakePolicy.normalize(expected).split(" ").length))return new PhraseEvidence(original,enhanced,"EXTRA_SPEECH: the decoders disagree about extra words. Repeat only the complete phrase.",null);
        if(enhancedMatch||rawMatch)return new PhraseEvidence(original,enhanced,"",enhancedMatch?processed:raw);
        return new PhraseEvidence(original,enhanced,PhraseEvidence.mismatch(expected,enhanced),null);
    }

    /** Compute a speaker x-vector for a PCM clip (16kHz mono). Null if unavailable. */
    public float[] embed(short[] pcm) {
        if(!isReady()||!isSpeakerReady()||pcm==null||pcm.length<3200)return null;
        Recognizer rec=null;
        try{
            rec=new Recognizer(model,SAMPLE_RATE);
            rec.setSpeakerModel(spkModel);
            rec.acceptWaveForm(pcm,pcm.length);String json=rec.getFinalResult();
            if(new JSONObject(json).optInt("spk_frames",0)<50)return null;
            return extractSpk(json);
        }catch(Throwable error){return null;}finally{if(rec!=null)rec.close();}
    }

    private static float[] extractSpk(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray spk = o.optJSONArray("spk");
            if (spk == null) return null;
            float[] v = new float[spk.length()];
            for (int i = 0; i < v.length; i++) v[i] = (float) spk.getDouble(i);
            // WakePolicy.owner()/enrollment() both hard-require WakePolicy.EMBED_DIM (128) and
            // silently reject anything else — that's correct as a safety gate, but a dimension
            // mismatch here means the loaded speaker model itself changed (e.g. a different
            // vosk-model-spk build) and voice verification will be silently disabled everywhere.
            // Logging it means that failure mode shows up in logs instead of just "wake never
            // accepts my voice" with no clue why.
            if (v.length != WakePolicy.EMBED_DIM) {
                android.util.Log.w("IRIS", "Speaker x-vector length " + v.length + " != expected "
                        + WakePolicy.EMBED_DIM + " — voice verification will reject all matches "
                        + "until the speaker model is reverted or WakePolicy.EMBED_DIM is updated.");
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Helpers ───

    private static String extractText(String json, String field) {
        if (json == null) return "";
        try {
            return new JSONObject(json).optString(field, "").trim().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
