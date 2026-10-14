package com.iris.assistant;

/** Offline checks for owner-name aliasing. */
public final class OwnerAliasTest {
    private static int checks;
    private static void eq(String a, String b, String what) {
        checks++;
        if (!a.equals(b)) throw new AssertionError("FAILED: " + what + " expected \"" + b
                + "\" got \"" + a + "\"");
    }

    public static void main(String[] args) {
        eq(OwnerAlias.toThirdPerson("where is Suvra", "Suvra"), "where is he",
                "name -> he");
        eq(OwnerAlias.toThirdPerson("is Suvra sleeping", "Suvra"), "is he sleeping",
                "name mid-sentence");
        eq(OwnerAlias.toThirdPerson("Suvra's email please", "Suvra"), "his email please",
                "possessive -> his");
        eq(OwnerAlias.toThirdPerson("WHERE IS SUVRA", "Suvra"), "WHERE IS he",
                "case-insensitive");
        // A different name is untouched; "Suvrajit" must not be partially replaced.
        eq(OwnerAlias.toThirdPerson("call Suvrajit", "Suvra"), "call Suvrajit",
                "word boundary, no partial match");
        eq(OwnerAlias.toThirdPerson("hello there", "Suvra"), "hello there",
                "no owner mention untouched");
        checks++;
        if (!OwnerAlias.mentionsOwner("ask Suvra", "Suvra")) throw new AssertionError("mentions");
        checks++;
        if (OwnerAlias.mentionsOwner("ask Suvrajit", "Suvra")) throw new AssertionError("no partial");

        System.out.println("Passed " + checks + " owner alias checks");
    }
}
