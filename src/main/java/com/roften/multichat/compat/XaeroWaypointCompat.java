package com.roften.multichat.compat;

import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.server.ServerActiveChannelState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;

/**
 * Compatibility: Xaero's Minimap / WorldMap waypoint share.
 *
 * Xaero broadcasts a SYSTEM message to all players when someone shares a waypoint.
 * Our chat tabs route SYSTEM messages in a special way, so we "re-wrap" that broadcast
 * with an invisible channel marker so the message lands in the same tab the sender had opened.
 */
public final class XaeroWaypointCompat {
    private XaeroWaypointCompat() {}

    private record Pending(UUID uuid, ChatChannel channel, long timeMs) {}
    private static volatile Pending PENDING;

    /** Called when we suspect a player initiated a waypoint share (chat payload or command). */
    public static void noteCandidate(ServerPlayer player) {
        if (player == null) return;
        // Use the actively-opened tab reported by the client.
        ChatChannel ch = ServerActiveChannelState.getOrDefault(player.getUUID(), ChatChannel.GLOBAL);
        PENDING = new Pending(player.getUUID(), ch, System.currentTimeMillis());
    }

    /**
     * Consume the pending share channel (expires quickly).
     * Returns null if there is no recent candidate.
     */
    public static ChatChannel consumePendingShareChannel() {
        Pending p = PENDING;
        if (p == null) return null;
        long age = System.currentTimeMillis() - p.timeMs;
        if (age > 3000L) {
            PENDING = null;
            return null;
        }
        PENDING = null;
        return p.channel;
    }

    /**
     * Peek the pending share channel without consuming it.
     *
     * <p>Used for Xaero builds that send waypoint share lines per-recipient (sendSystemMessage)
     * without including the sharer name in the text.</p>
     */
    public static ChatChannel peekPendingShareChannel() {
        Pending p = PENDING;
        if (p == null) return null;
        long age = System.currentTimeMillis() - p.timeMs;
        if (age > 3000L) {
            PENDING = null;
            return null;
        }
        return p.channel;
    }

    /** Peek the pending sharer UUID without consuming it (may be null). */
    public static UUID peekPendingSharerUuid() {
        Pending p = PENDING;
        if (p == null) return null;
        long age = System.currentTimeMillis() - p.timeMs;
        if (age > 3000L) {
            PENDING = null;
            return null;
        }
        return p.uuid;
    }

    /** Heuristic: check if this SYSTEM broadcast looks like Xaero waypoint share output. */
    public static boolean looksLikeWaypointShareBroadcast(Component message) {
        if (message == null) return false;

        String text = message.getString();
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);

        // Language-independent keywords.
        boolean hasKeyword = lower.contains("waypoint")
                || lower.contains("xaero")
                || lower.contains("метк")
                || lower.contains("точк")
                || lower.contains("координ");

        if (!hasKeyword) return false;

        // Prefer strong signal: a click event that runs/suggests a Xaero command.
        boolean hasXaeroClick = hasClickValue(message, v -> {
            if (v == null) return false;
            String vv = v.toLowerCase(Locale.ROOT);
            return vv.contains("xaero") || vv.contains("waypoint");
        });

        if (hasXaeroClick) return true;

        // Fallback: match common English text.
        return lower.contains("shared") && lower.contains("waypoint");
    }

    private interface StrPred { boolean test(String v); }

    private static boolean hasClickValue(Component c, StrPred pred) {
        if (c == null) return false;
        try {
            Style st = c.getStyle();
            if (st != null) {
                ClickEvent ce = st.getClickEvent();
                if (ce != null) {
                    String v = ce.getValue();
                    if (pred.test(v)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        for (Component sib : c.getSiblings()) {
            if (hasClickValue(sib, pred)) return true;
        }
        return false;
    }
}
