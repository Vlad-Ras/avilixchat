package com.roften.multichat.mixin;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.client.ui.MergedTabsState;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.client.ClientChatState;
import com.roften.multichat.compat.XaeroWaypointShareCompat;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-only: intercept chat additions at the *final* stage (after other mods had a chance to mutate
 * the component, add click events, etc.).
 *
 * We then route/store messages per-tab and cancel display when the message does not belong
 * to the currently selected channel.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    // Client-side dedupe for Xaero waypoint share. Some servers/mod combos manage to
    // deliver the exact same clickable share line twice; we suppress immediate duplicates.
    private static final long XAERO_CLIENT_DEDUP_WINDOW_MS = 1200L;
    private static final int XAERO_CLIENT_DEDUP_MAX = 64;
    private static final LinkedHashMap<String, Long> XAERO_CLIENT_RECENT = new LinkedHashMap<>(XAERO_CLIENT_DEDUP_MAX, 0.75f, true);

    /**
     * In 1.21.x ChatComponent#addMessage(Component) delegates to the 3-arg overload.
     * If we hook both, we will store/process the same message twice.
     */
    private static final boolean MULTICHAT_HAS_ADD3;
    static {
        boolean has = false;
        try {
            ChatComponent.class.getMethod("addMessage", Component.class, MessageSignature.class, GuiMessageTag.class);
            has = true;
        } catch (Throwable ignored) {
        }
        MULTICHAT_HAS_ADD3 = has;
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void multichat$addMessage_head(Component message, CallbackInfo ci) {
        // Avoid double-handling on 1.21.x where this overload delegates to the 3-arg overload.
        if (MULTICHAT_HAS_ADD3) return;
        if (handle(message, null, null)) {
            ci.cancel();
        }
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void multichat$addMessage_full_head(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (handle(message, signature, tag)) {
            ci.cancel();
        }
    }

    /**
     * When the user enabled "merged tabs" (SHIFT+LMB) and is currently viewing a merged tab,
     * messages that belong to any channel inside the merged set must NOT be cancelled, otherwise
     * the player will only see them after a manual rebuild (switch tab / reopen chat).
     */
    private static boolean shouldHideFromCurrentView(ChatChannel target) {
        if (target == null) return false;
        ChatChannel cur = ClientChatState.getCurrent();
        if (cur == target) return false;
        if (MergedTabsState.isActive() && MergedTabsState.contains(cur) && MergedTabsState.contains(target)) {
            return false;
        }
        return true;
    }

    /**
     * @return true if the message should be hidden from the current tab (i.e. cancel display).
     */
    private static boolean handle(Component msg, MessageSignature signature, GuiMessageTag tag) {
        if (msg == null) return false;
        if (ClientChatState.isRebuilding()) return false;

        // Xaero waypoint share can still end up delivered twice (e.g. a mod broadcasts + also sends per-recipient).
        // Do a lightweight client-side dedupe so players never see doubles.
        if (XaeroWaypointShareCompat.isWaypointShare(msg)) {
            if (isRecentXaeroClientDuplicate(msg)) {
                return true; // cancel display and do not store
            }

            // Xaero shares are clickable system lines. By default we treat clickable lines as "private"
            // (visible in all tabs), but for waypoint shares we want them to belong to a specific channel.
            // If the server tagged the component with our insertion marker, use it.
            // If not tagged (some servers/mods), fall back to GLOBAL so it does NOT leak into every tab.
            Optional<ChatChannel> xaeroCh = ChatChannel.detectFromComponent(msg);
            ChatChannel ch = xaeroCh.orElse(ChatChannel.GLOBAL);
            ClientChatState.remember(ch, msg, signature, tag);
            return shouldHideFromCurrentView(ch);
        }

        // Forced-private marker (used for @mentions): message should be visible in every tab,
        // but ONLY for the recipient that got this marked copy from the server.
        if (hasInsertionToken(msg, "avilixchat:force_private")) {
            ClientChatState.rememberPrivate(msg, signature, tag);
            return false;
        }

        // Spy copies are routed into the tab the admin is currently viewing.
        if (hasInsertionToken(msg, "avilixchat:spy")) {
            ClientChatState.remember(ClientChatState.getCurrent(), msg, signature, tag);
            return false;
        }

        // Admin-mirror copies always belong to ADMIN tab only.
        if (hasInsertionToken(msg, "avilixchat:admin_mirror")) {
            ClientChatState.remember(ChatChannel.ADMIN, msg, signature, tag);
            return shouldHideFromCurrentView(ChatChannel.ADMIN);
        }

        // Prefer insertion-based channel marker (robust), fallback to rendered-text parsing.
        Optional<ChatChannel> ch = ChatChannel.detectFromComponent(msg);
        String plain = null;
        if (ch.isEmpty()) {
            plain = msg.getString();
            // OpenPAC party chat can bypass our server router and still needs to land in CLAN only.
            if (looksLikePartyChat(plain)) {
                ch = Optional.of(ChatChannel.CLAN);
            } else {
                ch = ChatChannel.detectFromRenderedText(plain);
            }
        }

        if (ch.isPresent()) {
            ClientChatState.remember(ch.get(), msg, signature, tag);
            return shouldHideFromCurrentView(ch.get());
        }

        // Private messages should always show in every tab.
        if (isPrivateMessage(msg)) {
            ClientChatState.rememberPrivate(msg, signature, tag);
            return false;
        }

        // Clickable system lines (Corpse etc.) should not disappear when switching tabs.
        // But do not treat every clickable line as private, otherwise party chat (and other mods)
        // would leak into all tabs.
        if (hasClickAction(msg)) {
            ClientChatState.rememberPrivate(msg, signature, tag);
            return false;
        }

        // Default: system message.
        ClientChatState.rememberSystem(msg, signature, tag);

        // System messages can optionally be visible in every tab.
        // If disabled, show them only in GLOBAL (but keep them visible in ADMIN too).
        if (!MultiChatConfig.SHOW_SYSTEM_IN_ALL_TABS.getAsBoolean()) {
            ChatChannel cur = ClientChatState.getCurrent();
            return (cur != ChatChannel.GLOBAL && cur != ChatChannel.ADMIN);
        }
        return false;
    }

    private static boolean isRecentXaeroClientDuplicate(Component msg) {
        String key = "";
        try {
            String plain = msg.getString();
            String cmd = XaeroWaypointShareCompat.firstCommandValue(msg);
            key = (plain == null ? "" : plain) + "|" + (cmd == null ? "" : cmd);
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        synchronized (XAERO_CLIENT_RECENT) {
            long cutoff = now - (XAERO_CLIENT_DEDUP_WINDOW_MS * 2L);
            var it = XAERO_CLIENT_RECENT.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                Long t = e.getValue();
                if (t == null || t < cutoff) it.remove();
            }
            Long prev = XAERO_CLIENT_RECENT.get(key);
            if (prev != null && (now - prev) <= XAERO_CLIENT_DEDUP_WINDOW_MS) {
                return true;
            }
            XAERO_CLIENT_RECENT.put(key, now);
            while (XAERO_CLIENT_RECENT.size() > XAERO_CLIENT_DEDUP_MAX) {
                String eldest = XAERO_CLIENT_RECENT.keySet().iterator().next();
                XAERO_CLIENT_RECENT.remove(eldest);
            }
            return false;
        }
    }

    private static boolean hasInsertionToken(Component root, String token) {
        if (root == null || token == null || token.isEmpty()) return false;
        try {
            String ins = root.getStyle() != null ? root.getStyle().getInsertion() : null;
            if (token.equals(ins)) return true;
        } catch (Throwable ignored) {
        }
        for (Component sib : root.getSiblings()) {
            if (hasInsertionToken(sib, token)) return true;
        }
        return false;
    }

    private static boolean looksLikePartyChat(String s) {
        if (s == null || s.isBlank()) return false;
        String t = s.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.startsWith("[party]")
                || lower.startsWith("[p]")
                || lower.startsWith("party>")
                || lower.contains("[party]")
                || lower.contains(" party]")
                || lower.contains("[клан]")
                || lower.contains("[пати]")
                || lower.contains("[группа]");
    }

    private static boolean isPrivateMessage(Component msg) {
        try {
            if (msg.getContents() instanceof TranslatableContents tc) {
                String key = tc.getKey();
                if (key == null) return false;
                if (key.equals("commands.message.display.incoming")) return true;
                if (key.equals("commands.message.display.outgoing")) return true;
                if (key.equals("commands.msg.display.incoming")) return true;
                if (key.equals("commands.msg.display.outgoing")) return true;
                if (key.contains("commands.message.display.") || key.contains("commands.msg.display.")) return true;
            }
        } catch (Throwable ignored) {
        }

        String s = msg.getString();
        if (s == null) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("[pm]")
                || lower.contains("[dm]")
                || lower.contains("[whisper]")
                || lower.contains("[шепот]");
    }

    private static boolean hasClickAction(Component root) {
        if (root == null) return false;
        try {
            if (root.getStyle() != null && root.getStyle().getClickEvent() != null) {
                var action = root.getStyle().getClickEvent().getAction();
                if (action != null) {
                    String name = action.name();
                    if ("RUN_COMMAND".equals(name) || "SUGGEST_COMMAND".equals(name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        for (Component sib : root.getSiblings()) {
            if (hasClickAction(sib)) return true;
        }
        return false;
    }

    /**
     * Убирает полупрозрачный «трек» скроллбара чата (оставляя сам ползунок), чтобы не было лишней полосы.
     */
    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"),
            index = 4,
            require = 0
    )
    private int multichat$stripScrollbarTrackColor(int color) {
        int a = (color >>> 24) & 0xFF;
        int rgb = color & 0xFFFFFF;
        // В ваниле трек скроллбара рисуется полупрозрачным «светло‑серым/белым».
        // Иногда оттенок не чисто белый, поэтому режем по признакам: низкая альфа + светлый серый.
        if (a > 0 && a < 0xB0) {
            int r = (rgb >>> 16) & 0xFF;
            int g = (rgb >>> 8) & 0xFF;
            int b = rgb & 0xFF;
            boolean greyish = Math.abs(r - g) <= 12 && Math.abs(g - b) <= 12;
            boolean bright = r >= 0x90 && g >= 0x90 && b >= 0x90;
            if (greyish && bright) {
                return 0;
            }
        }
        return color;
    }
}
