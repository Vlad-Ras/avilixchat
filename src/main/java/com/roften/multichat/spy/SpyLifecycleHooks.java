package com.roften.multichat.spy;

import com.roften.multichat.MultiChatMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Restores and persists /avilixchat spy state across server restarts.
 */
@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class SpyLifecycleHooks {
    private SpyLifecycleHooks() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        // Load enabled spies from disk so the state survives server restarts.
        SpyState.loadPersisted();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SpyState.savePersisted();
    }
}
