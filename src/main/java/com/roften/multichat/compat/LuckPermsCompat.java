package com.roften.multichat.compat;

import com.roften.multichat.MultiChatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional integration with the LuckPerms API.
 *
 * <p>Implemented via reflection so the mod has no hard dependency on LuckPerms.
 */
public final class LuckPermsCompat {
    private LuckPermsCompat() {}

    /**
     * Returns a formatted prefix component for a player, or empty if LuckPerms is not present.
     */
    public static Component getPrefix(ServerPlayer player) {
        String prefix = getPrefixString(player.getUUID());
        if (prefix == null || prefix.isBlank()) {
            return Component.empty();
        }

        // LuckPerms prefixes often contain accidental leading/trailing spaces.
        // We normalize them here and let the chat formatter add separators.
        String s = trimAscii(prefix);
        if (s.isEmpty()) {
            return Component.empty();
        }

        String mode = String.valueOf(MultiChatConfig.LUCKPERMS_PREFIX_FORMAT.get()).trim().toUpperCase();
        return switch (mode) {
            case "MINIMESSAGE" -> MiniMessageComponentParser.parse(s);
            case "PLAIN" -> Component.literal(s);
            case "LEGACY" -> LegacyComponentParser.parse(s);
            default -> {
                // AUTO
                if (MiniMessageComponentParser.looksLikeMiniMessage(s)) {
                    yield MiniMessageComponentParser.parse(s);
                }
                yield LegacyComponentParser.parse(s);
            }
        };
    }

    /**
     * Returns the preferred nickname color (RGB) from LuckPerms meta.
     *
     * <p>This intentionally does NOT derive the color from the prefix.
     * Configure your LuckPerms meta with one of these keys (first match wins):
     * <ul>
     *   <li>name-color / name_color / namecolor</li>
     *   <li>chat-color / chat_color / chatcolor</li>
     *   <li>color</li>
     * </ul>
     *
     * <p>Supported value formats: {@code #RRGGBB}, {@code &#RRGGBB}, {@code §#RRGGBB},
     * legacy {@code &c}/{@code §c}, and MiniMessage color tags like {@code <#RRGGBB>}.
     */
    public static Integer getNameColorRgb(ServerPlayer player) {
        if (player == null) return null;
        UUID uuid = player.getUUID();

        String raw = null;
        for (String key : NAME_COLOR_META_KEYS) {
            raw = getMetaValueString(uuid, key);
            if (raw != null && !raw.isBlank()) break;
        }
        if (raw == null || raw.isBlank()) return null;

        String s = trimAscii(raw);
        if (s.isEmpty()) return null;

        // Parse by applying the color to a single probe character and then reading it back.
        String probe = s + "x";
        Component c = MiniMessageComponentParser.looksLikeMiniMessage(s)
                ? MiniMessageComponentParser.parse(probe)
                : LegacyComponentParser.parse(probe);

        Integer rgb = firstRgbFromComponent(c);
        return rgb;
    }

    private static final String[] NAME_COLOR_META_KEYS = new String[] {
            "username-color", "username_color", "usernamecolor",
            "name-color", "name_color", "namecolor",
            "chat-color", "chat_color", "chatcolor",
            "color",
            "nameColor", "chatColor"
    };

    private static Integer firstRgbFromComponent(Component c) {
        if (c == null) return null;
        List<Integer> stops = new ArrayList<>();
        collectColors(c, stops);
        if (stops.isEmpty()) return null;
        return stops.get(0);
    }

    private static void collectColors(Component c, List<Integer> out) {
        if (c == null) return;
        var color = c.getStyle().getColor();
        if (color != null) {
            int rgb = color.getValue();
            if (out.isEmpty() || out.get(out.size() - 1) != rgb) {
                out.add(rgb);
            }
        }
        for (Component sib : c.getSiblings()) {
            collectColors(sib, out);
        }
    }

    private static String trimAscii(String in) {
        if (in == null) return "";
        int start = 0;
        int end = in.length();
        while (start < end) {
            char c = in.charAt(start);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            start++;
        }
        while (end > start) {
            char c = in.charAt(end - 1);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            end--;
        }
        return in.substring(start, end);
    }

