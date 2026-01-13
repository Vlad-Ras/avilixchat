package com.roften.multichat.network;

import com.roften.multichat.chat.ChatChannel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Small client-only helper: send current channel to the server.
 */
public final class ClientChannelSync {
    private ClientChannelSync() {}

    @OnlyIn(Dist.CLIENT)
    public static void sendCurrentToServer(ChatChannel ch) {
        try {
            PacketDistributor.sendToServer(new ClientActiveChannelPacket(ch));
        } catch (Throwable ignored) {
        }
    }
}
