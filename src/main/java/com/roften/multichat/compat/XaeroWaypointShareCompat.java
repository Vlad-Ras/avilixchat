package com.roften.multichat.compat;

import com.roften.multichat.chat.ChatChannel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * Best-effort compatibility for Xaero's "share waypoint" chat line.
 *
 * <p>The line is usually a system broadcast with a clickable [Add] button.
 * We detect it via waypoint keywords + RUN_COMMAND/SUGGEST_COMMAND click events containing "xaero/waypoint".
 * When detected, we can tag it with a hidden insertion token so the client routes it
 * into a specific chat tab (channel) without changing the visible text.</p>
 */
public final class XaeroWaypointShareCompat {
    private XaeroWaypointShareCompat() {}

    public static boolean isWaypointShare(Component message) {
        if (message == null) return false;
        // Strong signal: a click event that runs/suggests a Xaero command.
        // Some Xaero builds do NOT include the word "waypoint" in the visible text,
        // especially on localized servers, but the click command still contains "xaero".
        if (hasCommandContaining(message, "xaero")) return true;
        if (hasCommandContaining(message, "waypoint")) return true;

        // Fallback heuristic: broad text hint + any click.
        String s = message.getString();
        if (s == null) s = "";
        String lower = s.toLowerCase(Locale.ROOT);
        boolean textHint = lower.contains("waypoint")
                || lower.contains("xaero")
                || lower.contains("метк")
                || lower.contains("точк")
                || lower.contains("коорд")
                || lower.contains("координ");

        return textHint && hasAnyClickEvent(message);
    }

    private static boolean hasAnyClickEvent(Component root) {
        if (root == null) return false;
        try {
            Style st = root.getStyle();
            if (st != null && st.getClickEvent() != null) return true;
        } catch (Throwable ignored) {
        }
        for (Component sib : root.getSiblings()) {
            if (hasAnyClickEvent(sib)) return true;
        }
        return false;
    }

    /** First click command value (RUN_COMMAND or SUGGEST_COMMAND) if present. */
    public static String firstCommandValue(Component root) {
        if (root == null) return null;
        try {
            Style st = root.getStyle();
            if (st != null) {
                ClickEvent ce = st.getClickEvent();
                if (ce != null) {
                    ClickEvent.Action a = ce.getAction();
                    if (a == ClickEvent.Action.RUN_COMMAND || a == ClickEvent.Action.SUGGEST_COMMAND) {
                        return ce.getValue();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        for (Component sib : root.getSiblings()) {
            String v = firstCommandValue(sib);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Attempts to find the sharer by matching any online player name
     * against the message visible string or the first RUN_COMMAND value.
     */
    public static ServerPlayer findSharer(MinecraftServer server, Component message) {
        if (server == null || message == null) return null;

        String plain = message.getString();
        if (plain == null) plain = "";
        String plainLower = plain.toLowerCase(Locale.ROOT);

        String cmd = firstCommandValue(message);
        String cmdLower = cmd == null ? "" : cmd.toLowerCase(Locale.ROOT);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String name = p.getGameProfile().getName();
            if (name == null || name.isBlank()) continue;
            String n = name.toLowerCase(Locale.ROOT);
            if (plainLower.contains(n) || cmdLower.contains(n)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Tags the original component with a hidden insertion token
     * that the client uses to route/store the line in a channel.
     */
    public static Component tagChannel(ChatChannel ch, Component original) {
        if (ch == null || original == null) return original;
        // IMPORTANT:
        // A completely empty component can be optimized away during some transformations.
        // Use a zero-width space to keep the style (insertion) reliably present on the client.
        return Component.literal("\u200B")
                .withStyle(s -> s.withInsertion("avilixchat:channel=" + ch.shortTag))
                .append(original);
    }

    private static boolean hasCommandContaining(Component root, String needle) {
        if (root == null || needle == null || needle.isEmpty()) return false;
        String n = needle.toLowerCase(Locale.ROOT);

        try {
            Style st = root.getStyle();
            if (st != null) {
                ClickEvent ce = st.getClickEvent();
                if (ce != null) {
                    ClickEvent.Action a = ce.getAction();
                    if (a != ClickEvent.Action.RUN_COMMAND && a != ClickEvent.Action.SUGGEST_COMMAND) {
                        // ignore
                    } else {
                    String v = ce.getValue();
                    if (v != null && v.toLowerCase(Locale.ROOT).contains(n)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        for (Component sib : root.getSiblings()) {
            if (hasCommandContaining(sib, needle)) return true;
        }
        return false;
    }
}
