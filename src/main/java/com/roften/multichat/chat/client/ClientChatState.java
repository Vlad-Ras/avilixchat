package com.roften.multichat.chat.client;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.chat.ChatChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.neoforged.neoforge.network.PacketDistributor;

import com.roften.multichat.client.ui.MergedTabsState;

import com.roften.multichat.network.ActiveChannelPacket;

import java.util.ArrayDeque;
import java.util.EnumMap;

/**
 * Client-side filtered chat view with per-tab unread markers.
 */
public final class ClientChatState {
    private static final int MAX_ALL = 4096;

    private static ChatChannel current = ChatChannel.GLOBAL;

    private enum Kind { CHANNEL, SYSTEM, PRIVATE }

    /**
     * Stored chat entry.
     *
     * We keep signature/tag when available so that when we rebuild the chat (switching tabs)
     * we re-add messages with the same metadata. This is critical for compatibility with mods
     * like ChatHeads and for click actions that depend on the original trust context.
     */
    private record Entry(Kind kind, ChatChannel channel, Component msg, MessageSignature signature, GuiMessageTag tag) {}

    /** Chronological combined history (channel + system + private). */
    private static final ArrayDeque<Entry> all = new ArrayDeque<>();

    /** Guard: while rebuilding we must NOT re-store/re-filter our own re-added messages. */
    private static final ThreadLocal<Boolean> REBUILDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Per-channel unread counters (client-only). */
    private static final EnumMap<ChatChannel, Integer> unread = new EnumMap<>(ChatChannel.class);

    static {
        for (ChatChannel ch : ChatChannel.values()) unread.put(ch, 0);
    }

    private ClientChatState() {}

    public static ChatChannel getCurrent() {
        return current;
    }

    /** Channel that outgoing messages should be routed as while on the client. */
    public static ChatChannel getSendChannel() {
        return current;
    }

    public static void setCurrent(ChatChannel ch) {
        if (ch == null) return;
        if (ch == current) return;
        current = ch;
        clearUnread(ch);
        syncActiveChannel();
        rebuildChat();
    }

