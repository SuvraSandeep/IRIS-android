package com.iris.assistant;

/**
 * Offline checks for the swipe gesture thresholds.
 *
 * Two opposing failures are pinned here. If the row claims gestures too eagerly the list cannot be
 * scrolled, which makes the Vault unusable. If it dismisses too eagerly it deletes evidence by
 * accident, which is unrecoverable. The bias is toward scrolling throughout, and these checks are
 * what stop a later tweak quietly reversing that.
 */
public class SwipeDecisionTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        verticalDragsAlwaysBelongToTheScrollView();
        clearHorizontalDragsAreClaimed();
        smallMovementsAreNeverClaimed();
        distanceDismissesOnlyPastTheThreshold();
        aFlingDismissesEarlyButOnlyInTheRightDirection();
        degenerateInputCannotDelete();
        dragAlphaStaysVisible();
        System.out.println("Passed " + checks + " swipe decision checks");
    }

    /** Scrolling must always win a vertical drag, or the list becomes unusable. */
    private static void verticalDragsAlwaysBelongToTheScrollView() {
        check(!SwipeDecision.claimsGesture(0, 200), "a pure vertical drag scrolls");
        check(!SwipeDecision.claimsGesture(0, -200), "an upward drag scrolls");
        check(!SwipeDecision.claimsGesture(30, 200), "mostly vertical scrolls");
        check(!SwipeDecision.claimsGesture(100, 100), "an exact diagonal scrolls rather than swipes");
        check(!SwipeDecision.claimsGesture(120, 100),
                "slightly more horizontal than vertical still scrolls, because the bias exceeds one");
    }

    private static void clearHorizontalDragsAreClaimed() {
        check(SwipeDecision.claimsGesture(200, 0), "a pure horizontal drag swipes");
        check(SwipeDecision.claimsGesture(-200, 0), "a leftward drag swipes");
        check(SwipeDecision.claimsGesture(200, 20), "mostly horizontal swipes");
        check(SwipeDecision.claimsGesture(-180, 30), "mostly horizontal leftward swipes");
    }

    /** Below the slop nothing is claimed, so a tap is never mistaken for a swipe. */
    private static void smallMovementsAreNeverClaimed() {
        check(!SwipeDecision.claimsGesture(0, 0), "a tap is not a swipe");
        check(!SwipeDecision.claimsGesture(5, 0), "a tiny wobble is not a swipe");
        check(!SwipeDecision.claimsGesture(SwipeDecision.CLAIM_SLOP_PX - 1, 0),
                "just under the slop is not a swipe");
        check(SwipeDecision.claimsGesture(SwipeDecision.CLAIM_SLOP_PX + 1, 0),
                "just over the slop with no vertical movement is a swipe");
    }

    private static void distanceDismissesOnlyPastTheThreshold() {
        int width = 1000;
        int threshold = (int) (width * SwipeDecision.DISMISS_FRACTION);
        check(!SwipeDecision.dismisses(threshold - 10, 0, width),
                "just short of the threshold springs back");
        check(SwipeDecision.dismisses(threshold + 10, 0, width),
                "past the threshold dismisses");
        check(SwipeDecision.dismisses(-(threshold + 10), 0, width),
                "a leftward swipe dismisses symmetrically");
        check(!SwipeDecision.dismisses(50, 0, width), "a short nudge never deletes");
        check(!SwipeDecision.dismisses(0, 0, width), "no movement never deletes");
    }

    /**
     * A fling should dismiss without crossing the full distance, but only if it is still travelling
     * the way the row was dragged. Without the direction check, a flick-and-pull-back would delete
     * the row the owner had just decided to keep.
     */
    private static void aFlingDismissesEarlyButOnlyInTheRightDirection() {
        int width = 1000;
        float fast = SwipeDecision.FLING_PX_PER_SECOND + 100;
        check(SwipeDecision.dismisses(60, fast, width), "a fast flick right dismisses early");
        check(SwipeDecision.dismisses(-60, -fast, width), "a fast flick left dismisses early");
        check(!SwipeDecision.dismisses(60, -fast, width),
                "a flick that reverses direction does NOT dismiss");
        check(!SwipeDecision.dismisses(-60, fast, width),
                "a leftward drag flicked back right does NOT dismiss");
        check(!SwipeDecision.dismisses(10, fast, width),
                "a fast flick that barely moved does not dismiss");
        check(!SwipeDecision.dismisses(60, SwipeDecision.FLING_PX_PER_SECOND - 100, width),
                "a slow drag of the same distance does not dismiss");
    }

    /** A bad width must never resolve to "delete", since the cost is unrecoverable. */
    private static void degenerateInputCannotDelete() {
        check(!SwipeDecision.dismisses(500, 0, 0), "a zero width cannot dismiss");
        check(!SwipeDecision.dismisses(500, 0, -100), "a negative width cannot dismiss");
        check(!SwipeDecision.dismisses(500, 5000, 0),
                "not even a fling dismisses when the width is unknown");
    }

    private static void dragAlphaStaysVisible() {
        int width = 1000;
        check(SwipeDecision.dragAlpha(0, width) == 1f, "an untouched row is fully opaque");
        check(SwipeDecision.dragAlpha(300, width) < 1f, "dragging fades the row");
        check(SwipeDecision.dragAlpha(300, width) > 0.3f, "a dragged row stays clearly visible");
        check(SwipeDecision.dragAlpha(5000, width) >= 0.35f,
                "even a huge drag never fades to invisible, which would promise a delete that has "
                        + "not happened");
        check(SwipeDecision.dragAlpha(100, 0) == 1f, "a zero width does not divide by zero");
        check(SwipeDecision.dragAlpha(-300, width) == SwipeDecision.dragAlpha(300, width),
                "fading is symmetric in both directions");
    }
}
