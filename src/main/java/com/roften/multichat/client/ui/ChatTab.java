package com.roften.multichat.client.ui;

import com.roften.multichat.chat.ChatChannel;

/**
 * Small immutable data holder for chat tab geometry.
 *
 * IMPORTANT: This class MUST NOT live in a mixin package.
 * Mixin forbids directly loading non-mixin helper classes from a mixin-owned package.
 */
public final class ChatTab {
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final ChatChannel channel;

    public ChatTab(int x, int y, int w, int h, ChatChannel channel) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.channel = channel;
    }

    public boolean hit(double mx, double my) {
        return mx >= x && mx < (x + w) && my >= y && my < (y + h);
    }
}
