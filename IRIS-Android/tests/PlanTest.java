package com.iris.assistant;

/**
 * Phase 2 regression checks: structured understanding + strict plan validation.
 * Pure logic, no Android — run via scripts/test-speech.sh.
 */
public final class PlanTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static Plan p(String text) { return IntentParser.parse(text); }

    public static void main(String[] args) {
        // ── screenshot: complete, executable, not sensitive ──
        Plan shot = p("take a screenshot");
        check(shot.intent() == IrisIntent.TAKE_SCREENSHOT, "screenshot intent");
        check(shot.isComplete() && shot.isExecutable(), "screenshot executable");
        check(shot.steps().get(0).tool().equals("take_screenshot"), "screenshot tool");
        check(p("screenshot").intent() == IrisIntent.TAKE_SCREENSHOT, "bare screenshot");
        check(p("click one screenshot").intent() == IrisIntent.TAKE_SCREENSHOT, "indian phrasing screenshot");

        // ── alarm with a time ──
        Plan a1 = p("set an alarm for 7 am");
        check(a1.intent() == IrisIntent.SET_ALARM, "alarm intent");
        check(a1.entity("time").equals("7 am"), "alarm time = " + a1.entity("time"));
        check(a1.isExecutable(), "alarm executable");
        check(p("wake me up at 6:30 pm").entity("time").equals("6:30 pm"), "alarm 6:30 pm");
        check(p("set alarm at seven o'clock").entity("time").startsWith("seven"), "word-time alarm");

        // ── alarm WITHOUT a time: must ask, never execute ──
        Plan a2 = p("set an alarm");
        check(a2.intent() == IrisIntent.SET_ALARM, "bare alarm intent");
        check(!a2.isComplete(), "bare alarm incomplete");
        check(!a2.isExecutable(), "bare alarm must not execute");
        check(a2.firstMissing().equals("time"), "bare alarm missing time");
        check(IntentParser.clarifyQuestion(a2).contains("time"), "alarm clarify question");

        // ── timer ──
        check(p("set a timer for 5 minutes").entity("duration").equals("5 minutes"), "timer duration");
        check(p("set a timer").firstMissing().equals("duration"), "timer missing duration");
        check(!p("set a timer").isExecutable(), "bare timer must not execute");

        // ── call: sensitive, so never auto-executable even when complete ──
        Plan c1 = p("call Maa");
        check(c1.intent() == IrisIntent.CALL_CONTACT, "call intent");
        check(c1.entity("recipient").equals("Maa"), "call keeps original case: " + c1.entity("recipient"));
        check(c1.needsConfirmation(), "call needs confirmation");
        check(!c1.isExecutable(), "call must not auto-execute");
        check(c1.isComplete(), "call complete");
        check(p("call").firstMissing().equals("recipient"), "bare call missing recipient");
        check(IntentParser.clarifyQuestion(p("call")).contains("call"), "call clarify question");

        // ── camera video + screen recording + voice ──
        Plan v = p("record front camera video 20 seconds");
        check(v.intent() == IrisIntent.RECORD_VIDEO, "video intent");
        check(v.entity("camera").equals("front"), "front camera");
        check(v.entity("duration").equals("20 seconds"), "video duration = " + v.entity("duration"));
        check(p("record video").entity("camera").equals("back"), "default back camera");
        check(p("record the screen for 2 minutes").intent() == IrisIntent.RECORD_SCREEN, "screen rec intent");
        check(p("record the screen for 2 minutes").entity("duration").equals("2 minutes"), "screen rec duration");
        check(p("record voice for an hour").intent() == IrisIntent.RECORD_VOICE, "voice intent");
        check(p("record voice for an hour").entity("duration").equals("an hour"), "voice duration");

        // ── torch ──
        check(p("torch on").entity("state").equals("on"), "torch on");
        check(p("torch off").isExecutable(), "torch executable");

        // ── unknown: parser must defer to the existing router ──
        check(p("what's the weather").isUnknown(), "weather deferred");
        check(p("tell maa i am late").isUnknown(), "sms deferred");
        check(p("").isUnknown(), "empty unknown");
        check(p(null).isUnknown(), "null unknown");

        // ── strict JSON validation (what a future LLM must satisfy) ──
        Plan good = Plan.fromJson("{\"intent\":\"SET_ALARM\",\"confidence\":0.9,"
                + "\"entities\":{\"time\":\"7 am\"},"
                + "\"steps\":[{\"tool\":\"create_alarm\",\"arguments\":{\"time\":\"7 am\"}}]}");
        check(good.intent() == IrisIntent.SET_ALARM, "json intent");
        check(good.entity("time").equals("7 am"), "json entity");
        check(good.steps().size() == 1, "json one step");
        check(good.isExecutable(), "json executable");

        // unknown tool must be rejected outright
        check(Plan.fromJson("{\"intent\":\"SET_ALARM\",\"steps\":[{\"tool\":\"wipe_phone\"}]}").isUnknown(),
                "unknown tool rejected");
        // unknown intent rejected
        check(Plan.fromJson("{\"intent\":\"LAUNCH_MISSILE\",\"steps\":[]}").isUnknown(), "unknown intent rejected");
        // malformed / empty input rejected
        check(Plan.fromJson("not json").isUnknown(), "garbage rejected");
        check(Plan.fromJson("").isUnknown(), "empty rejected");
        check(Plan.fromJson(null).isUnknown(), "null rejected");
        // step with no tool name rejected
        check(Plan.fromJson("{\"intent\":\"SET_ALARM\",\"steps\":[{\"arguments\":{}}]}").isUnknown(),
                "step without tool rejected");
        // prose-wrapped JSON is tolerated
        check(Plan.fromJson("Sure! {\"intent\":\"TAKE_SCREENSHOT\",\"confidence\":0.9,"
                + "\"steps\":[{\"tool\":\"take_screenshot\"}]} done").intent() == IrisIntent.TAKE_SCREENSHOT,
                "prose-wrapped json");
        // a sensitive intent from JSON still requires confirmation
        Plan jsonCall = Plan.fromJson("{\"intent\":\"CALL_CONTACT\",\"confidence\":1.0,"
                + "\"steps\":[{\"tool\":\"call_contact\",\"arguments\":{\"name\":\"Dad\"}}]}");
        check(jsonCall.needsConfirmation() && !jsonCall.isExecutable(), "json call still confirms");
        // missing fields survive the round trip
        check(Plan.fromJson("{\"intent\":\"SET_ALARM\",\"missing\":[\"time\"],\"steps\":[]}")
                .firstMissing().equals("time"), "json missing field");
        // confidence gating
        check(!Plan.fromJson("{\"intent\":\"TAKE_SCREENSHOT\",\"confidence\":0.2,"
                + "\"steps\":[{\"tool\":\"take_screenshot\"}]}").isExecutable(), "low confidence not executable");

        // ── tool whitelist ──
        check(ToolCall.of("take_screenshot").isKnown(), "known tool");
        check(!ToolCall.of("format_disk").isKnown(), "unknown tool");

        System.out.println("Passed " + checks + " plan/intent checks.");
    }
}
