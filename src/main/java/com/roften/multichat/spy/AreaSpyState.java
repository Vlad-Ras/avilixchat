package com.roften.multichat.spy;

import com.roften.multichat.db.ChatLogDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin "area spy": watches LOCAL chat happening in a fixed radius around a point.
 * Enabled via /spy area <radius> [minutes].
 */
public final class AreaSpyState {
    private AreaSpyState() {}

    private static final Map<UUID, Watch> WATCHERS = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public static boolean isEnabled(ServerPlayer p) {
        if (p == null) return false;
        Watch w = WATCHERS.get(p.getUUID());
        if (w == null || !w.enabled) return false;
        if (w.isExpired()) {
            WATCHERS.remove(p.getUUID());
            return false;
        }
        return true;
    }

    public static void disable(ServerPlayer p) {
        if (p == null) return;
        WATCHERS.remove(p.getUUID());
    }

    public static void enable(ServerPlayer p, int radiusBlocks) {
        enable(p, radiusBlocks, 0);
    }

    /**
     * @param minutes Duration to keep enabled. Use 0 or less for "until disabled".
     */
    public static void enable(ServerPlayer p, int radiusBlocks, int minutes) {
        if (p == null) return;
        ResourceKey<Level> dim = p.level().dimension();
        long expiresAt = 0L;
        if (minutes > 0) {
            expiresAt = (System.currentTimeMillis() / 1000L) + (long) minutes * 60L;
        }
        WATCHERS.put(p.getUUID(), new Watch(true, dim, p.getX(), p.getY(), p.getZ(), Math.max(1, radiusBlocks), expiresAt));
    }

    public static Watch get(ServerPlayer p) {
        return p == null ? null : WATCHERS.get(p.getUUID());
    }

    /**
     * Sends a copy of a LOCAL message to any enabled watchers whose area contains (x,y,z).
     */
    public static void deliverIfMatches(MinecraftServer server, ServerPlayer sender, ResourceKey<Level> dim, double x, double y, double z, Component formatted, java.util.function.Predicate<ServerPlayer> alreadyRecipient) {
        if (server == null || formatted == null || dim == null) return;

        for (ServerPlayer admin : server.getPlayerList().getPlayers()) {
            // Permission gate: if someone lost the permission, stop watching immediately.
            if (!SpyState.hasSpyPermission(admin)) {
                WATCHERS.remove(admin.getUUID());
                continue;
            }

            Watch w = WATCHERS.get(admin.getUUID());
            if (w == null || !w.enabled) continue;
            if (w.isExpired()) {
                WATCHERS.remove(admin.getUUID());
                continue;
            }
            if (!dim.equals(w.dimension)) continue;
            double dx = x - w.cx;
            double dy = y - w.cy;
            double dz = z - w.cz;
            double d2 = dx * dx + dy * dy + dz * dz;
            double r2 = (double) w.radius * (double) w.radius;
            if (d2 > r2) continue;

            // Не дублируем, если админ и так получатель.
            if (alreadyRecipient != null && alreadyRecipient.test(admin)) continue;

            // Не отправляем обратно отправителю (если он же админ).
            if (sender != null && admin.getUUID().equals(sender.getUUID())) continue;

            MutableComponent msg = areaPrefix(w)
                    .append(formatted.copy());

            // Append clickable sender coordinates (for quick teleport).
            if (sender != null) {
                msg = msg.append(SpyState.coordsComponent(dim, x, y, z));
            }

            MutableComponent finalMsg = msg;
            ChatLogDatabase.runWithoutMixinSystemLogging(() -> admin.sendSystemMessage(finalMsg));
        }
    }

    private static MutableComponent areaPrefix(Watch w) {
        String ts = TS.format(Instant.now());
        return Component.literal("[" + ts + "] ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("[AREA] ").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal("(" + w.radius + "b) ").withStyle(ChatFormatting.DARK_GRAY));
    }

    public record Watch(boolean enabled, ResourceKey<Level> dimension, double cx, double cy, double cz, int radius, long expiresAtEpochSeconds) {
        public boolean isExpired() {
            return expiresAtEpochSeconds > 0L && (System.currentTimeMillis() / 1000L) > expiresAtEpochSeconds;
        }
    }
}
