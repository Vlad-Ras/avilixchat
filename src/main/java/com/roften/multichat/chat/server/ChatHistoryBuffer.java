package com.roften.multichat.chat.server;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.chat.ChatChannel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Lightweight in-memory buffer of recently routed LOCAL messages.
 * Used for admin "history in radius" command.
 */
public final class ChatHistoryBuffer {
    private ChatHistoryBuffer() {}

    private static final Deque<Record> BUFFER = new ConcurrentLinkedDeque<>();

    public static void recordLocal(ResourceKey<Level> dim, double x, double y, double z, Component formatted) {
        if (dim == null || formatted == null) return;
        BUFFER.addLast(new Record(System.currentTimeMillis(), dim, x, y, z, formatted, ChatChannel.LOCAL));
        trim();
    }

    public static void recordLocalDeath(ResourceKey<Level> dim, double x, double y, double z, Component formatted) {
        if (dim == null || formatted == null) return;
        BUFFER.addLast(new Record(System.currentTimeMillis(), dim, x, y, z, formatted, ChatChannel.LOCAL));
        trim();
    }

    private static void trim() {
        int max = Math.max(100, MultiChatConfig.AREA_HISTORY_MAX_MESSAGES.getAsInt());
        while (BUFFER.size() > max) {
            BUFFER.pollFirst();
        }
    }

    /**
     * Returns formatted messages within radius (blocks) around the given point, and within the last N minutes.
     */
    public static List<Record> queryRecords(ResourceKey<Level> dim, double cx, double cy, double cz, int radiusBlocks, int lastMinutes) {
        if (dim == null) return List.of();
        long since = System.currentTimeMillis() - (long) Math.max(1, lastMinutes) * 60_000L;
        double r = Math.max(1, radiusBlocks);
        double r2 = r * r;

        List<Record> out = new ArrayList<>();
        for (Record rec : BUFFER) {
            if (rec.tsMillis < since) continue;
            if (!dim.equals(rec.dimension)) continue;
            double dx = rec.x - cx;
            double dy = rec.y - cy;
            double dz = rec.z - cz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;
            out.add(rec);
        }
        return out;
    }

    /**
     * Public so command handlers can attach coordinates / build clickable teleport links.
     */
    public record Record(long tsMillis, ResourceKey<Level> dimension, double x, double y, double z, Component formatted, ChatChannel channel) {}
}
