package com.roften.multichat.chat.server;

import com.roften.multichat.chat.ChatChannel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side "what tab is currently opened on the client" state.
 *
 * Used only for compat cases where the server needs to attribute a SYSTEM broadcast
 * (e.g. Xaero waypoint share) to the sender's active chat channel.
 */
public final class ServerChannelState {
    private static final ConcurrentHashMap<UUID, ChatChannel> CURRENT = new ConcurrentHashMap<>();

    private ServerChannelState() {}

    public static void set(ServerPlayer player, ChatChannel channel) {
        if (player == null) return;
        if (channel == null) channel = ChatChannel.GLOBAL;
        CURRENT.put(player.getUUID(), channel);
    }

    public static ChatChannel get(ServerPlayer player) {
        if (player == null) return ChatChannel.GLOBAL;
        return CURRENT.getOrDefault(player.getUUID(), ChatChannel.GLOBAL);
    }

    public static void clear(ServerPlayer player) {
        if (player == null) return;
        CURRENT.remove(player.getUUID());
    }
}
