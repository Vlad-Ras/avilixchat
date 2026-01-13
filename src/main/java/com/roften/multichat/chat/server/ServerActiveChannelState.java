package com.roften.multichat.chat.server;

import com.roften.multichat.chat.ChatChannel;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side memory of what chat tab a player currently has open.
 *
 * <p>Updated by client packets whenever the user switches tabs. Used to route
 * certain mod system messages (e.g. Xaero's waypoint share) into the channel
 * the sender had open at the moment of sending.</p>
 */
public final class ServerActiveChannelState {
    private static final ConcurrentHashMap<UUID, ChatChannel> ACTIVE = new ConcurrentHashMap<>();

    private ServerActiveChannelState() {}

    public static void set(UUID playerId, ChatChannel ch) {
        if (playerId == null || ch == null) return;
        ACTIVE.put(playerId, ch);
    }

    public static ChatChannel getOrDefault(UUID playerId, ChatChannel def) {
        if (playerId == null) return def;
        ChatChannel v = ACTIVE.get(playerId);
        return v == null ? def : v;
    }

    public static void remove(UUID playerId) {
        if (playerId == null) return;
        ACTIVE.remove(playerId);
    }
}
