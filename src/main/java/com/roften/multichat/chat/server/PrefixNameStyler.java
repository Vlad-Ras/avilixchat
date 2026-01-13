package com.roften.multichat.chat.server;

import com.roften.multichat.compat.LuckPermsCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

/**
 * Applies player name color from LuckPerms meta (NOT from the prefix).
 *
 * <p>Nick gradients are intentionally not supported: we apply a single solid color.</p>
 */
public final class PrefixNameStyler {
    private PrefixNameStyler() {}

    public static Component styleName(ServerPlayer player) {
        final String name = player.getGameProfile().getName();
        Integer rgb = LuckPermsCompat.getNameColorRgb(player);
        if (rgb == null) {
            return Component.literal(name);
        }

        return Component.literal(name).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    // Gradients intentionally not supported (solid color only).
}
