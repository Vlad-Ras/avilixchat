package com.roften.multichat.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.Optional;

public enum ChatChannel {
    GLOBAL("G", "avilixchat.tab.global", ChatFormatting.GRAY,
            new String[]{"#g ", "#global ", "#г ", "#глоб "}),
    LOCAL("L", "avilixchat.tab.local", ChatFormatting.GREEN,
            new String[]{"#l ", "#local ", "#лок ", "#локал "}),
    TRADE("T", "avilixchat.tab.trade", ChatFormatting.GOLD,
            new String[]{"#t ", "#trade ", "#тр ", "#торг "}),
    CLAN("C", "avilixchat.tab.clan", ChatFormatting.AQUA,
            new String[]{"#c ", "#clan ", "#party ", "#к ", "#клан "}),
    ADMIN("A", "avilixchat.tab.admin", ChatFormatting.RED,
            new String[]{"#a ", "#admin ", "#адм ", "#админ "});

    public final String shortTag;
    public final String tabKey;
    public final ChatFormatting color;
    private final String[] inputPrefixes;

    ChatChannel(String shortTag, String tabKey, ChatFormatting color, String[] inputPrefixes) {
        this.shortTag = shortTag;
        this.tabKey = tabKey;
        this.color = color;
        this.inputPrefixes = inputPrefixes;
    }

    public Component tabTitle() {
        return Component.translatable(tabKey);
    }

    public MutableComponent channelBadge() {
        // Mark the badge with an insertion token so the client can detect the channel
        // without relying on rendered text parsing (which can break with custom formatting).
        // The visible label is configurable (see MultiChatConfig UI_TAB_*).
        String label = com.roften.multichat.MultiChatConfig.getTabLabel(this);
        return Component.literal("[" + label + "]")
                .withStyle(color)
                .withStyle(s -> s.withInsertion("avilixchat:channel=" + shortTag));
    }

    /**
     * Parses a raw typed message like "#l hello" into (channel, "hello").
     * If no prefix is detected, GLOBAL is assumed.
     */
    public static ParseResult parseOutgoing(String raw) {
        if (raw == null) return new ParseResult(GLOBAL, "");

        // Aliases:
        // "!" at the beginning forces GLOBAL (common across many chat systems).
        // Example: "! hello" -> GLOBAL "hello".
        if (!raw.isEmpty() && raw.charAt(0) == '!') {
            String msg = raw.substring(1);
            if (msg.startsWith(" ")) msg = msg.substring(1);
            return new ParseResult(GLOBAL, msg);
        }

        String trimmed = raw;

        // Preferred: configurable switch key (default '$'): "$g hello", "$l hello", "$t hello", "$c hello", "$a hello"
        String key = com.roften.multichat.MultiChatConfig.UI_CHAT_SWITCH_KEY.get();
        if (key != null && !key.isEmpty() && raw.startsWith(key) && raw.length() > key.length()) {
            String rest = raw.substring(key.length());
            if (!rest.isEmpty()) {
                char c = Character.toLowerCase(rest.charAt(0));
                ChatChannel ch = switch (c) {
                    case 'g', 'г' -> GLOBAL;
                    case 'l', 'л' -> LOCAL;
                    case 't', 'т' -> TRADE;
                    case 'c', 'к' -> CLAN;
                    case 'a', 'а' -> ADMIN;
                    default -> null;
                };
                if (ch != null) {
                    String msg = rest.substring(1);
                    if (msg.startsWith(" ")) msg = msg.substring(1);
                    return new ParseResult(ch, msg);
                }
            }
        }

        // Backward compatible legacy prefixes (old '#g ', '#local ', etc.)
        String lower = raw.toLowerCase(Locale.ROOT);
        for (ChatChannel ch : values()) {
            for (String p : ch.inputPrefixes) {
                if (lower.startsWith(p)) {
                    trimmed = raw.substring(p.length());
                    return new ParseResult(ch, trimmed);
                }
            }
        }
        return new ParseResult(GLOBAL, raw);
    }

    /**
     * Detects a channel from an already-formatted displayed message string.
     * Expected format: "[HH:mm:ss] [X] ...".
     */
    public static Optional<ChatChannel> detectFromRenderedText(String text) {
        int idx = text.indexOf("] [");
        if (idx == -1) return Optional.empty();
        int start = idx + 3;
        int end = text.indexOf(']', start);
        if (end == -1) return Optional.empty();
        String tag = text.substring(start, end);
        for (ChatChannel ch : values()) {
            if (ch.shortTag.equalsIgnoreCase(tag)) return Optional.of(ch);
        }
        return Optional.empty();
    }

    /**
     * Detects a channel from the component structure.
     *
     * We store a hidden insertion token on the channel badge (see {@link #channelBadge()})
     * which is much more reliable than parsing the rendered text.
     */
    public static Optional<ChatChannel> detectFromComponent(Component root) {
        if (root == null) return Optional.empty();
        // Fast path: scan root + its siblings (our formatted chat is a root component with appended siblings)
        Optional<ChatChannel> found = scanInsertionRecursive(root);
        if (found.isPresent()) return found;
        for (Component sib : root.getSiblings()) {
            found = scanInsertionRecursive(sib);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private static Optional<ChatChannel> scanInsertionRecursive(Component c) {
        if (c == null) return Optional.empty();
        try {
            String ins = c.getStyle().getInsertion();
            if (ins != null && ins.startsWith("avilixchat:channel=")) {
                String tag = ins.substring("avilixchat:channel=".length());
                for (ChatChannel ch : values()) {
                    if (ch.shortTag.equalsIgnoreCase(tag)) return Optional.of(ch);
                }
            }
        } catch (Throwable ignored) {
        }
        // Recurse into siblings
        for (Component sib : c.getSiblings()) {
            Optional<ChatChannel> f = scanInsertionRecursive(sib);
            if (f.isPresent()) return f;
        }
        return Optional.empty();
    }

    public record ParseResult(ChatChannel channel, String message) {}
}
