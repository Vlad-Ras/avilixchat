package com.roften.multichat.chat.server;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.admin.AdminChatState;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.compat.LegacyComponentParser;
import com.roften.multichat.compat.LuckPermsCompat;
import com.roften.multichat.compat.MiniMessageComponentParser;
import com.roften.multichat.compat.OpenPacCompat;
import com.roften.multichat.db.ChatLogDatabase;
import com.roften.multichat.moderation.MuteEntry;
import com.roften.multichat.moderation.MuteManager;
import com.roften.multichat.moderation.Perms;
import com.roften.multichat.spy.SpyState;
import com.roften.multichat.spy.AreaSpyState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = MultiChatMod.MODID)
public final class ServerChatRouter {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private ServerChatRouter() {}

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        final ServerPlayer sender = event.getPlayer();
        final String raw = event.getRawText();
        final ChatChannel.ParseResult parsed = ChatChannel.parseOutgoing(raw);
        final ChatChannel channel = parsed.channel();
        final String messageText = parsed.message();

        // We fully handle broadcast to implement per-channel routing + formatting.
        event.setCanceled(true);

        if (messageText.isBlank()) {
            return;
        }

        // Mutes: block any outgoing chat message from muted players.
        // We check here (server-side) so it affects ALL channels equally.
        if (com.roften.multichat.MultiChatConfig.MUTES_ENABLED.get()) {
            var mute = com.roften.multichat.moderation.MuteManager.getMute(sender.getUUID());
            if (mute != null) {
                if (com.roften.multichat.moderation.MuteManager.isMuted(sender)) {
                    sender.sendSystemMessage(com.roften.multichat.moderation.MuteManager.buildMutedMessage(mute));
                    return;
                }
            }
        }

        // Hard permission gate: ADMIN channel ("$a" / "#a") is only usable by permitted players.
        if (channel == ChatChannel.ADMIN && !Perms.has(sender.createCommandSourceStack(), AdminChatState.NODE_ADMIN_CHAT)) {
            sender.sendSystemMessage(Component.literal("Нет прав на админский чат.").withStyle(ChatFormatting.RED));
            return;
        }

        final MinecraftServer server = sender.server;

