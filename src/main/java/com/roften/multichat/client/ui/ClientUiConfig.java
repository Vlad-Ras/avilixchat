package com.roften.multichat.client.ui;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.chat.ChatChannel;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Client-side UI settings (tab labels, colors, switch key).
 *
 * <p>Defaults are read from client config. On dedicated servers, these can be overridden
 * by a server->client sync packet.</p>
 */
public final class ClientUiConfig {
    private ClientUiConfig() {}

    private static volatile String chatSwitchKey = "$";

    /** Whether the ADMIN tab (A) should be shown for this client (server-controlled). */
    private static volatile boolean adminTabAllowed = false;

    private static final Map<ChatChannel, String> tabLabels = new EnumMap<>(ChatChannel.class);
    private static final Map<ChatChannel, Integer> tabColors = new EnumMap<>(ChatChannel.class);

    static {
        reloadFromLocalConfig();
    }

    public static void reloadFromLocalConfig() {
        chatSwitchKey = MultiChatConfig.UI_CHAT_SWITCH_KEY.get();
        for (ChatChannel ch : ChatChannel.values()) {
            tabLabels.put(ch, MultiChatConfig.getTabLabel(ch));
            tabColors.put(ch, MultiChatConfig.getTabRgb(ch));
        }
        // Local config cannot determine LuckPerms permissions. Keep strict default.
        adminTabAllowed = false;
    }

    public static void applyServerSync(String switchKey,
                                       Map<ChatChannel, String> labels,
                                       Map<ChatChannel, Integer> colors,
                                       boolean adminAllowed) {
        if (switchKey != null && !switchKey.isBlank()) {
            chatSwitchKey = switchKey;
        }
        if (labels != null) {
            tabLabels.putAll(labels);
        }
        if (colors != null) {
            tabColors.putAll(colors);
        }
        adminTabAllowed = adminAllowed;
    }

    public static String chatSwitchKey() {
        return chatSwitchKey;
    }

    public static boolean isAdminTabAllowed() {
        return adminTabAllowed;
    }

    public static String tabLabel(ChatChannel channel) {
        return tabLabels.getOrDefault(channel, channel.shortTag);
    }

    public static int tabRgb(ChatChannel channel) {
        return tabColors.getOrDefault(channel, 0xAAAAAA);
    }

    @Nullable
    public static TextColor tabTextColor(ChatChannel channel) {
        return TextColor.fromRgb(tabRgb(channel));
    }
}
