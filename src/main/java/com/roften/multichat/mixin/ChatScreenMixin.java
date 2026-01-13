package com.roften.multichat.mixin;

import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.client.ClientChatState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.roften.multichat.client.ui.ChatTab;
import com.roften.multichat.client.ui.ClientUiConfig;
import com.roften.multichat.client.ui.MergedTabsState;
import com.roften.multichat.client.ui.MentionSuggestState;
import com.roften.multichat.client.keybind.ClientKeybinds;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Client-only: renders compact channel tabs and handles clicks without stealing focus
 * from the chat input box. This avoids the "can't type after switching tab" issue.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    /**
     * Vanilla network limit (Serverbound chat packet).
     * If the client sends a longer string, the server disconnects the player
     * before any mod-side routing can run.
     */
    private static final int VANILLA_CHAT_MAX = 256;
    // IMPORTANT: do not declare helper classes inside the mixin package.
    // Mixin forbids directly loading non-mixin classes from a mixin package.
    private static final WeakHashMap<ChatScreen, List<ChatTab>> MULTICHAT_TABS = new WeakHashMap<>();
    private static final WeakHashMap<ChatScreen, MentionSuggestState> MENTION_STATE = new WeakHashMap<>();

    // Compact UI, placed at the LEFT start of the input line.
    private static final int TAB_W = 18;
    private static final int TAB_H = 10;
    private static final int TAB_GAP = 2;
    private static final int TAB_Y_OFFSET = 1; // 1px above the input

    @Inject(method = "init", at = @At("TAIL"))
    private void multichat$init(CallbackInfo ci) {
        ChatScreen self = (ChatScreen) (Object) this;

        // Do NOT rebuild/clear chat on open.
        // Rebuild is only done when switching tabs, otherwise the chat appears "cleared" when opening.

        EditBox input = findChatInput(self);
        if (input == null) return;

        // IMPORTANT: prevent disconnects when the outgoing message is longer than the
        // vanilla protocol limit (256). We also auto-prefix messages for non-global
        // channels ("#l ", "#t ", ...), so reserve space for that.
        applyMaxChatLength(input);

        int x0 = input.getX();
        int y0 = input.getY() - TAB_H - TAB_Y_OFFSET;

        // ADMIN tab is permission-gated (LuckPerms node 'avilixchat.spy' on the server).
        boolean allowAdmin = ClientUiConfig.isAdminTabAllowed();
        if (!allowAdmin && ClientChatState.getCurrent() == ChatChannel.ADMIN) {
            ClientChatState.setCurrent(ChatChannel.GLOBAL);
        }

        ChatChannel[] channels = allowAdmin
                ? new ChatChannel[]{ChatChannel.GLOBAL, ChatChannel.LOCAL, ChatChannel.TRADE, ChatChannel.CLAN, ChatChannel.ADMIN}
                : new ChatChannel[]{ChatChannel.GLOBAL, ChatChannel.LOCAL, ChatChannel.TRADE, ChatChannel.CLAN};

        List<ChatTab> tabs = new ArrayList<>(channels.length);
        for (int i = 0; i < channels.length; i++) {
            int x = x0 + i * (TAB_W + TAB_GAP);
            tabs.add(new ChatTab(x, y0, TAB_W, TAB_H, channels[i]));
        }
        MULTICHAT_TABS.put(self, tabs);
        MENTION_STATE.put(self, new MentionSuggestState());

        // Keep focus in the input by default.
        input.setFocused(true);
        self.setFocused(input);

        // Show current channel indicator inside the input (doesn't affect typing).
        updateChannelHint(input);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void multichat$removed(CallbackInfo ci) {
        MULTICHAT_TABS.remove((ChatScreen) (Object) this);
        MENTION_STATE.remove((ChatScreen) (Object) this);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void multichat$render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ChatScreen self = (ChatScreen) (Object) this;
        List<ChatTab> tabs = MULTICHAT_TABS.get(self);
        if (tabs == null || tabs.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;

        ChatTab hoveredTab = null;

        for (ChatTab t : tabs) {
            boolean selected = (t.channel == ClientChatState.getCurrent());
            boolean hover = t.hit(mouseX, mouseY);

            if (hover) hoveredTab = t;

            // Channel-colored tabs.
            int rgb = ClientUiConfig.tabRgb(t.channel) & 0xFFFFFF;
            int dark = darken(rgb, 0.40f);

            int bg = ((selected ? 0xA8 : (hover ? 0x88 : 0x66)) << 24) | dark;
            int border = ((selected ? 0xFF : 0xB0) << 24) | rgb;

            gfx.fill(t.x, t.y, t.x + t.w, t.y + t.h, bg);
            // Simple 1px border
            gfx.fill(t.x, t.y, t.x + t.w, t.y + 1, border);
            gfx.fill(t.x, t.y + t.h - 1, t.x + t.w, t.y + t.h, border);
            gfx.fill(t.x, t.y, t.x + 1, t.y + t.h, border);
            gfx.fill(t.x + t.w - 1, t.y, t.x + t.w, t.y + t.h, border);

            // Merge-selection outline (SHIFT+LMB): draw an OUTER 1px white frame so it doesn't cover the label.
            if (MergedTabsState.contains(t.channel)) {
                int w = 0xFFFFFFFF;
                int x1 = t.x - 1;
                int y1 = t.y - 1;
                int x2 = t.x + t.w + 1;
                int y2 = t.y + t.h + 1;
                gfx.fill(x1, y1, x2, y1 + 1, w);
                gfx.fill(x1, y2 - 1, x2, y2, w);
                gfx.fill(x1, y1, x1 + 1, y2, w);
                gfx.fill(x2 - 1, y1, x2, y2, w);
            }

            TextColor labelColor = TextColor.fromRgb(selected ? 0xFFFFFF : rgb);
            Component label = Component.literal(ClientUiConfig.tabLabel(t.channel))
                    .withStyle(style -> style.withColor(labelColor));

            int lw = font.width(label);
            int lx = t.x + (t.w - lw) / 2;
            int ly = t.y + 1;
            gfx.drawString(font, label, lx, ly, 0xFFFFFF, false);

            // Unread marker: show a small counter badge.
            if (!selected) {
                int unread = ClientChatState.getUnread(t.channel);
                if (unread > 0) {
                    drawUnreadBadge(gfx, font, t, unread);
                }
            }
        }

        // @mention suggestions + lightweight command help.
        EditBox input = findChatInput(self);
        if (input != null) {
            renderMentionSuggestions(self, gfx, input, mouseX, mouseY);
            renderCommandHelp(gfx, input);
        }

        // Tooltip with the full chat name when hovering a tab.
        if (hoveredTab != null) {
            gfx.renderTooltip(font, hoveredTab.channel.tabTitle(), mouseX, mouseY);
        }

    }

    private static int darken(int rgb, float factor) {
        factor = Math.max(0.0f, Math.min(1.0f, factor));
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = (int) (r * factor);
        g = (int) (g * factor);
        b = (int) (b * factor);
        return (r << 16) | (g << 8) | b;
    }

    private static void drawUnreadBadge(GuiGraphics gfx, net.minecraft.client.gui.Font font, ChatTab t, int unread) {
        // Fits into compact 18x10 tabs: show 1 digit, otherwise 9+.
        String text = unread > 9 ? "9+" : Integer.toString(unread);

        // Small scale so the badge fits.
        float scale = 0.75f;
        int tw = font.width(text);
        int th = font.lineHeight;

        int padX = 2;
        int padY = 1;
        int bw = (int) Math.ceil((tw + padX * 2) * scale);
        int bh = (int) Math.ceil((th + padY * 2) * scale);

        int bx = t.x + t.w - bw - 1;
        int by = t.y + 1;

        int bg = 0xCCFF3B3B;      // red
        int border = 0xFFFFB3B3;  // light border

        gfx.fill(bx, by, bx + bw, by + bh, bg);
        // 1px border
        gfx.fill(bx, by, bx + bw, by + 1, border);
        gfx.fill(bx, by + bh - 1, bx + bw, by + bh, border);
        gfx.fill(bx, by, bx + 1, by + bh, border);
        gfx.fill(bx + bw - 1, by, bx + bw, by + bh, border);

        // Draw text with scaling.
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 200);
        gfx.pose().scale(scale, scale, 1.0f);

        int tx = (int) ((bx + (bw - (tw * scale)) / 2.0f) / scale);
        int ty = (int) ((by + (bh - (th * scale)) / 2.0f) / scale);
        gfx.drawString(font, text, tx, ty, 0xFFFFFF, false);
        gfx.pose().popPose();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void multichat$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) return;
        ChatScreen self = (ChatScreen) (Object) this;

        // If @suggestions are open, allow clicking them.
        if (handleMentionClick(self, mouseX, mouseY)) {
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        List<ChatTab> tabs = MULTICHAT_TABS.get(self);
        if (tabs == null || tabs.isEmpty()) return;

        for (ChatTab t : tabs) {
            if (!t.hit(mouseX, mouseY)) continue;

            // SHIFT + LMB toggles merge selection for this channel (without switching the active channel).
            boolean shift = false;
            try {
                long win = Minecraft.getInstance().getWindow().getWindow();
                shift = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
            } catch (Throwable ignored) {
            }

            if (shift) {
                MergedTabsState.toggle(t.channel);
                ClientChatState.rebuildChat();
            } else {
                // Switch channel and rebuild visible chat without closing the screen.
                ClientChatState.setCurrent(t.channel);
            }

            // IMPORTANT: keep typing focus in the chat input.
            EditBox input = findChatInput(self);
            if (input != null) {
                input.setFocused(true);
                self.setFocused(input);
                updateChannelHint(input);
            }

            cir.setReturnValue(true);
            cir.cancel();
            return;
        }
    }

    // NOTE: CLAN chat is routed on the server by executing /opm when the CLAN channel is selected.
    // The client must NOT rewrite /opm into normal chat text; players should still be able to use
    // /opm as a real command.

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void multichat$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        ChatScreen self = (ChatScreen) (Object) this;
        // @suggestions keyboard navigation.
        if (handleMentionKeys(self, keyCode)) {
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        // Ensure the cycle hotkey works even while the chat is open (vanilla captures keyboard input).
        if (ClientKeybinds.handleChatScreenKeyPress(keyCode, scanCode)) {
            EditBox input = findChatInput(self);
            if (input != null) updateChannelHint(input);
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    private static void renderCommandHelp(GuiGraphics gfx, EditBox input) {
        String v = input.getValue();
        if (v == null || v.isBlank()) return;
        if (!v.startsWith("/")) return;

        String lower = v.toLowerCase();
        String help = null;

        if (lower.startsWith("/avilixchat")) {
            help = "/avilixchat spy [on|off]  |  /avilixchat adminmirror [on|off]";
        } else if (lower.startsWith("/mute")) {
            help = "/mute <ник> [длительность] [причина]";
        } else if (lower.startsWith("/tempmute")) {
            help = "/tempmute <ник> <длительность> [причина]";
        } else if (lower.startsWith("/unmute")) {
            help = "/unmute <ник> [причина]";
        } else if (lower.startsWith("/muted")) {
            help = "/muted <ник>";
        } else if (lower.startsWith("/mutelist")) {
            help = "/mutelist [страница]";
        }

        if (help == null) return;

        Minecraft mc = Minecraft.getInstance();
        int x = input.getX();
        int y = input.getY() - 10;
        gfx.drawString(mc.font, Component.literal(help).withStyle(ChatFormatting.DARK_GRAY), x, y, 0xFFFFFF, false);
    }

    private static void renderMentionSuggestions(ChatScreen screen, GuiGraphics gfx, EditBox input, int mouseX, int mouseY) {
        MentionSuggestState st = MENTION_STATE.get(screen);
        if (st == null) return;

        // Only show for normal chat (not commands).
        String value = input.getValue();
        if (value == null) {
            st.close();
            return;
        }
        if (value.startsWith("/")) {
            st.close();
            return;
        }

        int cursor = getCursorPos(input);
        st.cursor = cursor;

        int at = findActiveAt(value, cursor);
        if (at < 0) {
            st.close();
            return;
        }

        String prefix = value.substring(at + 1, Math.min(cursor, value.length()));
        if (prefix.contains(" ")) {
            st.close();
            return;
        }

        st.atIndex = at;
        rebuildCandidates(st, prefix);
        if (!st.isOpen()) {
            st.close();
            return;
        }

        // Clamp selection.
        if (st.selected < 0) st.selected = 0;
        if (st.selected >= st.candidates.size()) st.selected = st.candidates.size() - 1;

        // Render popup (vanilla-like simple list).
        // Important: make sure the popup is NOT clipped by leftover scissors from widgets,
        // and render it above the chat (higher Z) so it always stays "in front".
        Minecraft mc = Minecraft.getInstance();
        int rowH = 10;
        int w = Math.max(90, input.getWidth() / 2);
        int h = st.candidates.size() * rowH + 2;
        int x = input.getX();
        int y = input.getY() - h - 2;

        // Ensure no scissor from previous widgets clips the popup.
        try {
            GuiGraphics.class.getMethod("disableScissor").invoke(gfx);
        } catch (Throwable ignored) {
        }

        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 500);

        gfx.fill(x, y, x + w, y + h, 0xCC000000);
        gfx.fill(x, y, x + w, y + 1, 0x55FFFFFF);
        gfx.fill(x, y + h - 1, x + w, y + h, 0x55FFFFFF);

        for (int i = 0; i < st.candidates.size(); i++) {
            int ry = y + 1 + i * rowH;
            boolean sel = (i == st.selected);
            if (sel) {
                gfx.fill(x + 1, ry, x + w - 1, ry + rowH, 0x553B82F6);
            }
            String name = st.candidates.get(i);
            gfx.drawString(mc.font, Component.literal("@" + name).withStyle(sel ? ChatFormatting.WHITE : ChatFormatting.GRAY), x + 4, ry + 1, 0xFFFFFF, false);
        }

        gfx.pose().popPose();
    }

    private static boolean handleMentionKeys(ChatScreen screen, int keyCode) {
        MentionSuggestState st = MENTION_STATE.get(screen);
        if (st == null || !st.isOpen()) return false;

        if (keyCode == GLFW.GLFW_KEY_UP) {
            st.selected = Math.max(0, st.selected - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            st.selected = Math.min(st.candidates.size() - 1, st.selected + 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            return acceptMention(screen);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            st.close();
            return false;
        }
        return false;
    }

    private static boolean handleMentionClick(ChatScreen screen, double mouseX, double mouseY) {
        MentionSuggestState st = MENTION_STATE.get(screen);
        if (st == null || !st.isOpen()) return false;

        EditBox input = findChatInput(screen);
        if (input == null) return false;

        int rowH = 10;
        int w = Math.max(90, input.getWidth() / 2);
        int h = st.candidates.size() * rowH + 2;
        int x = input.getX();
        int y = input.getY() - h - 2;

        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return false;

        int idx = (int) ((mouseY - (y + 1)) / rowH);
        if (idx < 0 || idx >= st.candidates.size()) return true;
        st.selected = idx;
        return acceptMention(screen);
    }

    private static boolean acceptMention(ChatScreen screen) {
        MentionSuggestState st = MENTION_STATE.get(screen);
        if (st == null || !st.isOpen()) return false;

        EditBox input = findChatInput(screen);
        if (input == null) return false;

        String value = input.getValue();
        if (value == null) return false;
        String name = st.candidates.get(Math.max(0, Math.min(st.selected, st.candidates.size() - 1)));

        int at = st.atIndex;
        int cursor = st.cursor;
        if (at < 0 || at >= value.length()) return false;
        int end = Math.min(cursor, value.length());

        String before = value.substring(0, at);
        String after = value.substring(end);
        String inserted = "@" + name + " ";
        String out = before + inserted + after;

        input.setValue(out);
        // Put cursor right after inserted mention.
        setCursorPos(input, (before + inserted).length());
        st.close();
        return true;
    }

    private static int findActiveAt(String text, int cursor) {
        if (text == null) return -1;
        int c = Math.min(cursor, text.length());
        for (int i = c - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == '@') {
                // Must be at start or preceded by whitespace.
                if (i == 0 || Character.isWhitespace(text.charAt(i - 1))) {
                    return i;
                }
            }
            if (Character.isWhitespace(ch)) break;
        }
        return -1;
    }

    private static void rebuildCandidates(MentionSuggestState st, String prefix) {
        st.candidates.clear();
        String p = prefix == null ? "" : prefix.toLowerCase();

        Minecraft mc = Minecraft.getInstance();
        var conn = mc.getConnection();
        if (conn == null) return;

        try {
            var online = conn.getOnlinePlayers();
            for (var info : online) {
                try {
                    String n = info.getProfile().getName();
                    if (n == null) continue;
                    if (p.isEmpty() || n.toLowerCase().startsWith(p)) {
                        st.candidates.add(n);
                        if (st.candidates.size() >= 8) break;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // If mappings change, simply disable suggestions.
        }
    }

    private static int getCursorPos(EditBox input) {
        try {
            return (int) input.getClass().getMethod("getCursorPosition").invoke(input);
        } catch (Throwable ignored) {
        }
        // Fallback: end of text.
        try {
            String v = input.getValue();
            return v == null ? 0 : v.length();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void setCursorPos(EditBox input, int pos) {
        try {
            input.getClass().getMethod("setCursorPosition", int.class).invoke(input, pos);
        } catch (Throwable ignored) {
        }
    }

    // No keyReleased handling needed: we intentionally allow OS key-repeat to cycle.

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

    private static void updateChannelHint(EditBox input) {
        try {
            ChatChannel ch = ClientChatState.getCurrent();
            String label = ClientUiConfig.tabLabel(ch);
            input.setHint(Component.literal("[" + label + "] ").withStyle(ch.color));
            applyMaxChatLength(input);
        } catch (Throwable ignored) {
        }
    }

    private static void applyMaxChatLength(EditBox input) {
        try {
            // Our client-side sender may add a 3-char prefix for non-global channels: "#x ".
            int extra = (ClientChatState.getSendChannel() == ChatChannel.GLOBAL) ? 0 : 3;
            int max = Math.max(1, VANILLA_CHAT_MAX - extra);
            input.setMaxLength(max);
        } catch (Throwable ignored) {
        }
    }

    private static void addToRecentChat(String raw) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui == null) return;
            Object chat = mc.gui.getChat();
            // Common name in 1.20+.
            chat.getClass().getMethod("addRecentChat", String.class).invoke(chat, raw);
        } catch (Throwable ignored) {
        }
    }

    // (command-sending helpers removed)
}
