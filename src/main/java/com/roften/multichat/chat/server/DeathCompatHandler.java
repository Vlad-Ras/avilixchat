package com.roften.multichat.chat.server;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.admin.AdminChatState;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.db.ChatLogDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Fallback death routing if the mixin redirect does not apply due to a signature change.
 */
@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class DeathCompatHandler {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private DeathCompatHandler() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (!MultiChatConfig.DEATH_MESSAGES_LOCAL_ONLY.getAsBoolean()) return;

        int tick = server.getTickCount();
        if (DeathMessageDeduper.wasHandledRecently(player, tick)) return;

        Component deathMsg;
        try {
            deathMsg = player.getCombatTracker().getDeathMessage();
        } catch (Throwable t) {
            // As a last resort, do nothing and let vanilla broadcast.
            return;
        }
        if (deathMsg == null) return;

        DeathMessageDeduper.markHandled(player, tick);

        int radius = MultiChatConfig.DEATH_RADIUS_BLOCKS.getAsInt();
        double maxDistSqr = (double) radius * (double) radius;
        ServerLevel level = player.serverLevel();

        // Keep vanilla formatting: do NOT force a flat color, otherwise inner styles are lost.
        String ts = LocalTime.now().format(TIME_FMT);
        MutableComponent out = Component.literal("[" + ts + "]").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(" "))
                .append(ChatChannel.LOCAL.channelBadge())
                .append(Component.literal(" "))
                .append(deathMsg.copy());

        ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(player) <= maxDistSqr) {
                    p.sendSystemMessage(out);
                }
            }

            // Also send a copy into ADMIN tab for admins (independent of /spy toggle).
            MutableComponent adminOut = Component.literal("[" + ts + "]").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(" "))
                    .append(ChatChannel.ADMIN.channelBadge())
                    .append(Component.literal(" "))
                    .append(deathMsg.copy());
            Component marked = AdminChatState.markAdminMirror(adminOut);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!AdminChatState.hasAdminChatPermission(p)) continue;
                p.sendSystemMessage(marked);
            }
        });

        // Vanilla logs death messages to console; keep that behavior.
        MultiChatMod.LOGGER.info(deathMsg.getString());
        ChatLogDatabase.logDeath(server, player, deathMsg.getString());
    }
}
