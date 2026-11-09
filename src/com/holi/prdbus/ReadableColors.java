package com.holi.prdbus;

final class ReadableColors {
    private ReadableColors() { }
    private static double channel(int value) {
        double x = value / 255.0;
        return x <= .04045 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4);
    }
    static double luminance(int color) {
        return .2126 * channel((color >>> 16) & 255)
                + .7152 * channel((color >>> 8) & 255) + .0722 * channel(color & 255);
    }
    static int foreground(int background) {
        return luminance(background) > .179 ? 0xFF000000 : 0xFFFFFFFF;
    }
    static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
}
