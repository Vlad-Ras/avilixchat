package com.roften.multichat.network;

import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.client.ui.ClientUiConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * Server -> Client UI settings sync.
 */
public record UiConfigSyncPacket(
        String switchKey,
        Map<ChatChannel, String> tabLabels,
        Map<ChatChannel, Integer> tabColors,
        boolean adminTabAllowed
) implements CustomPacketPayload {

    public static final Type<UiConfigSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("avilixchat", "ui_config_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UiConfigSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public UiConfigSyncPacket decode(RegistryFriendlyByteBuf buf) {
            String key = buf.readUtf(16);

            int n = buf.readVarInt();
            Map<ChatChannel, String> labels = new EnumMap<>(ChatChannel.class);
            for (int i = 0; i < n; i++) {
                ChatChannel ch = ChatChannel.values()[buf.readVarInt()];
                labels.put(ch, buf.readUtf(16));
            }

            int m = buf.readVarInt();
            Map<ChatChannel, Integer> colors = new EnumMap<>(ChatChannel.class);
            for (int i = 0; i < m; i++) {
                ChatChannel ch = ChatChannel.values()[buf.readVarInt()];
                colors.put(ch, buf.readInt());
            }

            boolean adminAllowed = buf.readBoolean();
            return new UiConfigSyncPacket(key, labels, colors, adminAllowed);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, UiConfigSyncPacket pkt) {
            buf.writeUtf(pkt.switchKey == null ? "$" : pkt.switchKey, 16);

            buf.writeVarInt(pkt.tabLabels == null ? 0 : pkt.tabLabels.size());
            if (pkt.tabLabels != null) {
                for (var e : pkt.tabLabels.entrySet()) {
                    buf.writeVarInt(e.getKey().ordinal());
                    buf.writeUtf(e.getValue(), 16);
                }
            }

            buf.writeVarInt(pkt.tabColors == null ? 0 : pkt.tabColors.size());
            if (pkt.tabColors != null) {
                for (var e : pkt.tabColors.entrySet()) {
                    buf.writeVarInt(e.getKey().ordinal());
                    buf.writeInt(e.getValue());
                }
            }

            buf.writeBoolean(pkt.adminTabAllowed);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final UiConfigSyncPacket pkt, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientUiConfig.applyServerSync(pkt.switchKey, pkt.tabLabels, pkt.tabColors, pkt.adminTabAllowed);
        }).exceptionally(ex -> {
            // Don't crash client for sync errors
            ctx.player().sendSystemMessage(Component.literal("[AvilixChat] UI sync failed: " + ex.getMessage()));
            return null;
        });
    }
}