    /**
     * LuckPerms permission check. Returns:
     * - Boolean.TRUE / Boolean.FALSE if LuckPerms is present
     * - null if LuckPerms is not present or could not be queried
     */
    public static Boolean hasPermission(ServerPlayer player, String node) {
        if (player == null || node == null || node.isBlank()) return null;
        try {
            Object lp = getLuckPerms();
            if (lp == null) return null;

            Object userManager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUUID());
            if (user == null) return null;

            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object permData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
            Object tristate = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, node);

            // Tristate has asBoolean() in LuckPerms API
            Method asBoolean = tristate.getClass().getMethod("asBoolean");
            Object res = asBoolean.invoke(tristate);
            if (res instanceof Boolean b) return b;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * CommandSource permission helper used in Brigadier .requires(...)
     */
    public static boolean hasPermission(CommandSourceStack source, String node, int vanillaFallbackLevel) {
        if (source == null) return false;
        if (source.getEntity() instanceof ServerPlayer sp) {
            Boolean lp = hasPermission(sp, node);
            if (lp != null) return lp;
        }
        return source.hasPermission(vanillaFallbackLevel);
    }

    private static String getPrefixString(UUID uuid) {
        try {
            Object lp = getLuckPerms();
            if (lp == null) return null;

            Object userManager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null) {
                return null;
            }

            Object metaData = getCachedMetaData(lp, user);
            if (metaData == null) return null;
            Object prefix = metaData.getClass().getMethod("getPrefix").invoke(metaData);
            return prefix instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getMetaValueString(UUID uuid, String key) {
        if (uuid == null || key == null || key.isBlank()) return null;
        try {
            Object lp = getLuckPerms();
            if (lp == null) return null;

            Object userManager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null) return null;

            Object metaData = getCachedMetaData(lp, user);
            if (metaData == null) return null;

            // LuckPerms API: CachedMetaData#getMetaValue(String)
            Method m = metaData.getClass().getMethod("getMetaValue", String.class);
            Object v = m.invoke(metaData, key);
            return v instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * LuckPerms meta is contextual: a user can have different meta per server/world.
     *
     * <p>We try to obtain QueryOptions from the ContextManager and then call CachedData#getMetaData(QueryOptions).
     * If this API is not available in a given LP version, we fall back to CachedData#getMetaData().
     */
    private static Object getCachedMetaData(Object lp, Object user) {
        if (lp == null || user == null) return null;
        try {
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);

            // Try contextual QueryOptions: lp.getContextManager().getQueryOptions(user)
            Object queryOptions = null;
            try {
                Object ctxMgr = lp.getClass().getMethod("getContextManager").invoke(lp);

                // LuckPerms API differs a bit between versions. We locate getQueryOptions(...) by name.
                Method getQueryOptions = null;
                for (Method m : ctxMgr.getClass().getMethods()) {
                    if (!m.getName().equals("getQueryOptions")) continue;
                    if (m.getParameterCount() != 1) continue;
                    getQueryOptions = m;
                    break;
                }

                if (getQueryOptions != null) {
                    Object res = getQueryOptions.invoke(ctxMgr, user);
                    if (res instanceof CompletableFuture<?> cf) {
                        // Join is fine here: LP keeps this completed for online users.
                        queryOptions = cf.getNow(null);
                        if (queryOptions == null) queryOptions = cf.join();
                    } else {
                        queryOptions = res;
                    }
                }
            } catch (Throwable ignored) {
                queryOptions = null;
            }

            if (queryOptions != null) {
                try {
                    Method m = cachedData.getClass().getMethod("getMetaData", queryOptions.getClass());
                    return m.invoke(cachedData, queryOptions);
                } catch (NoSuchMethodException ignored) {
                    // Method signature may be getMetaData(net.luckperms.api.query.QueryOptions)
                    for (Method m : cachedData.getClass().getMethods()) {
                        if (!m.getName().equals("getMetaData")) continue;
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length != 1) continue;
                        if (params[0].isInstance(queryOptions) || params[0].isAssignableFrom(queryOptions.getClass())) {
                            return m.invoke(cachedData, queryOptions);
                        }
                    }
                }
            }

            // Fallback: non-contextual
            return cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getLuckPerms() {
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Method get = provider.getMethod("get");
            return get.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
