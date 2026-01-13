package com.roften.multichat.moderation;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.compat.LuckPermsCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class ModerationCommands {
    private ModerationCommands() {}

    private static final String NODE_MUTE = "avilixchat.mute";
    private static final String NODE_UNMUTE = "avilixchat.unmute";
    private static final String NODE_MUTED = "avilixchat.muted";
    private static final String NODE_MUTELIST = "avilixchat.mutelist";
    private static final SimpleCommandExceptionType ERR_BAD_TARGET =
            new SimpleCommandExceptionType(Component.literal("Не удалось разобрать аргумент 'target'."));


    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var disp = event.getDispatcher();

        disp.register(Commands.literal("mute")
                .requires(src -> LuckPermsCompat.hasPermission(src, NODE_MUTE, MultiChatConfig.MUTE_REQUIRED_PERMISSION_LEVEL.get()))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .suggests(ONLY_PLAYER_NAMES)
                        .executes(ctx -> mute(ctx, -1L))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(ctx -> mute(ctx, parseDuration(ctx)))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> mute(ctx, parseDuration(ctx)))
                                )
                        )
                )
        );

        disp.register(Commands.literal("unmute")
                .requires(src -> LuckPermsCompat.hasPermission(src, NODE_UNMUTE, MultiChatConfig.MUTE_REQUIRED_PERMISSION_LEVEL.get()))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .suggests(ONLY_PLAYER_NAMES)
                        .executes(ModerationCommands::unmute)
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ModerationCommands::unmute)
                        )
                )
        );

        disp.register(Commands.literal("muted")
                .requires(src -> LuckPermsCompat.hasPermission(src, NODE_MUTED, MultiChatConfig.MUTE_REQUIRED_PERMISSION_LEVEL.get()))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .suggests(ONLY_PLAYER_NAMES)
                        .executes(ModerationCommands::muted)
                )
        );

        disp.register(Commands.literal("mutelist")
                .requires(src -> LuckPermsCompat.hasPermission(src, NODE_MUTELIST, MultiChatConfig.MUTE_REQUIRED_PERMISSION_LEVEL.get()))
                .executes(ctx -> mutelist(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> mutelist(ctx, IntegerArgumentType.getInteger(ctx, "page")))
                )
        );
    }
    private static String optionalString(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }



    private static long parseDuration(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String s = StringArgumentType.getString(ctx, "duration");
        try {
            return DurationParser.parseToMillis(s);
        } catch (IllegalArgumentException e) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().create("Неверная длительность: " + s);
        }
    }

    private static int mute(CommandContext<CommandSourceStack> ctx, long durationMs) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        String reason = optionalString(ctx, "reason");

        Target resolved = resolveTargetFromCtx(ctx);

        UUID actorUuid = null;
        String actorName = "CONSOLE";
        String dim = null;
        net.minecraft.core.BlockPos pos = null;

        if (src.getEntity() instanceof ServerPlayer sp) {
            actorUuid = sp.getUUID();
            actorName = sp.getGameProfile().getName();
            dim = sp.level().dimension().location().toString();
            pos = sp.blockPosition();
        }

        MuteManager.setMute(src.getServer(), resolved.uuid, resolved.name, actorUuid, actorName, durationMs, reason, dim, pos);

        final String durText = (durationMs < 0) ? "навсегда" : DurationParser.formatRemaining(durationMs);
        src.sendSuccess(() -> Component.literal("Замучен: " + resolved.name + " на " + durText + ".").withStyle(ChatFormatting.YELLOW), true);

        if (resolved.player != null) {
            MutableComponent note = Component.literal("Вы были замучены").withStyle(ChatFormatting.RED)
                    .append(Component.literal(" на " + durText).withStyle(ChatFormatting.YELLOW));
            if (reason != null && !reason.isBlank()) {
                note = note.append(Component.literal(" причина: " + reason).withStyle(ChatFormatting.GRAY));
            }
            resolved.player.sendSystemMessage(note);
        }
        return 1;
    }

    private static int unmute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        String reason = optionalString(ctx, "reason");

        Target resolved = resolveTargetFromCtx(ctx);

        MuteEntry existing = MuteManager.getMute(resolved.uuid);
        if (existing == null) {
            src.sendSuccess(() -> Component.literal("Игрок не в муте: " + resolved.name).withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        UUID actorUuid = null;
        String actorName = "CONSOLE";
        if (src.getEntity() instanceof ServerPlayer sp) {
            actorUuid = sp.getUUID();
            actorName = sp.getGameProfile().getName();
        }

        MuteManager.removeMute(src.getServer(), resolved.uuid, "UNMUTE", actorUuid, actorName);

        src.sendSuccess(() -> Component.literal("Размучен: " + resolved.name + (reason != null && !reason.isBlank() ? " (" + reason + ")" : ""))
                .withStyle(ChatFormatting.GREEN), true);

        if (resolved.player != null) {
            MutableComponent note = Component.literal("Ваш мут снят.").withStyle(ChatFormatting.GREEN);
            if (reason != null && !reason.isBlank()) {
                note = note.append(Component.literal(" причина: " + reason).withStyle(ChatFormatting.GRAY));
            }
            resolved.player.sendSystemMessage(note);
        }
        return 1;
    }

    private static int muted(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Target resolved = resolveTargetFromCtx(ctx);

        MuteEntry e = MuteManager.getMute(resolved.uuid);
        if (e == null) {
            src.sendSuccess(() -> Component.literal("Не в муте: " + resolved.name).withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        MutableComponent line = Component.literal("Замучен: " + (e.targetName() == null ? resolved.name : e.targetName())).withStyle(ChatFormatting.YELLOW);
        if (e.isPermanent()) {
            line = line.append(Component.literal(" (навсегда)").withStyle(ChatFormatting.RED));
        } else {
            long remaining = Math.max(0, e.expiresAtEpochMs() - System.currentTimeMillis());
            line = line.append(Component.literal(" (осталось: " + DurationParser.formatRemaining(remaining) + ")").withStyle(ChatFormatting.YELLOW));
        }
        if (e.reason() != null && !e.reason().isBlank()) {
            line = line.append(Component.literal(" причина: " + e.reason()).withStyle(ChatFormatting.GRAY));
        }

        final MutableComponent lineFinal = line;

        src.sendSuccess(() -> lineFinal, false);
        return 1;
    }

    /** Преобразуем GameProfile в нашу модель Target с поиском онлайна и фоллбеком на offline-UUID. */
    private static Target resolveTarget(CommandSourceStack src, GameProfile gp) {
        Objects.requireNonNull(gp, "gameProfile");
        MinecraftServer server = src.getServer();

        String name = gp.getName();
        ServerPlayer online = null;
        UUID uuid = gp.getId();

        // Попробуем найти онлайнового по UUID, если есть
        if (uuid != null) {
            online = server.getPlayerList().getPlayer(uuid);
            if (online != null && (name == null || name.isBlank())) {
                name = online.getGameProfile().getName();
            }
        }

        // Если не нашли по UUID — попробуем по имени
        if (online == null && name != null) {
            online = server.getPlayerList().getPlayerByName(name);
            if (online != null && uuid == null) {
                uuid = online.getUUID();
            }
        }

        // Фоллбек: offline-UUID по имени
        if (uuid == null) {
            uuid = offlineUuid(name != null ? name : "unknown");
        }
        if (name == null || name.isBlank()) {
            name = online != null ? online.getGameProfile().getName() : "(unknown)";
        }
        return new Target(uuid, name, online);
    }

    /** Удобная модель результата резолва. */
    private record Target(UUID uuid, String name, ServerPlayer player) {}

    private static int mutelist(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack src = ctx.getSource();
        List<MuteEntry> list = new ArrayList<>(MuteManager.snapshot().values());
        list.sort(Comparator.comparingLong(MuteEntry::createdAtEpochMs).reversed());

        int perPage = 10;
        int pages = Math.max(1, (int)Math.ceil(list.size() / (double)perPage));
        int p = Math.min(Math.max(1, page), pages);

        int from = (p - 1) * perPage;
        int to = Math.min(list.size(), from + perPage);
        final Component header = Component.literal("Муты (" + p + "/" + pages + "): " + list.size()).withStyle(ChatFormatting.AQUA);
        src.sendSuccess(() -> header, false);

        for (int i = from; i < to; i++) {
            MuteEntry e = list.get(i);
            String name = e.targetName() != null ? e.targetName() : e.targetUuid().toString();
            MutableComponent line = Component.literal("- " + name).withStyle(ChatFormatting.YELLOW);
            if (e.isPermanent()) {
                line = line.append(Component.literal(" (навсегда)").withStyle(ChatFormatting.RED));
            } else {
                long remaining = Math.max(0, e.expiresAtEpochMs() - System.currentTimeMillis());
                line = line.append(Component.literal(" (" + DurationParser.formatRemaining(remaining) + ")").withStyle(ChatFormatting.YELLOW));
            }
            if (e.reason() != null && !e.reason().isBlank()) {
                line = line.append(Component.literal(" причина: " + e.reason()).withStyle(ChatFormatting.GRAY));
            }
            final Component out = line;
            src.sendSuccess(() -> out, false);
        }
        return 1;
    }

    private static Target resolveTargetFromCtx(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();

        // 1) target как онлайн-игрок (EntityArgument.player())
        try {
            ServerPlayer sp = EntityArgument.getPlayer(ctx, "target");
            return new Target(sp.getUUID(), sp.getGameProfile().getName(), sp);
        } catch (IllegalArgumentException ignored) {}

        // 2) target как GameProfile (любой: селектор, кэш, оффлайн-профиль)
        try {
            Collection<GameProfile> set = GameProfileArgument.getGameProfiles(ctx, "target");
            GameProfile gp = requireSingle(set);
            return resolveTarget(server, gp);
        } catch (IllegalArgumentException ignored) {}

        // 3) target как простая строка
        try {
            String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "target");
            return resolveTarget(server, name);
        } catch (IllegalArgumentException ignored) {}

        throw ERR_BAD_TARGET.create();
    }

    private static GameProfile requireSingle(Collection<GameProfile> profiles) throws CommandSyntaxException {
        if (profiles == null || profiles.isEmpty())
            throw new SimpleCommandExceptionType(Component.literal("Игрок не найден.")).create();
        if (profiles.size() > 1)
            throw new SimpleCommandExceptionType(Component.literal("Укажите одного игрока.")).create();
        return profiles.iterator().next();
    }

    private static Target resolveTarget(MinecraftServer server, GameProfile gp) {
        UUID uuid = gp.getId();
        String name = gp.getName();
        ServerPlayer online = null;

        if (uuid != null) online = server.getPlayerList().getPlayer(uuid);
        if (online == null && name != null) online = server.getPlayerList().getPlayerByName(name);

        if (uuid == null) uuid = offlineUuid(name != null ? name : "unknown");
        if (name == null || name.isBlank()) name = online != null ? online.getGameProfile().getName() : "(unknown)";
        return new Target(uuid, name, online);
    }

    private static Target resolveTarget(MinecraftServer server, String name) {
        ServerPlayer online = (name == null) ? null : server.getPlayerList().getPlayerByName(name);
        UUID uuid = (online != null) ? online.getUUID() : offlineUuid(name != null ? name : "unknown");
        String fixedName = (online != null) ? online.getGameProfile().getName() : (name != null ? name : "(unknown)");
        return new Target(uuid, fixedName, online);
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static final SuggestionProvider<CommandSourceStack> ONLY_PLAYER_NAMES = (ctx, b) -> {
        var names = ctx.getSource().getServer().getPlayerList().getPlayers()
                .stream()
                .map(p -> p.getGameProfile().getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return SharedSuggestionProvider.suggest(names, b); // без @a, @e и т.п.
    };
}
