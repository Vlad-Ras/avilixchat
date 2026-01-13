package com.roften.multichat.network;

import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.server.ServerChannelState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> Server: report the client's currently opened chat channel/tab. */
public record ClientActiveChannelPacket(int channelOrdinal) implements CustomPacketPayload {

    public static final Type<ClientActiveChannelPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("avilixchat", "client_active_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientActiveChannelPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ClientActiveChannelPacket decode(RegistryFriendlyByteBuf buf) {
            return new ClientActiveChannelPacket(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ClientActiveChannelPacket pkt) {
            buf.writeVarInt(pkt.channelOrdinal);
        }
    };

    public ClientActiveChannelPacket(ChatChannel ch) {
        this(ch == null ? ChatChannel.GLOBAL.ordinal() : ch.ordinal());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final ClientActiveChannelPacket pkt, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            int ord = pkt.channelOrdinal;
            ChatChannel ch = ChatChannel.GLOBAL;
            if (ord >= 0 && ord < ChatChannel.values().length) {
                ch = ChatChannel.values()[ord];
            }
            ServerChannelState.set(sp, ch);
        }).exceptionally(ex -> {
            // Don't crash server on decoding issues.
            try {
                ctx.player().sendSystemMessage(Component.literal("[AvilixChat] Channel sync failed: " + ex.getMessage()));
            } catch (Throwable ignored) {
            }
            return null;
        });
    }
}
