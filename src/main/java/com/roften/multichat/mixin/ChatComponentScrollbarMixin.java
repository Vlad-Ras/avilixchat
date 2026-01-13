package com.roften.multichat.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-only: draws a simple scrollbar for the chat history.
 *
 * Vanilla chat is scrollable with mouse wheel/PageUp/PageDown, but it has no visible scrollbar.
 * This mixin renders a thin scrollbar when the chat screen is open.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentScrollbarMixin {

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void avilixchat$renderScrollbar(GuiGraphics gfx, int tickCount, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        // Only when chat is open/focused.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (!(mc.screen instanceof ChatScreen)) return;

        ChatComponent self = (ChatComponent) (Object) this;

        int width = invokeInt(self, "getWidth", "getChatWidth");
        int height = invokeInt(self, "getHeight", "getChatHeight");
        if (width <= 0 || height <= 0) return;

        int linesPerPage = invokeInt(self, "getLinesPerPage", "getLineCount");
        if (linesPerPage <= 0) return;

        int scrollPos = readIntField(self,
                "chatScrollbarPos", "scrollPos", "scrollPosition", "scrollOffset");

        int totalLines = findTotalLineCount(self);
        if (totalLines <= linesPerPage) return; // no need

        int maxScroll = Math.max(1, totalLines - linesPerPage);
        if (scrollPos < 0) scrollPos = 0;
        if (scrollPos > maxScroll) scrollPos = maxScroll;

        int screenH = mc.getWindow().getGuiScaledHeight();
        int xLeft = 2;
        int yBottom = screenH - 40;
        int yTop = yBottom - height;

        // Track (slightly inset so it doesn't touch text background edge).
        int trackX0 = xLeft + width - 2;
        int trackX1 = xLeft + width;
        int trackY0 = yTop + 2;
        int trackY1 = yBottom - 2;
        int trackH = Math.max(1, trackY1 - trackY0);

        // Thumb size/pos.
        int thumbH = Math.max(8, (int) Math.floor((trackH * (linesPerPage / (double) totalLines))));
        int thumbRange = Math.max(1, trackH - thumbH);
        int thumbY0 = trackY0 + (int) Math.round((scrollPos / (double) maxScroll) * thumbRange);
        int thumbY1 = thumbY0 + thumbH;

        // Make sure it's always visible above chat text/background.
        try {
            GuiGraphics.class.getMethod("disableScissor").invoke(gfx);
        } catch (Throwable ignored) {
        }
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 450);

        // Track
        gfx.fill(trackX0, trackY0, trackX1, trackY1, 0x66000000);
        // Thumb (white-ish)
        gfx.fill(trackX0, thumbY0, trackX1, thumbY1, 0xCCFFFFFF);

        gfx.pose().popPose();
    }

    private static int invokeInt(Object target, String... names) {
        if (target == null) return 0;
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                Object r = m.invoke(target);
                if (r instanceof Integer i) return i;
                if (r instanceof Number num) return num.intValue();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static int readIntField(Object target, String... fieldNames) {
        if (target == null) return 0;
        for (String n : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof Integer i) return i;
                if (v instanceof Number num) return num.intValue();
            } catch (Throwable ignored) {
            }
        }
        // Fallback: find the first int field that looks like scroll.
        try {
            for (Field f : target.getClass().getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                String ln = f.getName().toLowerCase();
                if (!ln.contains("scroll")) continue;
                f.setAccessible(true);
                return f.getInt(target);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
        * Best-effort total line count.
        * We prefer the wrapped/trimmed line list; if it is not found, we pick the largest List field.
        */
    private static int findTotalLineCount(ChatComponent chat) {
        if (chat == null) return 0;

        // Try common MojMaps field names first.
        Integer n = readListSize(chat,
                "trimmedMessages", "trimmedChatMessages", "chatLines", "lines", "displayedMessages");
        if (n != null) return n;

        // Fallback: choose the largest List field.
        try {
            int best = 0;
            for (Field f : chat.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(chat);
                if (v instanceof List<?> list) {
                    best = Math.max(best, list.size());
                }
            }
            return best;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Integer readListSize(Object target, String... fieldNames) {
        for (String n : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof List<?> list) return list.size();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
