package com.roften.multichat.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.roften.multichat.chat.ChatChannel;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Client-only persisted UI state.
 *
 * <p>NeoForge configs are great for "settings", but for per-user UI state (like merged tabs)
 * we store a small JSON next to configs so it survives client restarts and does not mix with
 * server/common config synchronization.</p>
 */
public final class ClientPersistedState {
    private ClientPersistedState() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "avilixchat-client-state.json";

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /**
     * Loads merged tabs from disk and inserts them into the provided set.
     * The set is cleared first.
     */
    public static void loadMergedTabsInto(EnumSet<ChatChannel> out) {
        if (out == null) return;
        out.clear();

        Path p = file();
        if (!Files.exists(p)) return;

        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonElement el = com.google.gson.JsonParser.parseReader(r);
            if (!el.isJsonObject()) return;
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("mergedTabs") || !obj.get("mergedTabs").isJsonArray()) return;

            for (JsonElement e : obj.getAsJsonArray("mergedTabs")) {
                if (!e.isJsonPrimitive()) continue;
                String s = e.getAsString();
                if (s == null || s.isBlank()) continue;
                try {
                    out.add(ChatChannel.valueOf(s.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Saves the given merged tabs set to disk. */
    public static void saveMergedTabs(Collection<ChatChannel> merged) {
        Path p = file();
        try {
            Files.createDirectories(p.getParent());
        } catch (Throwable ignored) {
        }

        List<String> names = new ArrayList<>();
        if (merged != null) {
            for (ChatChannel ch : merged) {
                if (ch == null) continue;
                names.add(ch.name());
            }
        }

        JsonObject obj = new JsonObject();
        obj.add("mergedTabs", GSON.toJsonTree(names));

        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            GSON.toJson(obj, w);
        } catch (Throwable ignored) {
        }
    }

    /** Convenience: replace merged tabs with the given set and persist. */
    public static void replaceMergedTabs(Set<ChatChannel> channels) {
        saveMergedTabs(channels);
    }
}
