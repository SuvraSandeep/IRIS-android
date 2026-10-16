package com.iris.assistant;

/** Offline checks for edited-message detection. */
public final class EditedMessageTest {
    private static int checks;
    private static void yes(String b, String what) {
        checks++;
        if (!EditedMessage.looksEdited(b)) throw new AssertionError("FAILED (want edit): " + what + " :: " + b);
    }
    private static void no(String b, String what) {
        checks++;
        if (EditedMessage.looksEdited(b)) throw new AssertionError("FAILED (want normal): " + what + " :: " + b);
    }

    public static void main(String[] args) {
        yes("See you at 6 (edited)", "trailing (edited)");
        yes("ok fine <edited>", "angle edited");
        yes("This message was edited", "whatsapp phrase");
        yes("meeting moved \u2022 edited", "bullet edited marker");

        no("I edited the document and sent it", "a sentence about editing");
        no("the edited cut of the film", "edited mid-sentence");
        no("see you at 6", "ordinary message");
        no("", "empty");

        System.out.println("Passed " + checks + " edited message checks");
    }
}
