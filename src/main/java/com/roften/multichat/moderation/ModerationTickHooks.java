package com.roften.multichat.moderation;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Periodically checks for expired mutes while players are online,
 * so players get an immediate notification when their mute ends.
 */
@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class ModerationTickHooks {
    private ModerationTickHooks() {}

    // check every 30 seconds (20 ticks * 30)
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!MultiChatConfig.MUTES_ENABLED.get()) return;
        if (!MuteManager.isLoaded()) return;

        tickCounter++;
        if (tickCounter < 20 * 30) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null) return;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            // Triggers expiry check and sends auto-unmute message if needed.
            if (MuteManager.getMute(p.getUUID()) != null) {
                MuteManager.isMuted(p);
            }
        }
    }
}
