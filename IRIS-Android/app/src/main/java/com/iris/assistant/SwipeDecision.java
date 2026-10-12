package com.iris.assistant;

/**
 * Decides what a drag on an archive row means.
 *
 * Pure, with no Android imports, so the thresholds are checked offline rather than by repeatedly
 * flicking a phone.
 *
 * The archive list is a LinearLayout inside a ScrollView, not a RecyclerView, so there is no
 * ItemTouchHelper to lean on and adding one would mean adding androidx.recyclerview as a
 * dependency to a project that currently has three. The gesture is therefore handled directly,
 * which makes one judgement unavoidable: a touch that moves both sideways and down has to be
 * claimed either by the row or by the scroll view, and claiming wrongly either makes the list
 * impossible to scroll or makes swiping impossible.
 *
 * The bias is deliberately toward scrolling. A failed swipe is a minor annoyance the owner simply
 * repeats; a list that fights being scrolled is unusable, and an accidental swipe deletes evidence.
 */
public final class SwipeDecision {

    /**
     * Fraction of the row's width a drag must cover to count as a dismissal.
     *
     * A third rather than a half: the owner's complaint was that long-press and confirm is a chore,
     * so the gesture has to feel light. The undo window is what makes a light threshold safe.
     */
    public static final float DISMISS_FRACTION = 0.33f;

    /** Pixels of horizontal travel before the gesture is claimed from the scroll view at all. */
    public static final int CLAIM_SLOP_PX = 24;

    /**
     * How much more horizontal than vertical the movement must be before the row claims it.
     *
     * Greater than one, so a diagonal drag goes to the scroll view rather than the row.
     */
    public static final float HORIZONTAL_BIAS = 1.6f;

    /** A flick this fast dismisses even without reaching the distance threshold. */
    public static final float FLING_PX_PER_SECOND = 900f;

    private SwipeDecision() { }

    /**
     * Whether the row should take over this gesture from the enclosing scroll view.
     *
     * @param totalDx total horizontal travel since the touch began, may be negative
     * @param totalDy total vertical travel since the touch began, may be negative
     */
    public static boolean claimsGesture(float totalDx, float totalDy) {
        float dx = Math.abs(totalDx);
        float dy = Math.abs(totalDy);
        if (dx < CLAIM_SLOP_PX) return false;
        return dx > dy * HORIZONTAL_BIAS;
    }

    /**
     * Whether releasing here should dismiss the row.
     *
     * @param dragPx            horizontal offset at release, may be negative
     * @param velocityPxPerSec  horizontal velocity at release, may be negative
     * @param rowWidthPx        the row's width
     */
    public static boolean dismisses(float dragPx, float velocityPxPerSec, int rowWidthPx) {
        if (rowWidthPx <= 0) return false;
        float travelled = Math.abs(dragPx);
        // A fling must still be going the same way as the drag. Without this check a quick
        // flick-and-return would delete the row the owner just decided to keep.
        boolean sameDirection = dragPx == 0 || velocityPxPerSec == 0
                || (dragPx > 0) == (velocityPxPerSec > 0);
        if (sameDirection && Math.abs(velocityPxPerSec) >= FLING_PX_PER_SECOND
                && travelled >= CLAIM_SLOP_PX) {
            return true;
        }
        return travelled >= rowWidthPx * DISMISS_FRACTION;
    }

    /**
     * Opacity for a row mid-drag, so the gesture visibly commits before the finger lifts.
     *
     * Floored well above invisible: a row faded to nothing would read as already deleted, which
     * for a destructive action is a promise that has not been kept yet.
     */
    public static float dragAlpha(float dragPx, int rowWidthPx) {
        if (rowWidthPx <= 0) return 1f;
        float progress = Math.abs(dragPx) / rowWidthPx;
        if (progress <= 0) return 1f;
        float alpha = 1f - progress;
        return Math.max(0.35f, Math.min(1f, alpha));
    }
}
