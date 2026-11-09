package com.holi.prdbus;

/** Protect the palette pairs used by the 2.0 light interface. */
public final class DesignTokensSelfTest {
    public static void main(String[] args) {
        for (int surface : new int[]{Ui.BG, Ui.SURFACE, Ui.RAISED, Ui.LIGHT_BLUE, Ui.LIGHT_ORANGE}) {
            for (int ink : new int[]{Ui.TEXT, Ui.MUTED, Ui.PRIMARY, Ui.ACCENT}) {
                if (ReadableColors.contrast(ink, surface) < 4.5)
                    throw new AssertionError("Unreadable secondary or primary text");
            }
        }
        if (ReadableColors.contrast(0xFFFFFFFF, Ui.PRIMARY) < 4.5)
            throw new AssertionError("Primary control contrast");
        if (ReadableColors.contrast(Ui.ACCENT, Ui.SURFACE) < 4.5)
            throw new AssertionError("Arrival accent contrast");
        for (boolean night : new boolean[]{false, true}) {
            Ui.setNight(night);
            if (ReadableColors.contrast(Ui.PRIMARY_DARK, Ui.LIGHT_BLUE) < 4.5)
                throw new AssertionError("Bus and intercity badge contrast");
        }
        Ui.setNight(false);
        System.out.println("DesignTokensSelfTest passed (22 contrast pairs)");
    }
}
