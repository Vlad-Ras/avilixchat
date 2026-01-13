package com.roften.multichat.internal;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;

/**
 * Small helper for parsing user-configured colors.
 *
 * Supported:
 *  - "#RRGGBB" / "RRGGBB"
 *  - "0xRRGGBB"
 *  - vanilla formatting names like "green", "gold" (best-effort)
 */
public final class ColorUtil {
    private ColorUtil() {}

    public static TextColor parseTextColor(String input, ChatFormatting fallback) {
        Integer rgb = parseRgb(input);
        if (rgb != null) return TextColor.fromRgb(rgb);
        Integer fb = fallback != null ? fallback.getColor() : null;
        return fb != null ? TextColor.fromRgb(fb) : null;
    }

    /**
     * @return RGB int (0xRRGGBB) or null
     */
    public static Integer parseRgb(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;

        // 0xRRGGBB
        if (s.startsWith("0x") || s.startsWith("0X")) {
            try {
                int v = (int) Long.parseLong(s.substring(2), 16);
                return v & 0xFFFFFF;
            } catch (NumberFormatException ignored) {
            }
        }

        // #RRGGBB or RRGGBB
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) {
            try {
                int v = Integer.parseInt(s, 16);
                return v & 0xFFFFFF;
            } catch (NumberFormatException ignored) {
            }
        }

        // Best-effort: formatting name
        try {
            ChatFormatting cf = ChatFormatting.valueOf(s.toUpperCase());
            if (cf != null && cf.getColor() != null) return cf.getColor();
        } catch (IllegalArgumentException ignored) {
        }

        return null;
    }
}
