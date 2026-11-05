package com.aure.totk;

/** UI-owned button bindings. Pure Java so transitions can be regression-tested on a desktop JVM.
 * All masks use the native Switch bit indices (0..15), not Android keycode values.
 */
final class TouchBindings {
    static final int ZL = 8;
    private static final long ZL_MASK = 1L << ZL;
    private boolean toggleZl;
    private boolean rawZlHeld;
    private boolean latchedZl;
    private boolean zlToggledOnThisPress;
    private int comboFirst = ZL, comboSecond = 0; // default: ZL + A; macro hidden by default

    static boolean validBit(int bit) { return bit >= 0 && bit < 16; }
    static int safeBit(int bit, int fallback) { return validBit(bit) ? bit : fallback; }

    void setToggleZl(boolean enabled) {
        if (toggleZl != enabled) reset();
        toggleZl = enabled;
    }
    boolean toggleZl() { return toggleZl; }
    boolean latchedZl() { return latchedZl; }
    boolean toggledOnThisPress() { return zlToggledOnThisPress; }

    void setCombo(int first, int second) {
        if (!validBit(first) || !validBit(second) || first == second)
            throw new IllegalArgumentException("Combo requires two different Switch buttons");
        comboFirst = first;
        comboSecond = second;
    }
    int comboFirst() { return comboFirst; }
    int comboSecond() { return comboSecond; }

    /** Reset on panel hide, activity focus loss, orientation change and menu opening.
     * A latched ZL must never survive a pause or be carried into the next game session.
     */
    void reset() {
        rawZlHeld = false;
        latchedZl = false;
        zlToggledOnThisPress = false;
    }

    /** physicalBits comes exclusively from ordinary virtual button hit targets.
     * macroHeld comes from a separate finger on the M1 shortcut, so a combo containing
     * ZL does not accidentally toggle its latch. Both actions release with that finger.
     */
    long apply(long physicalBits, boolean macroHeld) {
        physicalBits &= 0xffffL;
        boolean raw = (physicalBits & ZL_MASK) != 0;
        zlToggledOnThisPress = false;
        if (toggleZl && raw && !rawZlHeld) {
            latchedZl = !latchedZl;
            zlToggledOnThisPress = true;
        }
        rawZlHeld = raw;
        long result = physicalBits;
        if (toggleZl) {
            result &= ~ZL_MASK;
            if (latchedZl) result |= ZL_MASK;
        }
        if (macroHeld) result |= 1L << comboFirst | 1L << comboSecond;
        return result;
    }
}
