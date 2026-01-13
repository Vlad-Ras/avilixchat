package com.roften.multichat.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.admin.AdminChatState;
import com.roften.multichat.moderation.Perms;
import com.roften.multichat.spy.AreaSpyState;
import com.roften.multichat.spy.SpyState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers the root mod command: /avilixchat ...
 *
 * <p>We register via {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS} from {@link com.roften.multichat.MultiChatMod}
 * to avoid annotation/bus differences across NeoForge versions.</p>
 */
public final class AvilixChatCommands {
    private AvilixChatCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("avilixchat");

        // /avilixchat spy [on|off]
        root.then(Commands.literal("spy")
                .requires(src -> Perms.has(src, SpyCommandsNodes.NODE_SPY))
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

        // /avilixchat adminmirror [on|off]
        root.then(Commands.literal("adminmirror")
                .requires(src -> Perms.has(src, AdminChatState.NODE_ADMIN_CHAT))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    boolean on = AdminChatState.toggleMirror(player);
                    ctx.getSource().sendSuccess(() -> Component.literal("Admin mirror: " + (on ? "ON" : "OFF"))
                            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    AdminChatState.setMirror(player, true);
                    ctx.getSource().sendSuccess(() -> Component.literal("Admin mirror: ON").withStyle(ChatFormatting.GREEN), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    AdminChatState.setMirror(player, false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Admin mirror: OFF").withStyle(ChatFormatting.RED), false);
                    return 1;
                }))
        );

        event.getDispatcher().register(root);

        // /spy area <radius> [minutes]
        LiteralArgumentBuilder<CommandSourceStack> spy = Commands.literal("spy")
                .requires(src -> Perms.has(src, SpyCommandsNodes.NODE_SPY));

        spy.then(Commands.literal("area")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (AreaSpyState.isEnabled(player)) {
                        AreaSpyState.disable(player);
                        ctx.getSource().sendSuccess(() -> Component.literal("Area spy: OFF")
                                .withStyle(ChatFormatting.RED), false);
                    } else {
                        int r = Math.max(1, MultiChatConfig.LOCAL_RADIUS_BLOCKS.getAsInt());
                        AreaSpyState.enable(player, r, 0);
                        ctx.getSource().sendSuccess(() -> Component.literal("Area spy: ON (" + r + "b)")
                                .withStyle(ChatFormatting.GREEN), false);
                    }
                    return 1;
                })
                .then(Commands.literal("off").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    AreaSpyState.disable(player);
                    ctx.getSource().sendSuccess(() -> Component.literal("Area spy: OFF")
                            .withStyle(ChatFormatting.RED), false);
                    return 1;
                }))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            int radius = IntegerArgumentType.getInteger(ctx, "radius");
                            AreaSpyState.enable(player, radius, 0);
                            ctx.getSource().sendSuccess(() -> Component.literal("Area spy: ON (" + radius + "b)")
                                    .withStyle(ChatFormatting.GREEN), false);
                            return 1;
                        })
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 24 * 60))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    int radius = IntegerArgumentType.getInteger(ctx, "radius");
                                    int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                    AreaSpyState.enable(player, radius, minutes);
                                    ctx.getSource().sendSuccess(() -> Component.literal("Area spy: ON (" + radius + "b, " + minutes + "m)")
                                            .withStyle(ChatFormatting.GREEN), false);
                                    return 1;
                                })))
        );

        event.getDispatcher().register(spy);
        MultiChatMod.LOGGER.info("Registered /avilixchat subcommands: spy, adminmirror");
    }

    /**
     * Kept as a tiny indirection so we don't duplicate string literals across classes.
     */
    private static final class SpyCommandsNodes {
        private static final String NODE_SPY = "avilixchat.spy";
    }
}
