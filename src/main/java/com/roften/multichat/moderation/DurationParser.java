package com.roften.multichat.moderation;

import java.util.Locale;

/**
 * Parses simple duration strings like 10s, 15m, 2h, 1d, 3w, or keywords perm/forever.
 * If only a number is provided, it is treated as minutes.
 */
public final class DurationParser {
    private DurationParser() {}

    public static long parseToMillis(String input) throws IllegalArgumentException {
        if (input == null) throw new IllegalArgumentException("duration is null");
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("duration is empty");

        if (s.equals("perm") || s.equals("permanent") || s.equals("forever") || s.equals("infinite")) {
            return -1L;
        }

        // number only => minutes
        if (s.chars().allMatch(Character::isDigit)) {
            long n = Long.parseLong(s);
            return n * 60_000L;
        }

        char unit = s.charAt(s.length() - 1);
        String numPart = s.substring(0, s.length() - 1).trim();
        if (numPart.isEmpty() || !numPart.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid duration: " + input);
        }
        long n = Long.parseLong(numPart);

        return switch (unit) {
            case 's' -> n * 1000L;
            case 'm' -> n * 60_000L;
            case 'h' -> n * 3_600_000L;
            case 'd' -> n * 86_400_000L;
            case 'w' -> n * 604_800_000L;
            default -> throw new IllegalArgumentException("Invalid duration unit: " + unit);
        };
    }

    public static String formatRemaining(long remainingMs) {
        if (remainingMs < 0) return "навсегда";
        long sec = Math.max(0, remainingMs / 1000L);
        long days = sec / 86400; sec %= 86400;
        long hours = sec / 3600; sec %= 3600;
        long mins = sec / 60; sec %= 60;
        if (days > 0) return days + "д " + hours + "ч";
        if (hours > 0) return hours + "ч " + mins + "м";
        if (mins > 0) return mins + "м " + sec + "с";
        return sec + "с";
    }
}
