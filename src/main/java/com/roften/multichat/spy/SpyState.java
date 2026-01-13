package com.roften.multichat.spy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.roften.multichat.db.ChatLogDatabase;
import com.roften.multichat.moderation.Perms;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session "chat spy" state.
 *
 * Enabled players receive copies of:
 * - chat messages (if they were not already a recipient)
 * - direct system messages sent to other players
 * - executed commands
 */
public final class SpyState {
    private SpyState() {}

    public static final String NODE_SPY = "avilixchat.spy";

    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    // Persist enabled spies across server restarts.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STORE_FILE = "avilixchat-spy-state.json";

    /**
     * Guards against recursion when we are sending spy messages.
     */
    private static final ThreadLocal<Boolean> SENDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * Permission gate for *receiving* / using spy features.
     * Uses LuckPerms node when available; falls back to vanilla permission level from config.
     */
    public static boolean hasSpyPermission(ServerPlayer player) {
        if (player == null) return false;
        return Perms.has(player.createCommandSourceStack(), NODE_SPY);
    }

    public static boolean isEnabled(ServerPlayer player) {
        if (player == null) return false;
        UUID id = player.getUUID();
        if (!ENABLED.contains(id)) return false;

        // If permissions were removed while online, stop sending immediately.
        if (!hasSpyPermission(player)) {
            ENABLED.remove(id);
            savePersisted();
            return false;
        }
        return true;
    }

    public static boolean toggle(ServerPlayer player) {
        if (player == null) return false;
        if (!hasSpyPermission(player)) {
            ENABLED.remove(player.getUUID());
            savePersisted();
            return false;
        }
        UUID id = player.getUUID();
        if (ENABLED.contains(id)) {
            ENABLED.remove(id);
            savePersisted();
            return false;
        }
        ENABLED.add(id);
        savePersisted();
        return true;
    }

    public static void set(ServerPlayer player, boolean on) {
        if (player == null) return;
        if (!hasSpyPermission(player)) {
            ENABLED.remove(player.getUUID());
            savePersisted();
            return;
        }
        if (on) ENABLED.add(player.getUUID());
        else ENABLED.remove(player.getUUID());
        savePersisted();
    }

    /** Loads persisted enabled spy UUIDs from disk (server-side). */
    public static void loadPersisted() {
        ENABLED.clear();
        Path p = persistedPath();
        if (p == null || !Files.exists(p)) return;

        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonElement el = com.google.gson.JsonParser.parseReader(r);
            if (!el.isJsonObject()) return;
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("enabled") || !obj.get("enabled").isJsonArray()) return;
            for (JsonElement e : obj.getAsJsonArray("enabled")) {
                if (!e.isJsonPrimitive()) continue;
                String s = e.getAsString();
                if (s == null || s.isBlank()) continue;
                try {
                    ENABLED.add(UUID.fromString(s.trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Saves current enabled spy UUIDs to disk (server-side). */
    public static void savePersisted() {
        Path p = persistedPath();
        if (p == null) return;

        try {
            Files.createDirectories(p.getParent());
        } catch (Throwable ignored) {
        }

        JsonObject obj = new JsonObject();
        obj.add("enabled", GSON.toJsonTree(ENABLED.stream().map(UUID::toString).toList()));
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            GSON.toJson(obj, w);
        } catch (Throwable ignored) {
        }
    }

    private static Path persistedPath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(STORE_FILE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isSendingSpy() {
        return SENDING.get();
    }

    public static void sendToSpies(MinecraftServer server, ServerPlayer exclude, Component msg) {
        if (server == null || msg == null) return;

        final Component marked = markSpy(msg);

        // Avoid our own system-message mixins seeing these.
        SENDING.set(Boolean.TRUE);
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!isEnabled(p)) continue;
                if (exclude != null && p.getUUID().equals(exclude.getUUID())) continue;

                ChatLogDatabase.runWithoutMixinSystemLogging(() -> p.sendSystemMessage(marked));
            }
        } finally {
            SENDING.set(Boolean.FALSE);
        }
    }

    /** Marks a message as a spy copy so the client routes it into the currently selected tab. */
    public static MutableComponent markSpy(Component original) {
        return Component.empty()
                .withStyle(s -> s.withInsertion("avilixchat:spy"))
                .append(original);
    }

    public static MutableComponent spyPrefix() {
        String ts = TS.format(Instant.now());
        return Component.literal("[" + ts + "] ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("SPY").withStyle(ChatFormatting.DARK_RED))
                .append(Component.literal("] ").withStyle(ChatFormatting.GRAY));
    }

    /**
     * Builds a clickable coordinates component for spy messages.
     * Clicking runs a command that teleports the clicker to the given position (in the correct dimension).
     */
    public static Component coordsComponent(ResourceKey<Level> dim, double x, double y, double z) {
        if (dim == null) return Component.empty();

        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        String dimId = dim.location().toString();

        // Teleport the clicker (@s) into the right dimension.
        // Requires permissions on the client who clicks (admins / ops).
        String cmd = "/execute in " + dimId + " run tp @s " + bx + " " + by + " " + bz;

        Component hover = Component.literal("Телепортироваться: " + bx + " " + by + " " + bz + " (" + dimId + ")")
                .withStyle(ChatFormatting.GRAY);

        return Component.literal(" ")
                .append(Component.literal("[" + bx + " " + by + " " + bz + "]")
                        .withStyle(s -> s
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
    }

    public static Component coordsComponent(ServerPlayer sender) {
        if (sender == null) return Component.empty();
        BlockPos p = sender.blockPosition();
        return coordsComponent(sender.level().dimension(), p.getX(), p.getY(), p.getZ());
    }
}