    private static void syncActiveChannel() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;
            // Connection check prevents noisy errors during early client init.
            if (mc.getConnection() == null) return;
            PacketDistributor.sendToServer(new ActiveChannelPacket(current.ordinal()));
        } catch (Throwable ignored) {
        }
    }

    public static int getUnread(ChatChannel channel) {
        if (channel == null) return 0;
        return unread.getOrDefault(channel, 0);
    }

    public static boolean hasUnread(ChatChannel channel) {
        return getUnread(channel) > 0;
    }

    public static void clearUnread(ChatChannel channel) {
        if (channel == null) return;
        unread.put(channel, 0);
    }

    private static void incUnread(ChatChannel channel) {
        if (channel == null) return;
        unread.put(channel, unread.getOrDefault(channel, 0) + 1);
    }

    public static void rememberSystem(Component msg) {
        rememberSystem(msg, null, null);
    }

    public static void rememberSystem(Component msg, MessageSignature signature, GuiMessageTag tag) {
        push(new Entry(Kind.SYSTEM, null, msg, signature, tag));

        // If system messages are NOT shown in all tabs, they effectively belong to GLOBAL.
        if (!MultiChatConfig.SHOW_SYSTEM_IN_ALL_TABS.getAsBoolean()) {
            if (current != ChatChannel.GLOBAL) {
                incUnread(ChatChannel.GLOBAL);
            }
        }
    }

    public static void rememberPrivate(Component msg) {
        rememberPrivate(msg, null, null);
    }

    public static void rememberPrivate(Component msg, MessageSignature signature, GuiMessageTag tag) {
        push(new Entry(Kind.PRIVATE, null, msg, signature, tag));
        // Private messages show everywhere -> no unread markers.
    }

    public static void remember(ChatChannel channel, Component msg) {
        remember(channel, msg, null, null);
    }

    public static void remember(ChatChannel channel, Component msg, MessageSignature signature, GuiMessageTag tag) {
        if (channel == null) {
            rememberSystem(msg, signature, tag);
            return;
        }
        push(new Entry(Kind.CHANNEL, channel, msg, signature, tag));

        if (current != channel) {
            incUnread(channel);
        }
    }

    private static void push(Entry e) {
        if (e == null || e.msg == null) return;
        all.addLast(e);
        while (all.size() > MAX_ALL) {
            all.removeFirst();
        }
    }

    public static boolean isRebuilding() {
        return REBUILDING.get();
    }

    public static void rebuildChat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;

        REBUILDING.set(Boolean.TRUE);
        try {

        Object chat = mc.gui.getChat();
        try {
            // IMPORTANT: must fully clear the chat component before rebuilding,
            // otherwise lines remain and we append duplicates on every tab switch.
            chat.getClass().getMethod("clearMessages", boolean.class).invoke(chat, true);
        } catch (Throwable ignored) {
            try {
                chat.getClass().getMethod("clearMessages").invoke(chat);
            } catch (Throwable ignored2) {
                // Worst case: duplicates until relog.
            }
        }

        final boolean showSystemInAll = MultiChatConfig.SHOW_SYSTEM_IN_ALL_TABS.getAsBoolean();
        final boolean mergedView = MergedTabsState.isActive() && MergedTabsState.contains(current);
        if (mergedView) {
            // When the merged view is visible, consider those channels "read".
            for (ChatChannel ch : MergedTabsState.snapshot()) {
                clearUnread(ch);
            }
        }

        // Prefer the 3-arg addMessage method when available.
        // (Component, MessageSignature, GuiMessageTag)
        java.lang.reflect.Method add3 = null;
        try {
            add3 = chat.getClass().getMethod("addMessage", Component.class, MessageSignature.class, GuiMessageTag.class);
        } catch (Throwable ignored) {
        }

        for (Entry e : all) {
            if (e.msg == null) continue;

            switch (e.kind) {
                case PRIVATE -> addMessage(chat, add3, e);
                case SYSTEM -> {
                    if (showSystemInAll || current == ChatChannel.GLOBAL || current == ChatChannel.ADMIN) {
                        addMessage(chat, add3, e);
                    }
                }
                case CHANNEL -> {
                    if (mergedView) {
                        if (e.channel != null && MergedTabsState.contains(e.channel)) {
                            addMessage(chat, add3, e);
                        }
                    } else {
                        if (e.channel == current) {
                            addMessage(chat, add3, e);
                        }
                    }
                }
            }
        }

        } finally {
            REBUILDING.set(Boolean.FALSE);
        }
    }

    private static void addMessage(Object chat, java.lang.reflect.Method add3, Entry e) {
        if (chat == null || e == null || e.msg == null) return;

        // IMPORTANT (ChatHeads compat): calling ChatComponent.addMessage(...) directly bypasses
        // ChatListener, so ChatHeads (and some other chat mods) won't be notified about the sender.
        // When we rebuild the chat (switch tabs / reopen chat), use ChatListener.handleSystemMessage
        // if available so those mods can re-attach their per-line metadata (heads, etc.).
        if (tryAddThroughChatListener(e.msg)) {
            return;
        }

        try {
            // Only use the 3-arg overload when we actually have metadata.
            // Passing (null,null) can change how vanilla and some mods classify the line.
            if (add3 != null && (e.signature != null || e.tag != null)) {
                add3.invoke(chat, e.msg, e.signature, e.tag);
                return;
            }
        } catch (Throwable ignored) {
        }
        // Fallback: basic addMessage(Component)
        try {
            chat.getClass().getMethod("addMessage", Component.class).invoke(chat, e.msg);
        } catch (Throwable ignored) {
            try {
                Minecraft.getInstance().gui.getChat().addMessage(e.msg);
            } catch (Throwable ignored2) {
            }
        }
    }

    private static boolean tryAddThroughChatListener(Component msg) {
        if (msg == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;

        Object listener = null;
        try {
            // Preferred: Minecraft#getChatListener()
            try {
                listener = mc.getClass().getMethod("getChatListener").invoke(mc);
            } catch (Throwable ignored) {
                // Fallback: reflectively find a field of type ChatListener
                for (java.lang.reflect.Field f : mc.getClass().getDeclaredFields()) {
                    if (!ChatListener.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    listener = f.get(mc);
                    break;
                }
            }

            if (listener == null) return false;

            // Most common signature: handleSystemMessage(Component, boolean)
            try {
                listener.getClass().getMethod("handleSystemMessage", Component.class, boolean.class)
                        .invoke(listener, msg, false);
                return true;
            } catch (Throwable ignored) {
            }

            // Some builds might have a 1-arg overload: handleSystemMessage(Component)
            try {
                listener.getClass().getMethod("handleSystemMessage", Component.class)
                        .invoke(listener, msg);
                return true;
            } catch (Throwable ignored) {
            }

            // Last resort: scan for any handleSystemMessage(...) method with Component as first arg.
            for (java.lang.reflect.Method m : listener.getClass().getMethods()) {
                if (!m.getName().equals("handleSystemMessage")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 1) continue;
                if (pt[0] != Component.class) continue;
                try {
                    Object[] args = new Object[pt.length];
                    args[0] = msg;
                    // Fill remaining args with safe defaults.
                    for (int i = 1; i < pt.length; i++) {
                        if (pt[i] == boolean.class) args[i] = false;
                        else args[i] = null;
                    }
                    m.invoke(listener, args);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
