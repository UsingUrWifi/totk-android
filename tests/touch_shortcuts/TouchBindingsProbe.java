package com.aure.totk;

/** Behavioral regression for sticky-ZL and simultaneous two-action macros. */
public final class TouchBindingsProbe {
    static long bit(int n) { return 1L << n; }
    static void expect(String label, long actual, long expected) {
        if (actual != expected) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
    }
    static void yes(String label, boolean value) { if (!value) throw new AssertionError(label); }
    public static void main(String[] args) {
        TouchBindings b = new TouchBindings();
        expect("default hold down", b.apply(bit(8), false), bit(8));
        expect("default hold release", b.apply(0, false), 0);
        b.setToggleZl(true);
        expect("first press latches", b.apply(bit(8), false), bit(8));
        yes("first edge detected", b.toggledOnThisPress());
        expect("still held doesn't flip", b.apply(bit(8), false), bit(8));
        yes("no duplicate edge", !b.toggledOnThisPress());
        expect("release stays latched", b.apply(0, false), bit(8));
        expect("second ZL press releases", b.apply(bit(8), false), 0);
        yes("second edge acknowledged", b.toggledOnThisPress());
        expect("release remains unlatched", b.apply(0, false), 0);
        expect("next press latches again", b.apply(bit(8), false), bit(8));
        b.reset();
        expect("reset releases on focus loss", b.apply(0, false), 0);

        b.setCombo(8, 0); // ZL + A, while ZL is toggle-enabled
        expect("macro adds two simultaneous buttons", b.apply(0, true), bit(8) | bit(0));
        yes("macro does not change latch", !b.latchedZl());
        expect("macro release releases both", b.apply(0, false), 0);
        expect("macro plus independent B", b.apply(bit(1), true), bit(8) | bit(0) | bit(1));
        expect("release macro retains B", b.apply(bit(1), false), bit(1));
        expect("B release", b.apply(0, false), 0);
        b.setCombo(12, 0); // left + A
        expect("remappable buttons", b.apply(0, true), bit(12) | bit(0));
        expect("remapped release", b.apply(0, false), 0);
        b.setToggleZl(false);
        expect("hold restore", b.apply(bit(8), false), bit(8));
        expect("hold restore release", b.apply(0, false), 0);

        boolean rejected = false;
        try { b.setCombo(8, 8); } catch (IllegalArgumentException expected) { rejected = true; }
        yes("duplicate action must be rejected", rejected);
        rejected = false;
        try { b.setCombo(-1, 0); } catch (IllegalArgumentException expected) { rejected = true; }
        yes("invalid action must be rejected", rejected);
        expect("invalid stored key falls back", TouchBindings.safeBit(99, 8), 8);
        System.out.println("PASS: toggle edges, pause reset, combo press/release, remapping, defaults");
    }
}
