package com.roften.multichat.mixin;

import com.mojang.brigadier.ParseResults;
import com.roften.multichat.compat.XaeroWaypointCompat;
import com.roften.multichat.spy.SpyState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures executed commands for chat spy.
 *
 * <p>IMPORTANT (MC 1.21.1 / NeoForge 21.1.x):
 * {@code Commands#performCommand} uses {@code (ParseResults<CommandSourceStack>, String) -> void}.
 * We inject using the explicit JVM descriptor to avoid matching the wrong overload.
 */
@Mixin(net.minecraft.commands.Commands.class)
public abstract class CommandsMixin {

    @Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V", at = @At("HEAD"))
    private void avilixchat$spyCommand(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        if (SpyState.isSendingSpy()) return;

        CommandSourceStack source = parse.getContext().getSource();
        MinecraftServer server = source.getServer();
        if (server == null) return;

        // Don't spam spies with the toggle command itself.
        String raw = command == null ? "" : command.trim();

        // Xaero waypoint share: remember the executor's currently opened chat tab for a short
        // time window, so we can route Xaero's subsequent SYSTEM broadcast into that tab.
        // (Some Xaero builds don't include the sharer name in the broadcast line.)
        try {
            ServerPlayer exec0 = source.getPlayer();
            if (exec0 != null && looksLikeXaeroShareCommand(raw)) {
                XaeroWaypointCompat.noteCandidate(exec0);
            }
        } catch (Throwable ignored) {
        }
        if (raw.startsWith("avilixchat spy") || raw.startsWith("/avilixchat spy")) {
            return;
        }

        ServerPlayer exec = source.getPlayer();
        // Spy copies must be monochrome: everything gray, only "SPY" stays red.
        Component who = exec != null
                ? Component.literal(exec.getGameProfile().getName()).withStyle(ChatFormatting.GRAY)
                : Component.literal(source.getTextName()).withStyle(ChatFormatting.GRAY);

        // Keep it short: no "Command:" prefix.
        Component msg = SpyState.spyPrefix()
                .append(who)
                .append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/" + raw).withStyle(ChatFormatting.GRAY));

        // Smart filtering is inside SpyState: it will avoid duplicates when the spy is also a recipient.
        // Additionally, we pass executor to avoid echoing back to the executor.
        SpyState.sendToSpies(server, exec, msg);
    }

    private static boolean looksLikeXaeroShareCommand(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String s = raw.startsWith("/") ? raw.substring(1) : raw;
        String lower = s.toLowerCase(Locale.ROOT);
        // Be intentionally broad: the pending marker expires very quickly.
        if (!lower.contains("xaero")) return false;
        // Common tokens across Xaero minimap/worldmap versions.
        return lower.contains("waypoint") || lower.contains("wp") || lower.contains("share") || lower.contains("minimap") || lower.contains("worldmap");
    }
}
