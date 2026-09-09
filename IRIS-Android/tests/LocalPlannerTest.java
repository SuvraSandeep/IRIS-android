package com.iris.assistant;

/**
 * Phase 5 regression checks for the planner layer, using a fake engine so no model is needed.
 * Proves the safety gates: bad output is rejected rather than acted upon.
 */
public final class LocalPlannerTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    /** An engine that always returns the same canned output. */
    private static LocalPlanner with(final String output, final boolean ready) {
        return new LocalPlanner(new LocalPlanner.Engine() {
            @Override public boolean ready() { return ready; }
            @Override public String generate(String prompt) { return output; }
        });
    }

    public static void main(String[] args) {
        // ── a good plan is accepted ──
        LocalPlanner ok = with("{\"intent\":\"SET_ALARM\",\"confidence\":0.9,"
                + "\"entities\":{\"time\":\"7 am\"},"
                + "\"steps\":[{\"tool\":\"create_alarm\",\"arguments\":{\"time\":\"7 am\"}}]}", true);
        Plan p = ok.plan("set an alarm for 7 am", "");
        check(p.intent() == IrisIntent.SET_ALARM, "good plan accepted");
        check(p.entity("time").equals("7 am"), "entity survived");
        check(ok.available(), "engine available");

        // ── unavailable engine must not be consulted ──
        check(with("{}", false).plan("anything", "").isUnknown(), "unready engine -> unknown");
        check(!with("{}", false).available(), "unready reports unavailable");

        // ── an invented tool is rejected ──
        check(with("{\"intent\":\"SET_ALARM\",\"confidence\":0.99,"
                + "\"steps\":[{\"tool\":\"wipe_phone\"}]}", true)
                .plan("x", "").isUnknown(), "invented tool rejected");

        // ── an invented intent is rejected ──
        check(with("{\"intent\":\"TRANSFER_MONEY\",\"confidence\":0.99,\"steps\":[]}", true)
                .plan("x", "").isUnknown(), "invented intent rejected");

        // ── low confidence is rejected (rule 6 of the prompt) ──
        check(with("{\"intent\":\"TAKE_SCREENSHOT\",\"confidence\":0.2,"
                + "\"steps\":[{\"tool\":\"take_screenshot\"}]}", true)
                .plan("x", "").isUnknown(), "low confidence rejected");

        // ── garbage / empty / null model output is rejected ──
        check(with("I think you want an alarm!", true).plan("x", "").isUnknown(), "prose rejected");
        check(with("", true).plan("x", "").isUnknown(), "empty rejected");
        check(with(null, true).plan("x", "").isUnknown(), "null rejected");

        // ── an incomplete plan survives so the caller can ask ──
        Plan inc = with("{\"intent\":\"SET_ALARM\",\"confidence\":0.9,"
                + "\"missing\":[\"time\"],\"steps\":[]}", true).plan("set an alarm", "");
        check(!inc.isUnknown() && !inc.isComplete(), "incomplete plan kept");
        check(inc.firstMissing().equals("time"), "incomplete names the field");

        // ── a sensitive plan is never auto-executable ──
        Plan call = with("{\"intent\":\"CALL_CONTACT\",\"confidence\":1.0,"
                + "\"steps\":[{\"tool\":\"call_contact\",\"arguments\":{\"name\":\"Dad\"}}]}", true)
                .plan("call dad", "");
        check(call.needsConfirmation() && !call.isExecutable(), "sensitive plan confirms");

        // ── empty transcript is not sent to the model ──
        check(with("{\"intent\":\"SET_ALARM\",\"steps\":[]}", true).plan("", "").isUnknown(), "empty input");
        check(with("{\"intent\":\"SET_ALARM\",\"steps\":[]}", true).plan(null, "").isUnknown(), "null input");

        // ── the prompt must list only real tools/intents and carry the safety rules ──
        String prompt = LocalPlanner.buildPrompt("set an alarm", "- alarm: Set an alarm for 7:00 AM");
        check(prompt.contains("create_alarm"), "prompt lists tools");
        check(prompt.contains("SET_ALARM"), "prompt lists intents");
        check(!prompt.contains("wipe_phone"), "prompt has no fake tools");
        check(prompt.contains("Never invent"), "prompt forbids invention");
        check(prompt.contains("needs_confirmation"), "prompt covers confirmation");
        check(prompt.contains("Never rewrite it"), "prompt protects message text");
        check(prompt.contains("RECENT CONTEXT"), "prompt includes context when given");
        check(!LocalPlanner.buildPrompt("hi", "").contains("RECENT CONTEXT"), "no empty context block");
        for (String tool : ToolCall.KNOWN) check(prompt.contains(tool), "prompt lists " + tool);

        System.out.println("Passed " + checks + " local-planner checks.");
    }
}
