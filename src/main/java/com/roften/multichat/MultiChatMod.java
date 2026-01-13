package com.roften.multichat;

import com.mojang.logging.LogUtils;
import com.roften.multichat.commands.AvilixChatCommands;
import com.roften.multichat.client.keybind.ClientKeybinds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import com.roften.multichat.network.NetworkRegistration;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(MultiChatMod.MODID)
public class MultiChatMod {
    public static final String MODID = "avilixchat";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MultiChatMod(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MultiChatConfig.SPEC);
        modEventBus.addListener((RegisterPayloadHandlersEvent e) -> NetworkRegistration.register(e));

        // GAME bus: commands must be registered on the main NeoForge event bus.
        // (Annotations can be fragile across versions; this is deterministic.)
        NeoForge.EVENT_BUS.addListener(AvilixChatCommands::onRegisterCommands);

        // Client-only: register key mappings on the MOD event bus.
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(ClientKeybinds::registerKeyMappings);
        }
    }
}
