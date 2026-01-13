package com.roften.multichat.db;

import com.roften.multichat.MultiChatMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Ensures the database is initialized early and closed cleanly on server stop.
 */
@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class ChatLogLifecycleHooks {
    private ChatLogLifecycleHooks() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ChatLogDatabase.init(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ChatLogDatabase.shutdown();
    }
}
