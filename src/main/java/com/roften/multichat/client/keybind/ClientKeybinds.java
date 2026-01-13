package com.roften.multichat.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.client.ClientChatState;
import com.roften.multichat.client.ui.ClientUiConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/**
 * Client keybind: cycle chat channels (configurable in Controls).
 *
 * Notes:
 * - RegisterKeyMappingsEvent is registered via the mod event bus (see MultiChatMod).
 * - Runtime handling is on the Forge bus (ClientTickEvent) + ChatScreen mixin for open chat.
 */
@EventBusSubscriber(modid = MultiChatMod.MODID, value = Dist.CLIENT)
public final class ClientKeybinds {
    private ClientKeybinds() {}

    public static final String CATEGORY = "key.categories.avilixchat";
    public static final String KEY_CYCLE = "key.avilixchat.cycle_channel";

    public static KeyMapping CYCLE_CHANNEL;

    // Intentionally no "latch" here.
    // In open chat, we react on keyPressed directly. If the user holds the key,
    // OS key-repeat will cycle multiple times (as requested).

    /**
     * Called from the MOD event bus (MultiChatMod constructor on client).
     */
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        // Default is unbound so it doesn't conflict with other mods / vanilla.
        CYCLE_CHANNEL = new KeyMapping(
                KEY_CYCLE,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
        );
        event.register(CYCLE_CHANNEL);
    }

    /**
     * Hotkey support while chat is CLOSED.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (CYCLE_CHANNEL == null) return;

        // Do not switch while user is in other GUIs (inventory, menus, etc.).
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            // Chat open is handled by ChatScreenMixin (so it works even when vanilla captures input).
            return;
        }

        while (CYCLE_CHANNEL.consumeClick()) {
            cycleNow();
        }
    }

    /**
     * Called from ChatScreenMixin so the keybind works while the chat is open.
     */
    public static boolean handleChatScreenKeyPress(int keyCode, int scanCode) {
        if (CYCLE_CHANNEL == null) return false;
        if (!CYCLE_CHANNEL.matches(keyCode, scanCode)) return false;
        cycleNow();
        updateChatHintIfOpen();
        return true;
    }

    public static void cycleNow() {
        boolean allowAdmin = ClientUiConfig.isAdminTabAllowed();
        ChatChannel next = nextChannel(ClientChatState.getCurrent(), allowAdmin);
        ClientChatState.setCurrent(next);
    }

    private static ChatChannel nextChannel(ChatChannel cur, boolean allowAdmin) {
        ChatChannel[] arr = allowAdmin
                ? new ChatChannel[]{ChatChannel.GLOBAL, ChatChannel.LOCAL, ChatChannel.TRADE, ChatChannel.CLAN, ChatChannel.ADMIN}
                : new ChatChannel[]{ChatChannel.GLOBAL, ChatChannel.LOCAL, ChatChannel.TRADE, ChatChannel.CLAN};
        int idx = 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == cur) {
                idx = i;
                break;
            }
        }
        return arr[(idx + 1) % arr.length];
    }

    private static void updateChatHintIfOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ChatScreen chatScreen)) return;
        EditBox input = findChatInput(chatScreen);
        if (input == null) return;
        ChatChannel ch = ClientChatState.getCurrent();
        String label = ClientUiConfig.tabLabel(ch);
        input.setHint(Component.literal("[" + label + "] ").withStyle(ch.color));

        // Prevent disconnects: reserve space for client-side channel prefixes ("#l ", "#t ", ...)
        // so the final outgoing string never exceeds the vanilla 256-char protocol limit.
        try {
            int extra = (ClientChatState.getSendChannel() == ChatChannel.GLOBAL) ? 0 : 3;
            int max = Math.max(1, 256 - extra);
            input.setMaxLength(max);
        } catch (Throwable ignored) {
        }
    }

    /**
     * We intentionally use reflection here because ChatScreen doesn't expose the input field.
     */
    private static EditBox findChatInput(ChatScreen screen) {
        try {
            for (Field f : ChatScreen.class.getDeclaredFields()) {
                if (EditBox.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (v instanceof EditBox eb) return eb;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
