package com.roften.multichat.network;

import com.roften.multichat.MultiChatMod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge 1.21+ networking uses payload handlers (CustomPacketPayload).
 */
public final class NetworkRegistration {
    private NetworkRegistration() {}

    public static final String PROTOCOL = "2";

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MultiChatMod.MODID).versioned(PROTOCOL);
        registrar.playToClient(UiConfigSyncPacket.TYPE, UiConfigSyncPacket.STREAM_CODEC, UiConfigSyncPacket::handle);
        registrar.playToServer(ActiveChannelPacket.TYPE, ActiveChannelPacket.STREAM_CODEC, ActiveChannelPacket::handle);
    }
}
