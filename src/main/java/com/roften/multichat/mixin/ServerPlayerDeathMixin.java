package com.roften.multichat.mixin;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.admin.AdminChatState;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.server.DeathMessageDeduper;
import com.roften.multichat.db.ChatLogDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Routes player death messages into LOCAL chat only (within a configurable radius)
 * and writes them to a dedicated DB table.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathMixin {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Redirect(
            method = "die",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
            require = 0
    )
    private void multichat$redirectDeathBroadcast(PlayerList list, Component message, boolean overlay) {
        // If the redirect fails to apply (method signature changes), vanilla broadcast will be used.
        if (message == null) return;

        ServerPlayer self = (ServerPlayer) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) {
            list.broadcastSystemMessage(message, overlay);
            return;
        }

        if (!MultiChatConfig.DEATH_MESSAGES_LOCAL_ONLY.getAsBoolean()) {
            list.broadcastSystemMessage(message, overlay);
            return;
        }

        // Mark handled so our LivingDeathEvent fallback does not duplicate it.
        DeathMessageDeduper.markHandled(self, server.getTickCount());

        int radius = MultiChatConfig.DEATH_RADIUS_BLOCKS.getAsInt();
        double maxDistSqr = (double) radius * (double) radius;
        ServerLevel level = self.serverLevel();

        List<ServerPlayer> targets = level.players().stream()
                .filter(p -> p.distanceToSqr(self) <= maxDistSqr)
                .collect(Collectors.toList());

        // Keep the vanilla death component formatting (player names, team colors, hover/click, etc.).
        // If we apply a flat GRAY style here, it will override inner styles and the report will look "flat".
        String ts = LocalTime.now().format(TIME_FMT);
        MutableComponent out = Component.literal("[" + ts + "]").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(" "))
                .append(ChatChannel.LOCAL.channelBadge())
                .append(Component.literal(" "))
                .append(message.copy());

        // Send without mixin-based system logging duplication.
        ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
            for (ServerPlayer p : targets) {
                p.sendSystemMessage(out);
            }

            // Always deliver a copy into ADMIN tab (even if /spy is disabled).
            MutableComponent adminOut = Component.literal("[" + ts + "]").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(" "))
                    .append(ChatChannel.ADMIN.channelBadge())
                    .append(Component.literal(" "))
                    .append(message.copy());

            Component marked = AdminChatState.markAdminMirror(adminOut);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!AdminChatState.hasAdminChatPermission(p)) continue;
                p.sendSystemMessage(marked);
            }
        });

        // Vanilla logs death messages to console; we keep that behavior even when routing locally.
        MultiChatMod.LOGGER.info(message.getString());

        // Write into a dedicated DB table.
        ChatLogDatabase.logDeath(server, self, message.getString());
    }
}
