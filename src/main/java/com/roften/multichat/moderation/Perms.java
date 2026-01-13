package com.roften.multichat.moderation;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.compat.LuckPermsCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Permission helper: prefers LuckPerms nodes when available, falls back to vanilla permission levels.
 */
public final class Perms {
    private Perms() {}

    public static boolean has(CommandSourceStack source, String node) {
        // console / command blocks etc
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return source.hasPermission(MultiChatConfig.MUTE_REQUIRED_PERMISSION_LEVEL.getAsInt());
        }

        Boolean lp = LuckPermsCompat.hasPermission(player, node);
        if (lp != null) {
            return lp;
        }
        return source.hasPermission(MultiChatConfig.MUTE_REQUIRED_PERMISSION_LEVEL.getAsInt());
    }
}
