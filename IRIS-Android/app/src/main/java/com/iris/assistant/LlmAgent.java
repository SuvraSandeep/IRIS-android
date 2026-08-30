package com.iris.assistant;

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * On-device conversational AI using Google's Gemma via MediaPipe LLM Inference.
 *
 * Uses reflection to call MediaPipe so the app compiles and runs even when the
 * runtime/model is unavailable — in that case isReady() returns false and the
 * caller falls back to the rule-based chat.
 *
 * The model (gemma .task/.bin) is expected in the app's model directory.
 * 100% offline once the model is present.
 */
public final class LlmAgent {
    private static final String[] MODEL_NAMES = {
            "gemma.task", "gemma3-1b-it-int4.task", "model.task", "gemma.bin"
    };

    private Object llmInference;   // com.google.mediapipe.tasks.genai.llminference.LlmInference
    private java.lang.reflect.Method generateMethod;
    private volatile boolean ready;

    /**
     * Attempt to load the Gemma model. Returns true if loaded.
     * Runs on a background thread by the caller (model load is heavy).
     */
    public boolean loadModel(Context context) {
        File modelFile = findModel(context);
        if (modelFile == null) {
            android.util.Log.i("IRIS", "LLM: no Gemma model found, using rule-based chat");
            ready = false;
            return false;
        }
        try {
            // Reflectively build LlmInferenceOptions and create LlmInference
            Class<?> llmClass = Class.forName(
                    "com.google.mediapipe.tasks.genai.llminference.LlmInference");
            Class<?> optionsClass = Class.forName(
                    "com.google.mediapipe.tasks.genai.llminference.LlmInference$LlmInferenceOptions");
            Class<?> builderClass = Class.forName(
                    "com.google.mediapipe.tasks.genai.llminference.LlmInference$LlmInferenceOptions$Builder");

            Object builder = optionsClass.getMethod("builder").invoke(null);
            builderClass.getMethod("setModelPath", String.class)
                    .invoke(builder, modelFile.getAbsolutePath());
            try { builderClass.getMethod("setMaxTokens", int.class).invoke(builder, 512); } catch (Exception ignored) { }
            try { builderClass.getMethod("setMaxTopK", int.class).invoke(builder, 40); } catch (Exception ignored) { }
            Object options = builderClass.getMethod("build").invoke(builder);

            llmInference = llmClass.getMethod("createFromOptions", Context.class, optionsClass)
                    .invoke(null, context, options);
            generateMethod = llmClass.getMethod("generateResponse", String.class);
            ready = true;
            android.util.Log.i("IRIS", "LLM: Gemma loaded from " + modelFile.getName());
            return true;
        } catch (Throwable t) {
            android.util.Log.w("IRIS", "LLM load failed: " + t.getMessage());
            ready = false;
            return false;
        }
    }

    public boolean isReady() { return ready && llmInference != null && generateMethod != null; }