        // CLAN channel: must go through /opm so the party/clan mod handles delivery/formatting.
        // We still keep our own formatted copy for admin mirror + spy + DB logging.
        if (channel == ChatChannel.CLAN) {
            final MutableComponent formattedClan = format(server, channel, sender, messageText, false);

            try {
                // Execute the command as the sender. Do NOT include a leading '/'.
                server.getCommands().performPrefixedCommand(sender.createCommandSourceStack(), "opm " + messageText);
            } catch (Throwable t) {
                sender.sendSystemMessage(Component.literal("Ошибка отправки в клановый чат (/opm).").withStyle(ChatFormatting.RED));
                MultiChatMod.LOGGER.warn("Failed to execute /opm for clan chat", t);
            }

            // Admin mirror: copy into ADMIN tab (per-admin toggle).
            Component mirrorCopy = AdminChatState.markAdminMirror(formattedClan);
            ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (!AdminChatState.isMirrorEnabled(p)) continue;
                    p.sendSystemMessage(mirrorCopy);
                }
            });

            // Chat spy: send a copy to enabled admins who were NOT already a recipient.
            // We approximate recipients using our clan-target resolver.
            final List<ServerPlayer> clanTargets = resolveTargets(ChatChannel.CLAN, sender);
            final MutableComponent spyFormatted = format(server, channel, sender, messageText, true);
            final Component spyMarked = SpyState.markSpy(spyFormatted);
            ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (!SpyState.isEnabled(p)) continue;
                    if (p.getUUID().equals(sender.getUUID())) continue;
                    boolean alreadyRecipient = clanTargets.stream().anyMatch(t -> t.getUUID().equals(p.getUUID()));
                    if (alreadyRecipient) continue;
                    p.sendSystemMessage(spyMarked);
                }
            });

            // Persist into DB (time, player, message, coordinates, channel)
            ChatLogDatabase.log(server, channel, sender, messageText);

            // Also log to server console.
            MultiChatMod.LOGGER.info("[{}] {}: {}", channel.shortTag, sender.getGameProfile().getName(), messageText);
            return;
        }

        final MutableComponent formatted = format(server, channel, sender, messageText, false);

        // @mentions: ONLY the mentioned player(s) should see this message in all tabs.
        // We do this by sending a marked private copy to the mentioned players,
        // while excluding them from the normal channel recipients.
        final Set<ServerPlayer> mentioned = resolveMentionedPlayers(server, messageText);

        final List<ServerPlayer> targets = resolveTargets(channel, sender);
        if (targets.isEmpty()) {
            sender.sendSystemMessage(Component.translatable("avilixchat.no_recipients").withStyle(ChatFormatting.RED));
            return;
        }

        final List<ServerPlayer> normalTargets;
        if (mentioned.isEmpty()) {
            normalTargets = targets;
        } else {
            normalTargets = targets.stream()
                    .filter(p -> mentioned.stream().noneMatch(m -> m.getUUID().equals(p.getUUID())))
                    .collect(Collectors.toList());
        }

        // We intentionally use SystemMessage packets for channel routing.
        // Mixin-based system logging would otherwise record these again (once per recipient), so suppress it here.
        ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
            for (ServerPlayer target : normalTargets) {
                target.sendSystemMessage(formatted);
            }
        });

        // LOCAL channel extras: keep a short in-memory history and deliver to any /spy area watchers.
        if (channel == ChatChannel.LOCAL) {
            var dim = sender.level().dimension();
            double x = sender.getX();
            double y = sender.getY();
            double z = sender.getZ();

            // Store for potential future radius-history commands.
            ChatHistoryBuffer.recordLocal(dim, x, y, z, formatted);

            // Area spy: copy local messages to admins watching a fixed radius.
            // Avoid duplicates if the admin was already a recipient (local radius OR mentioned).
            Set<UUID> already = new HashSet<>();
            for (ServerPlayer t : normalTargets) already.add(t.getUUID());
            for (ServerPlayer t : mentioned) already.add(t.getUUID());

            AreaSpyState.deliverIfMatches(server, sender, dim, x, y, z, formatted, p -> already.contains(p.getUUID()));
        }

        if (!mentioned.isEmpty()) {
            final MutableComponent privateCopy = Component.empty()
                    .withStyle(s -> s.withInsertion("avilixchat:force_private"))
                    .append(formatted);
            ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
                for (ServerPlayer m : mentioned) {
                    m.sendSystemMessage(privateCopy);
                }
            });
        }

        // Admin mirror: copy messages from other channels into ADMIN tab (per-admin toggle).
        if (channel != ChatChannel.ADMIN) {
            Component mirrorCopy = AdminChatState.markAdminMirror(formatted);
            ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (!AdminChatState.isMirrorEnabled(p)) continue;
                    p.sendSystemMessage(mirrorCopy);
                }
            });
        }

        // Chat spy: send a copy to enabled admins who were NOT already a recipient.
        final MutableComponent spyFormatted = format(server, channel, sender, messageText, true);
        final Component spyMarked = SpyState.markSpy(spyFormatted);
        ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!SpyState.isEnabled(p)) continue;
                if (p.getUUID().equals(sender.getUUID())) continue; // no need to echo back to sender
                boolean alreadyRecipient = normalTargets.stream().anyMatch(t -> t.getUUID().equals(p.getUUID()))
                        || mentioned.stream().anyMatch(t -> t.getUUID().equals(p.getUUID()));
                if (alreadyRecipient) continue; // smart filtering
                p.sendSystemMessage(spyMarked);
            }
        });

        // Persist into DB (time, player, message, coordinates, channel)
        ChatLogDatabase.log(server, channel, sender, messageText);

        // Also log to server console.
        MultiChatMod.LOGGER.info("[{}] {}: {}", channel.shortTag, sender.getGameProfile().getName(), messageText);
    }

    private static MutableComponent format(MinecraftServer server, ChatChannel channel, ServerPlayer sender, String messageText, boolean spyTag) {
        final String ts = LocalTime.now().format(TIME_FMT);

        final TextColor spyGray = TextColor.fromLegacyFormat(ChatFormatting.GRAY);

        // Build with explicit single-space separators to avoid double-spacing with LP prefixes.
        MutableComponent out = Component.literal("[" + ts + "]")
                .withStyle(spyTag ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY);
        out = out.append(Component.literal(""));
        out = out.append(spyTag ? forceColor(channel.channelBadge(), spyGray) : channel.channelBadge());
        out = out.append(Component.literal(""));

        if (spyTag) {
            // In SPY echo: everything is gray, only "SPY" is red.
            out = out.append(Component.literal("[").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("SPY").withStyle(ChatFormatting.DARK_RED))
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY));
        }

        Component lpPrefix = LuckPermsCompat.getPrefix(sender);
        if (!lpPrefix.getString().isEmpty()) {
            out = out.append(spyTag ? forceColor(lpPrefix, spyGray) : lpPrefix);
            out = out.append(Component.literal(""));
        }

        // Player name color comes from LuckPerms meta (NOT from the prefix).
        out = out.append(spyTag
                ? Component.literal(sender.getGameProfile().getName()).withStyle(ChatFormatting.GRAY)
                : PrefixNameStyler.styleName(sender));

        out = out.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));

        // Parse player-provided formatting (legacy + MiniMessage subset) so hex colors work.
        Component parsedMsg = MiniMessageComponentParser.looksLikeMiniMessage(messageText)
                ? MiniMessageComponentParser.parse(messageText)
                : LegacyComponentParser.parse(messageText);

        if (spyTag) {
            // SPY echo must be monochrome.
            parsedMsg = forceColor(parsedMsg, spyGray);
        } else {
            // If player text has no explicit color, apply per-channel default from config.
            if (parsedMsg.getStyle().getColor() == null) {
                int rgb = MultiChatConfig.getTextRgb(channel);
                parsedMsg = parsedMsg.copy().withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(rgb)));
            }
        }

        out = out.append(parsedMsg);
        return out;
    }

    /**
     * Forces the given component (and all its siblings) to the specified text color.
     * Keeps click/hover events and other style bits intact.
     */
    private static MutableComponent forceColor(Component in, TextColor color) {
        if (in == null) return Component.empty().copy();
        MutableComponent out = in.copy().withStyle(s -> s.withColor(color));

        // Re-color siblings recursively (parent styles do not override explicit child colors).
        List<Component> sibs = new ArrayList<>(out.getSiblings());
        out.getSiblings().clear();
        for (Component sib : sibs) {
            out.append(forceColor(sib, color));
        }
        return out;
    }

    private static List<ServerPlayer> resolveTargets(ChatChannel channel, ServerPlayer sender) {
        return switch (channel) {
            case GLOBAL, TRADE -> new ArrayList<>(sender.server.getPlayerList().getPlayers());
            case ADMIN -> resolveAdminTargets(sender.server);
            case LOCAL -> resolveLocalTargets(sender);
            case CLAN -> resolveClanTargets(sender);
        };
    }

    /**
     * Public helper for other server-side hooks (e.g. Xaero waypoint share broadcast routing).
     * Applies the same recipient rules as normal chat:
     * GLOBAL/TRADE -> all, LOCAL -> radius, CLAN -> party/team, ADMIN -> permitted.
     */
    public static List<ServerPlayer> resolveTargetsForChannel(ChatChannel channel, ServerPlayer sender) {
        if (channel == null || sender == null) return List.of();
        return resolveTargets(channel, sender);
    }

    private static List<ServerPlayer> resolveAdminTargets(MinecraftServer server) {
        if (server == null) return List.of();
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Perms.has(p.createCommandSourceStack(), AdminChatState.NODE_ADMIN_CHAT)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Extract "@Name" mentions from plain typed text and map them to online players.
     * Matching is case-insensitive and only exact player names are accepted.
     */
    private static Set<ServerPlayer> resolveMentionedPlayers(MinecraftServer server, String messageText) {
        if (server == null || messageText == null || messageText.isEmpty()) return Set.of();

        Set<String> names = new HashSet<>();
        final int len = messageText.length();
        for (int i = 0; i < len; i++) {
            if (messageText.charAt(i) != '@') continue;
            int j = i + 1;
            if (j >= len) continue;

            int start = j;
            while (j < len) {
                char c = messageText.charAt(j);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    j++;
                    continue;
                }
                break;
            }
            if (j <= start) continue;
            String rawName = messageText.substring(start, j);
            if (rawName.length() < 3 || rawName.length() > 16) continue;
            names.add(rawName.toLowerCase(Locale.ROOT));
            i = j - 1;
        }

        if (names.isEmpty()) return Set.of();

        Set<ServerPlayer> out = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String n = p.getGameProfile().getName();
            if (n == null) continue;
            if (names.contains(n.toLowerCase(Locale.ROOT))) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<ServerPlayer> resolveLocalTargets(ServerPlayer sender) {
        final int r = MultiChatConfig.LOCAL_RADIUS_BLOCKS.getAsInt();
        final double maxDistSqr = (double) r * (double) r;
        final ServerLevel level = sender.serverLevel();

        return level.players().stream()
                .filter(p -> p.distanceToSqr(sender) <= maxDistSqr)
                .collect(Collectors.toList());
    }

    private static List<ServerPlayer> resolveClanTargets(ServerPlayer sender) {
        // Prefer Open Parties and Claims party members if present.
        List<ServerPlayer> partyTargets = OpenPacCompat.tryGetPartyOnlineMembers(sender);
        if (partyTargets != null) {
            return partyTargets;
        }

        // Fallback: scoreboard team as "clan".
        PlayerTeam team = sender.getTeam();
        if (team == null) {
            return List.of(sender);
        }

        Set<String> names = (Set<String>) team.getPlayers();
        List<ServerPlayer> targets = new ArrayList<>();
        for (ServerPlayer p : sender.server.getPlayerList().getPlayers()) {
            if (names.contains(p.getGameProfile().getName())) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) {
            targets.add(sender);
        }
        return targets;
    }
}
