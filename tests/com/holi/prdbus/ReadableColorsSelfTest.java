package com.holi.prdbus;

public final class ReadableColorsSelfTest {
    public static void main(String[] args) {
        int count = 0;
        for (int r = 0; r <= 255; r += 17)
            for (int g = 0; g <= 255; g += 17)
                for (int b = 0; b <= 255; b += 17) {
                    int color = 0xFF000000 | (r << 16) | (g << 8) | b;
                    if (ReadableColors.contrast(color, ReadableColors.foreground(color)) < 4.5)
                        throw new AssertionError("Insufficient label contrast: " + color);
                    count++;
                }
        for (int text : new int[]{0xFF1E6A97, 0xFF54637A, 0xFFA74C17})
            if (ReadableColors.contrast(text, 0xFFF8FAFD) < 4.5)
                throw new AssertionError("Theme text contrast");
        System.out.println("ReadableColorsSelfTest passed (" + count + " background samples + theme)");
    }
}
