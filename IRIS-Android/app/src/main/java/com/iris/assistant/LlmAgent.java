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
            return result != null ? result.toString().trim() : null;
        } catch (Throwable t) {
            android.util.Log.e("IRIS", "LLM generate failed: " + t.getMessage());
            return null;
        }
    }

    /** Build the system prompt with memory, personality, conversation, and tools. */
    private String buildPrompt(Context context, String userMessage, String conversationContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are IRIS, a warm, concise personal assistant living on the user's phone.\n");

        String personality = new AppSettings(context).personality();
        if ("Sarcastic".equals(personality)) sb.append("Your tone is witty and playfully sarcastic, but still helpful.\n");
        else if ("Professional".equals(personality)) sb.append("Your tone is formal, brief, and professional.\n");
        else if ("Warm".equals(personality)) sb.append("Your tone is warm, caring, and friendly.\n");
        else sb.append("Your tone is friendly and natural.\n");

        // Inject memory
        String name = MemoryStore.ownerName(context);
        if (name != null) sb.append("The user's name is ").append(name).append(".\n");
        List<MemoryStore.Memory> memories = MemoryStore.getAll(context);
        if (!memories.isEmpty()) {
            sb.append("What you know about the user:\n");
            int count = 0;
            for (MemoryStore.Memory m : memories) {
                if (count++ >= 15) break;
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
        sb.append("[NOTIFICATIONS] — read recent phone notifications\n");
        sb.append("[REMEMBER: <fact>] — save a fact about the user\n");
        sb.append("[RECALL: <topic>] — look up something you know about the user\n");
        sb.append("Otherwise, reply conversationally in one or two short sentences.\n\n");

        // Few-shot examples so the small model reliably uses tags
        sb.append("Examples:\n");
        sb.append("User: call my brother\nIRIS: [CALL: brother]\n");
        sb.append("User: what's the weather\nIRIS: [WEATHER]\n");
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
