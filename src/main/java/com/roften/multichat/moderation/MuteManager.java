package com.roften.multichat.moderation;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mutes with DB persistence.
 */
public final class MuteManager {
    private MuteManager() {}

    private static final ConcurrentHashMap<UUID, MuteEntry> MUTES = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    public static void init(MinecraftServer server) {
        if (!MultiChatConfig.MUTES_ENABLED.get()) {
            loaded = true;
            return;
        }
        ModerationDatabase.init(server);
        MUTES.clear();
        MUTES.putAll(ModerationDatabase.loadAllMutes());
        loaded = true;
        MultiChatMod.LOGGER.info("[MultiChat] Loaded {} mutes from database.", MUTES.size());
    }

    public static void shutdown() {
        MUTES.clear();
        loaded = false;
        ModerationDatabase.shutdown();
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static MuteEntry getMute(UUID uuid) {
        return MUTES.get(uuid);
    }

    public static Map<UUID, MuteEntry> snapshot() {
        return Map.copyOf(MUTES);
    }

    public static boolean isMuted(ServerPlayer player) {
        if (!MultiChatConfig.MUTES_ENABLED.get()) return false;
        MuteEntry e = MUTES.get(player.getUUID());
        if (e == null) return false;
        long now = System.currentTimeMillis();
        if (e.isExpired(now)) {
            // auto unmute
            removeMute(player.getServer(), player.getUUID(), "AUTO_UNMUTE", null, null);
            notifyAutoUnmuted(player);
            return false;
        }
        return true;
    }

    public static void setMute(MinecraftServer server, UUID targetUuid, String targetName,
                               UUID actorUuid, String actorName,
                               long durationMs, String reason,
                               String actorDimension, BlockPos actorPos) {
        long now = System.currentTimeMillis();
        long expiresAt = durationMs < 0 ? -1L : (now + durationMs);
        MuteEntry entry = new MuteEntry(targetUuid, targetName, actorUuid, actorName, now, expiresAt, reason);
        MUTES.put(targetUuid, entry);
        ModerationDatabase.upsertMuteAsync(entry);

        ModerationDatabase.logActionAsync("MUTE", actorUuid, actorName, targetUuid, targetName,
                durationMs < 0 ? null : durationMs, expiresAt < 0 ? null : expiresAt, reason,
                actorDimension, actorPos == null ? null : actorPos.getX(), actorPos == null ? null : actorPos.getY(), actorPos == null ? null : actorPos.getZ());
    }

    public static void removeMute(MinecraftServer server, UUID targetUuid, String action,
                                  UUID actorUuid, String actorName) {
        MUTES.remove(targetUuid);
        ModerationDatabase.deleteMuteAsync(targetUuid);
        ModerationDatabase.logActionAsync(action, actorUuid, actorName, targetUuid, null,
                null, null, null,
                null, null, null, null);
    }

    public static MutableComponent buildMutedMessage(MuteEntry e) {
        long now = System.currentTimeMillis();
        long remaining = e.isPermanent() ? -1L : Math.max(0, e.expiresAtEpochMs() - now);

        MutableComponent msg = Component.literal("Вы замучены").withStyle(ChatFormatting.RED);
        msg = msg.append(Component.literal(" (осталось: " + DurationParser.formatRemaining(remaining) + ")").withStyle(ChatFormatting.YELLOW));
        if (e.reason() != null && !e.reason().isBlank()) {
            msg = msg.append(Component.literal(" причина: " + e.reason()).withStyle(ChatFormatting.GRAY));
        }
        return msg;
    }

    public static void notifyAutoUnmuted(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Ваш мут истёк, вы снова можете писать в чат.").withStyle(ChatFormatting.GREEN));
    }
}
