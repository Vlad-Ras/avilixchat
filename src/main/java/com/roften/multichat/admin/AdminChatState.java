package com.roften.multichat.admin;

import com.roften.multichat.moderation.Perms;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session ADMIN chat state.
 *
 * <p>Two different features are intentionally separated:</p>
 * <ul>
 *   <li><b>ADMIN chat</b>: a private channel that only admins can see & write to.</li>
 *   <li><b>Admin mirror</b>: copies of messages from other channels that are delivered into ADMIN chat.
 *       This mirror can be disabled per admin via command.</li>
 * </ul>
 */
public final class AdminChatState {
    private AdminChatState() {}

    /** LuckPerms node that gates ADMIN chat (visibility + writing). */
    public static final String NODE_ADMIN_CHAT = "avilixchat.adminchat";

    /**
     * Players that DISABLED the admin-mirror for this session.
     * Default behavior is ON (i.e., not present in this set).
     */
    private static final Set<UUID> MIRROR_DISABLED = ConcurrentHashMap.newKeySet();

    public static boolean hasAdminChatPermission(ServerPlayer player) {
        if (player == null) return false;
        return Perms.has(player.createCommandSourceStack(), NODE_ADMIN_CHAT);
    }

    /** Returns whether this player should receive mirrored messages into ADMIN chat. */
    public static boolean isMirrorEnabled(ServerPlayer player) {
        if (player == null) return false;
        if (!hasAdminChatPermission(player)) {
            MIRROR_DISABLED.remove(player.getUUID());
            return false;
        }
        return !MIRROR_DISABLED.contains(player.getUUID());
    }

    public static boolean toggleMirror(ServerPlayer player) {
        if (player == null) return false;
        if (!hasAdminChatPermission(player)) {
            MIRROR_DISABLED.remove(player.getUUID());
            return false;
        }
        UUID id = player.getUUID();
        if (MIRROR_DISABLED.contains(id)) {
            MIRROR_DISABLED.remove(id);
            return true;
        }
        MIRROR_DISABLED.add(id);
        return false;
    }

    public static void setMirror(ServerPlayer player, boolean on) {
        if (player == null) return;
        if (!hasAdminChatPermission(player)) {
            MIRROR_DISABLED.remove(player.getUUID());
            return;
        }
        if (on) MIRROR_DISABLED.remove(player.getUUID());
        else MIRROR_DISABLED.add(player.getUUID());
    }

    /** Marks a component as "admin mirror" so the client routes it into ADMIN tab only. */
    public static MutableComponent markAdminMirror(Component original) {
        return Component.empty()
                .withStyle(s -> s.withInsertion("avilixchat:admin_mirror"))
                .append(original);
    }
}