    /**
     * Generate a reply to the user's message, given their memory and personality.
     * Returns the raw model output (may contain an action tag). Null on failure.
     */
    public String generateReply(Context context, String userMessage, String conversationContext) {
        if (!isReady()) return null;
        try {
            String prompt = buildPrompt(context, userMessage, conversationContext);
            Object result = generateMethod.invoke(llmInference, prompt);
            return cleanReply(result != null ? result.toString() : null);
        } catch (Throwable t) {
            android.util.Log.e("IRIS", "LLM generate failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Strip conversation scaffolding the small model tends to echo back:
     * leading "IRIS:" labels, literal "\n", and any hallucinated next turn
     * ("User:", a second "IRIS:", or model end-of-turn markers). Keeps only
     * IRIS's single reply. Returns null if nothing usable remains.
     */
    static String cleanReply(String raw) {
        if (raw == null) return null;
        String s = raw.replace("\\n", "\n").trim();
        // If the model echoed turn labels, take the text after the first "IRIS:"
        int iris = s.indexOf("IRIS:");
        if (iris >= 0) s = s.substring(iris + 5);
        // Cut at the first appended new turn or end-of-turn marker
        String[] stops = {
                "\nUser:", "User:", "\nIRIS:", "IRIS:", "\nAssistant:", "Assistant:",
                "<|im_end|>", "<|im_start|>", "<|endoftext|>", "<end_of_turn>", "<eos>"
        };
        int cut = -1;
        for (String stop : stops) {
            int i = s.indexOf(stop);
            if (i >= 0 && (cut < 0 || i < cut)) cut = i;
        }
        if (cut >= 0) s = s.substring(0, cut);
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** A live snapshot of the environment so replies are context-aware. */
    private String situationContext(Context context) {
        StringBuilder sb = new StringBuilder("Current context (for your awareness):\n");
        try {
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat(
                    "EEEE, d MMMM yyyy, h:mm a", java.util.Locale.getDefault());
            sb.append("- Now: ").append(df.format(now.getTime())).append("\n");
            int hour = now.get(java.util.Calendar.HOUR_OF_DAY);
            String part = hour < 5 ? "late night" : hour < 12 ? "morning"
                    : hour < 17 ? "afternoon" : hour < 21 ? "evening" : "night";
            sb.append("- Part of day: ").append(part).append("\n");
        } catch (Exception ignored) { }
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int level = bm != null
                    ? bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            if (level >= 0) {
                boolean charging = bm.isCharging();
                sb.append("- Battery: ").append(level).append("%")
                        .append(charging ? " (charging)" : "").append("\n");
            }
        } catch (Exception ignored) { }
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            String net = "offline";
            if (cm != null) {
                android.net.Network active = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
                if (caps != null) {
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) net = "Wi-Fi";
                    else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) net = "mobile data";
                    else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) net = "ethernet";
                    else net = "online";
                }
            }
            sb.append("- Network: ").append(net).append("\n");
        } catch (Exception ignored) { }
        String owner = MemoryStore.ownerName(context);
        if (owner != null) sb.append("- You are speaking with: ").append(owner).append("\n");
        return sb.toString();
    }

    /** Build the system prompt with memory, personality, conversation, and tools. */
    private String buildPrompt(Context context, String userMessage, String conversationContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are IRIS, the user's personal AI assistant, running privately on their phone.\n");
        sb.append("You are highly capable, composed, and quietly confident \u2014 a devoted, discreet aide who has looked after the user for years. ");
        sb.append("You anticipate needs, speak with polished understated wit and genuine warmth, and address the user respectfully. ");
        sb.append("You are efficient and never waffle. Keep replies to one or two natural, human sentences.\n");

        String personality = new AppSettings(context).personality();
        if ("Sarcastic".equals(personality)) sb.append("Lean into dry, clever, raised-eyebrow humour \u2014 but always loyal and genuinely helpful.\n");
        else if ("Professional".equals(personality)) sb.append("Keep your tone crisp, formal, and businesslike.\n");
        else if ("Warm".equals(personality)) sb.append("Be especially warm and personable, while staying refined.\n");
        else sb.append("Keep your composed, subtly witty, and helpful demeanour.\n");

        sb.append(situationContext(context));

        // Inject memory
        String name = MemoryStore.ownerName(context);
        if (name != null) sb.append("The user's name is ").append(name).append(".\n");
        List<MemoryStore.Memory> memories = MemoryStore.getAll(context);
        if (!memories.isEmpty()) {
            sb.append("What you know about the user:\n");
            int count = 0;
            for (MemoryStore.Memory m : memories) {
                if (count++ >= 8) break;
                sb.append("- ").append(m.key).append(": ").append(m.value);
                if (m.detail != null && !m.detail.isEmpty()) sb.append(" (").append(m.detail).append(")");
                sb.append("\n");
            }
        }

        // Tool instructions
        sb.append("\nYou can take actions by replying with EXACTLY one tag:\n");
        sb.append("[CALL: <name>] — call a contact\n");
        sb.append("[REDIAL] — call the last person again\n");
        sb.append("[CALL_HISTORY] — tell who they called recently\n");
        sb.append("[TIME] — current time\n");
        sb.append("[BATTERY] — battery level\n");
        sb.append("[WEATHER] — current weather\n");
        sb.append("[LOCATION] — tell the user where they currently are\n");
        sb.append("[SMS: <name> | <message>] — send a text message\n");
        sb.append("[WHATSAPP: <name> | <message>] — send a WhatsApp message\n");
        sb.append("[ALARM: <time>] — set an alarm (e.g. 7:30 am)\n");
        sb.append("[TIMER: <duration>] — set a countdown timer (e.g. 5 minutes)\n");
        sb.append("[REMINDER: in <duration> | <task>] or [REMINDER: at <time> | <task>] — remind the user\n");
        sb.append("[TORCH: on] / [TORCH: off] — flashlight\n");
        sb.append("[VOLUME: up] / [VOLUME: down] / [VOLUME: mute] / [VOLUME: max] — media volume\n");
        sb.append("[WIFI] — open Wi-Fi settings\n");
        sb.append("[BLUETOOTH] — open Bluetooth settings\n");
        sb.append("[SEARCH: <query>] — search the web\n");
        sb.append("Only use a tag when the user actually wants that action done.\n");
        sb.append("You CANNOT (be honest and say so, don't pretend): send email, post to social media, "
                + "directly switch Wi-Fi/Bluetooth on or off (you can only open their settings), "
                + "auto-reply to notifications, make video calls, play music or media, take photos, "
                + "or operate other apps beyond opening them. If asked for something outside your tools, "
                + "say briefly that you can't do it yet.\n");
        sb.append("[NOTIFICATIONS] — read recent phone notifications\n");
        sb.append("[REMEMBER: <fact>] — save a fact about the user\n");
        sb.append("[RECALL: <topic>] — look up something you know about the user\n");
        sb.append("Otherwise, reply conversationally in one or two short sentences.\n");
        sb.append("Write ONLY IRIS's next line, then stop. Never write the user's lines or continue the conversation yourself.\n\n");

        // Few-shot examples so the small model reliably uses tags
        sb.append("Examples:\n");
        sb.append("User: call my brother\nIRIS: [CALL: brother]\n");
        sb.append("User: what's the weather\nIRIS: [WEATHER]\n");
        sb.append("User: where am i\nIRIS: [LOCATION]\n");
        sb.append("User: text mom saying I'll be home late\nIRIS: [SMS: mom | I'll be home late]\n");
        sb.append("User: whatsapp Rahul that I'm on my way\nIRIS: [WHATSAPP: Rahul | I'm on my way]\n");
        sb.append("User: set an alarm for 6:30 am\nIRIS: [ALARM: 6:30 am]\n");
        sb.append("User: set a timer for 10 minutes\nIRIS: [TIMER: 10 minutes]\n");
        sb.append("User: remind me to take medicine in 2 hours\nIRIS: [REMINDER: in 2 hours | take medicine]\n");
        sb.append("User: turn on the flashlight\nIRIS: [TORCH: on]\n");
        sb.append("User: turn up the volume\nIRIS: [VOLUME: up]\n");
        sb.append("User: search for the tallest mountain\nIRIS: [SEARCH: tallest mountain]\n");
        sb.append("User: any new messages\nIRIS: [NOTIFICATIONS]\n");
        sb.append("User: call him again\nIRIS: [REDIAL]\n");
        sb.append("User: remember I like green tea\nIRIS: [REMEMBER: likes green tea]\n");
        sb.append("User: how are you\nIRIS: I'm doing great, thanks for asking! How can I help?\n\n");

        // Conversation context window — so IRIS remembers what was just said
        if (conversationContext != null && !conversationContext.isEmpty()) {
            sb.append(conversationContext).append("\n");
        }

        sb.append("User: ").append(userMessage).append("\nIRIS:");
        return sb.toString();
    }

    public void close() {
        if (llmInference != null) {
            try {
                llmInference.getClass().getMethod("close").invoke(llmInference);
            } catch (Throwable ignored) { }
            llmInference = null;
        }
        ready = false;
    }

    private File findModel(Context context) {
        File dir = ModelManager.modelDir(context);
        for (String name : MODEL_NAMES) {
            File f = new File(dir, name);
            if (f.exists() && f.length() > 1_000_000) return f;
        }
        // Also check for any .task file
        File[] tasks = dir.listFiles((d, n) -> n.endsWith(".task") || n.endsWith(".bin"));
        if (tasks != null) {
            for (File f : tasks) if (f.length() > 1_000_000) return f;
        }
        return null;
    }
}
