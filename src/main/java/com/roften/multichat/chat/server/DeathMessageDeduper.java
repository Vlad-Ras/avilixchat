package com.roften.multichat.chat.server;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents duplicate handling of the same death message when both a mixin redirect
 * and a NeoForge event fallback fire.
 */
public final class DeathMessageDeduper {
    private DeathMessageDeduper() {}

    private static final Map<UUID, Integer> HANDLED_TICK = new ConcurrentHashMap<>();

    public static void markHandled(ServerPlayer player, int serverTick) {
        if (player == null) return;
        HANDLED_TICK.put(player.getUUID(), serverTick);
    }

    public static boolean wasHandledRecently(ServerPlayer player, int serverTick) {
        if (player == null) return false;
        Integer t = HANDLED_TICK.get(player.getUUID());
        if (t == null) return false;
        return Math.abs(serverTick - t) <= 2;
    }
}
