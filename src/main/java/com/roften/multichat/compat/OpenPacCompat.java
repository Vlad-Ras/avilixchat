package com.roften.multichat.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Optional integration with "Open Parties and Claims" (OPaC).
 * Uses reflection against the official OPaC API.
 */
public final class OpenPacCompat {
    private OpenPacCompat() {}

    /**
     * @return null if OPaC is not present or its API cannot be called. Otherwise returns a list of online party members.
     */
    @SuppressWarnings("unchecked")
    public static List<ServerPlayer> tryGetPartyOnlineMembers(ServerPlayer sender) {
        try {
            Class<?> apiClass = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Object api = getApiInstance(apiClass, sender.server);
            if (api == null) {
                return null;
            }

            Object partyManager = api.getClass().getMethod("getPartyManager").invoke(api);
            if (partyManager == null) {
                return null;
            }

            Object party = getPartyForMember(partyManager, sender);
            if (party == null) {
                return List.of(sender);
            }

            Method onlineStreamMethod = party.getClass().getMethod("getOnlineMemberStream");
            Object streamObj = onlineStreamMethod.invoke(party);
            if (!(streamObj instanceof Stream<?> stream)) {
                return null;
            }

            List<ServerPlayer> out = new ArrayList<>();
            stream.forEach(o -> {
                if (o instanceof ServerPlayer sp) {
                    out.add(sp);
                }
            });
            if (out.isEmpty()) {
                out.add(sender);
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getApiInstance(Class<?> apiClass, MinecraftServer server) throws Exception {
        // Try the common API pattern: static get(MinecraftServer)
        try {
            Method m = apiClass.getMethod("get", MinecraftServer.class);
            return m.invoke(null, server);
        } catch (NoSuchMethodException ignored) {
            // Try other reasonable names without failing hard.
        }

        for (Method m : apiClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(MinecraftServer.class)) {
                if (apiClass.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null, server);
                }
            }
        }
        return null;
    }

    /**
     * IMPORTANT: this must be a SAFE getter.
     *
     * The previous implementation searched for ANY method name containing "party" and 1 param,
     * which can accidentally call mutators like "leaveParty(UUID)" and remove the player from the party.
     */
    private static Object getPartyForMember(Object partyManager, ServerPlayer sender) throws Exception {
        UUID uuid = sender.getUUID();

        // 1) Try common getter-like names (UUID).
        Object party = tryInvokeGetter(partyManager, uuid,
                new String[]{
                        "getPartyForMember",
                        "getPartyByMember",
                        "getPartyOfMember",
                        "getPartyByMemberUUID",
                        "findPartyForMember",
                        "findPartyByMember"
                });
        if (party != null) return party;

        // 2) Try common getter-like names (player).
        party = tryInvokeGetter(partyManager, sender,
                new String[]{
                        "getPartyForMember",
                        "getPartyByMember",
                        "getPartyOfMember",
                        "getPartyForPlayer",
                        "getPartyByPlayer",
                        "findPartyForMember",
                        "findPartyByMember"
                });
        if (party != null) return party;

        // 3) Fallback: safe scan for methods that LOOK like getters.
        // Only consider methods that:
        // - contain both "get" (or "find") and "party"
        // - have exactly 1 parameter that we can pass (UUID or ServerPlayer)
        // - return a non-primitive, non-boolean, non-void type
        for (Method m : partyManager.getClass().getMethods()) {
            String n = m.getName().toLowerCase();
            if (!(n.contains("party") && (n.contains("get") || n.contains("find")))) continue;
            if (m.getParameterCount() != 1) continue;
            if (!isSafePartyReturnType(m.getReturnType())) continue;

            Class<?> p = m.getParameterTypes()[0];
            try {
                if (p.isAssignableFrom(UUID.class)) {
                    Object res = m.invoke(partyManager, uuid);
                    if (res != null) return res;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (p.isAssignableFrom(sender.getClass())) {
                    Object res = m.invoke(partyManager, sender);
                    if (res != null) return res;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static boolean isSafePartyReturnType(Class<?> ret) {
        if (ret == null) return false;
        if (ret == void.class) return false;
        if (ret.isPrimitive()) return false;
        if (ret == Boolean.class) return false;
        return true;
    }

    private static Object tryInvokeGetter(Object target, Object arg, String[] candidateNames) {
        if (target == null || arg == null || candidateNames == null) return null;

        for (String name : candidateNames) {
            if (name == null || name.isBlank()) continue;
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equalsIgnoreCase(name)) continue;
                if (m.getParameterCount() != 1) continue;
                if (!isSafePartyReturnType(m.getReturnType())) continue;

                Class<?> p = m.getParameterTypes()[0];
                if (!p.isAssignableFrom(arg.getClass())) continue;

                try {
                    Object res = m.invoke(target, arg);
                    if (res != null) return res;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
