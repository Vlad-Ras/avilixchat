package com.roften.multichat.network;

import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.server.ServerActiveChannelState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> Server: notify what chat tab is currently open on the client.
 */
public record ActiveChannelPacket(int channelOrdinal) implements CustomPacketPayload {

    public static final Type<ActiveChannelPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("avilixchat", "active_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActiveChannelPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ActiveChannelPacket decode(RegistryFriendlyByteBuf buf) {
            return new ActiveChannelPacket(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ActiveChannelPacket pkt) {
            buf.writeVarInt(pkt.channelOrdinal);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final ActiveChannelPacket pkt, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            int ord = pkt == null ? 0 : pkt.channelOrdinal;
            if (ord < 0 || ord >= ChatChannel.values().length) ord = 0;
            ChatChannel ch = ChatChannel.values()[ord];
            if (ctx.player() != null) {
                ServerActiveChannelState.set(ctx.player().getUUID(), ch);
            }
        });
    }
}
