package com.roften.multichat.internal;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla sometimes sends the same "unknown command" / "no permission" system message twice
 * (e.g. via different internal pathways). We only want to log it once.
 */
public final class SystemLogDeduper {
    private SystemLogDeduper() {}

    private static final long WINDOW_MS = 500L;

    private static final ConcurrentHashMap<UUID, Entry> LAST_BY_PLAYER = new ConcurrentHashMap<>();

    public static boolean shouldSkip(UUID playerUuid, String plainText) {
        if (playerUuid == null) return false;
        if (plainText == null) return false;

        long now = System.currentTimeMillis();
        int h = plainText.hashCode();

        Entry prev = LAST_BY_PLAYER.get(playerUuid);
        if (prev != null
                && prev.hash == h
                && (now - prev.epochMs) <= WINDOW_MS
                && plainText.equals(prev.text)) {
            return true;
        }

        LAST_BY_PLAYER.put(playerUuid, new Entry(now, h, plainText));
        return false;
    }

    private record Entry(long epochMs, int hash, String text) {}
}
