package com.roften.multichat.moderation;

import com.roften.multichat.MultiChatMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Initializes mute system and performs auto-unmute checks on login.
 */
@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class ModerationLifecycleHooks {
    private ModerationLifecycleHooks() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MuteManager.init(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MuteManager.shutdown();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Trigger expiry check
        MuteManager.isMuted(player);
    }
}
