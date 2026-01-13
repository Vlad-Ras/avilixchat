package com.roften.multichat.network;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.moderation.Perms;
import com.roften.multichat.admin.AdminChatState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumMap;
import java.util.Map;

/**
 * Sends UI settings (and permission-gated UI flags) to player on login.
 */
@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class UiConfigSyncEvents {
    private UiConfigSyncEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        // Build payload from server config.
        Map<ChatChannel, String> labels = new EnumMap<>(ChatChannel.class);
        Map<ChatChannel, Integer> colors = new EnumMap<>(ChatChannel.class);
        for (ChatChannel ch : ChatChannel.values()) {
            labels.put(ch, MultiChatConfig.getTabLabel(ch));
            colors.put(ch, MultiChatConfig.getTabRgb(ch));
        }

        // ADMIN tab is only shown to players who have the LuckPerms node (or vanilla fallback).
        boolean adminAllowed = Perms.has(sp.createCommandSourceStack(), AdminChatState.NODE_ADMIN_CHAT);

        UiConfigSyncPacket pkt = new UiConfigSyncPacket(
                MultiChatConfig.UI_CHAT_SWITCH_KEY.get(),
                labels,
                colors,
                adminAllowed
        );
        PacketDistributor.sendToPlayer(sp, pkt);
    }
}
