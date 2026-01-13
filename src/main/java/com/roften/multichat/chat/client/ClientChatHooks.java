package com.roften.multichat.chat.client;

import com.roften.multichat.MultiChatMod;
import com.roften.multichat.chat.ChatChannel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.Locale;

@EventBusSubscriber(modid = MultiChatMod.MODID, value = Dist.CLIENT)
public final class ClientChatHooks {
    private ClientChatHooks() {}

    /** Vanilla protocol limit for outgoing chat strings. */
    private static final int VANILLA_CHAT_MAX = 256;

    @SubscribeEvent
    public static void onClientChatSend(ClientChatEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.isBlank()) return;

        // We'll rewrite into 'out' and apply a final safety clamp.
        String out = raw;

        // Commands are handled by vanilla packets (different limits); don't rewrite them.
        if (out.startsWith("/")) return;

        // Aliases:
        // "!" at the beginning forces GLOBAL (strip the '!').
        if (!out.isEmpty() && out.charAt(0) == '!') {
            out = out.substring(1);
            if (out.startsWith(" ")) out = out.substring(1);
        }

        // IMPORTANT:
        // Do NOT treat any '#' prefix as "channel selection".
        // Players can start messages with HEX colors: "#RRGGBB ...".
        // We only skip when the message already contains our explicit channel selector.
        ChatChannel.ParseResult parsed = ChatChannel.parseOutgoing(out);

        boolean hasExplicitChannelPrefix = parsed.channel() != ChatChannel.GLOBAL && !parsed.message().equals(out);

        // Auto-prefix only if the player didn't type an explicit selector.
        if (!hasExplicitChannelPrefix) {
            ChatChannel sendAs = ClientChatState.getSendChannel();
            if (sendAs != ChatChannel.GLOBAL) {
                String prefix = switch (sendAs) {
                    case LOCAL -> "#l ";
                    case TRADE -> "#t ";
                    case CLAN -> "#c ";
                    case ADMIN -> "#a ";
                    case GLOBAL -> "";
                };
                if (!prefix.isEmpty()) out = prefix + out;
            }
        }

        event.setMessage(out);

        // FINAL SAFETY NET:
        // MultiChat may auto-prefix messages for non-global channels. If the final string exceeds
        // the vanilla network limit, the server will kick the player. Clamp to the protocol max.
        try {
            String finalMsg = event.getMessage();
            if (finalMsg != null && finalMsg.length() > VANILLA_CHAT_MAX) {
                event.setMessage(finalMsg.substring(0, VANILLA_CHAT_MAX));
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("Сообщение было обрезано до " + VANILLA_CHAT_MAX + " символов (лимит протокола).")
                                    .withStyle(ChatFormatting.RED),
                            false
                    );
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // (command sending helper removed)

    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        // Routing/filtering is handled in ChatComponentMixin at the final addMessage(...) stage.
        // Keeping this event hook (empty) avoids version-to-version differences where some mods
        // expect the event class to be loaded.
    }
}
