package com.roften.multichat.spy;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.roften.multichat.moderation.Perms;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /avilixchat spy [on|off]
 */
public final class SpyCommands {
    private SpyCommands() {}

    public static final String NODE_SPY = "avilixchat.spy";

    /**
     * NOTE: Command registration moved to {@code com.roften.multichat.commands.AvilixChatCommands}
     * and is attached explicitly from {@code MultiChatMod}.
     */
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("avilixchat");

        root.then(Commands.literal("spy")
                .requires(src -> Perms.has(src, NODE_SPY))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    boolean on = SpyState.toggle(player);
                    ctx.getSource().sendSuccess(() -> Component.literal("Chat spy: " + (on ? "ON" : "OFF"))
                            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    SpyState.set(player, true);
                    ctx.getSource().sendSuccess(() -> Component.literal("Chat spy: ON").withStyle(ChatFormatting.GREEN), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    SpyState.set(player, false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Chat spy: OFF").withStyle(ChatFormatting.RED), false);
                    return 1;
                }))
        );

        event.getDispatcher().register(root);
    }
}
