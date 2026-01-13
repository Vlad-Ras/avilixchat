package com.roften.multichat.internal;

/**
 * Small thread-local context used by mixins to avoid duplicate system message logging.
 *
 * <p>Important: this class MUST NOT live in a package that contains mixin classes
 * (e.g. com.roften.multichat.mixin.*), otherwise Sponge Mixin may throw
 * IllegalClassLoadError.
 */
public final class ChatLogMixinContext {
    private ChatLogMixinContext() {}

    private static final ThreadLocal<Boolean> IN_PLAYERLIST_BROADCAST = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void enterPlayerListBroadcast() {
        IN_PLAYERLIST_BROADCAST.set(Boolean.TRUE);
    }

    public static void exitPlayerListBroadcast() {
        IN_PLAYERLIST_BROADCAST.set(Boolean.FALSE);
    }

    public static boolean isInPlayerListBroadcast() {
        return Boolean.TRUE.equals(IN_PLAYERLIST_BROADCAST.get());
    }
}
