package com.iris.assistant;

public final class AppRequestTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        AppRequest r = AppRequest.parse("search Arijit Singh on Spotify");
        check(r != null && r.value.equals("Arijit Singh") && r.app.equals("spotify"));
        check(AppRequest.parse("find coffee near me in google maps").action.equals("search"));
        check(AppRequest.parse("share the latest video via WhatsApp").value.equals("video"));
        check(AppRequest.parse("share via whatsapp: Don't call Dad; just say hi!").value.equals("Don't call Dad; just say hi!"));
        check(AppRequest.parse("text Dad search cats on youtube") == null);
        check(AppRequest.parse("do not search cats on youtube") == null);
        check(AppRequest.parse("search cats on banking") == null);
        check(AppRequest.parse("share the latest video via whatsapp to Rahul") == null);
        check(AppRequest.parse("share the latest screenshot via whatsapp") == null); // no photo/screenshot guessing
        check(AppRequest.parse(null) == null);
        check(AppRequest.parse(SpeechText.command("please share via whatsapp saying wait for me")).value.equals("wait for me"));
        check(AppRequest.parse("share via gmail saying Yes, please!").value.equals("Yes, please!"));
        check(!new AppRequest("search", "com.attacker.app", "x").valid());
        check(!new AppRequest("share_media", "whatsapp", "content://private/file").valid());
        check(!new AppRequest("search", "spotify", "x".repeat(2001)).valid());
        Plan good = Plan.of(IrisIntent.APP_SEARCH).step(ToolCall.of("search_app", "app", "spotify", "value", "Rain")).build();
        check(AppRequest.fromPlan(good) != null);
        Plan share = Plan.of(IrisIntent.APP_SHARE).step(ToolCall.of("share_text_to_app", "app", "whatsapp", "value", "Hi")).build();
        check(share.needsConfirmation() && !share.isExecutable());
        check(AppRequest.fromPlan(share) != null);
        check(AppRequest.fromPlan(Plan.of(IrisIntent.APP_SEARCH).confidence(.5f).step(good.steps().get(0)).build()) == null);
        check(AppRequest.fromPlan(Plan.of(IrisIntent.APP_SHARE).step(good.steps().get(0)).build()) == null);
        check(AppRequest.fromPlan(Plan.of(IrisIntent.APP_SEARCH).step(good.steps().get(0)).step(good.steps().get(0)).build()) == null);
        check(AppRequest.fromPlan(Plan.of(IrisIntent.APP_SEARCH).step(ToolCall.of("search_app", "app", "spotify", "value", "Rain", "recipient", "Dad")).build()) == null);
        System.out.println("Passed " + checks + " app-integration safety checks.");
    }
}
