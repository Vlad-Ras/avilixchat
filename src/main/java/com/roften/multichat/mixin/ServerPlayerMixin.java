package com.roften.multichat.mixin;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.server.ServerActiveChannelState;
import com.roften.multichat.compat.XaeroWaypointCompat;
import com.roften.multichat.compat.XaeroWaypointShareCompat;
import com.roften.multichat.db.ChatLogDatabase;
import com.roften.multichat.internal.ChatLogMixinContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs system/mod messages that are sent to a single player via sendSystemMessage(...).
 *
 * Additionally, we handle Xaero waypoint share lines here because some Xaero builds send them
 * per-recipient (ServerPlayer#sendSystemMessage) and others broadcast them.
 *
 * Goals:
 * 1) Prevent duplicates when the same share line is sent twice.
 * 2) Ensure the line is routed into the chat tab (channel) that the sharer currently has open
 *    by tagging it with our hidden insertion token.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    /** Guard to avoid infinite recursion when we re-send a tagged copy. */
    private static final ThreadLocal<Boolean> ROUTE_GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Per-recipient recent waypoint share dedupe (keeps only a small window). */
    private static final long WAYPOINT_DEDUP_WINDOW_MS = 1200L;
    private static final int WAYPOINT_DEDUP_MAX = 64;
    private static final ConcurrentHashMap<UUID, LinkedHashMap<String, Long>> WAYPOINT_RECENT = new ConcurrentHashMap<>();

    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void multichat$sendSystemMessage_head(Component message, CallbackInfo ci) {
        if (handleWaypointShare(message, false, false)) {
            ci.cancel();
            return;
        }
        multichat$logDirectSystem(message);
    }

    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void multichat$sendSystemMessageOverlay_head(Component message, boolean overlay, CallbackInfo ci) {
        if (handleWaypointShare(message, true, overlay)) {
            ci.cancel();
            return;
        }
        multichat$logDirectSystem(message);
    }

    /**
     * @return true if we cancelled the original send (either suppressed as duplicate or re-sent tagged).
     */
    private boolean handleWaypointShare(Component message, boolean hasOverlay, boolean overlay) {
        if (ROUTE_GUARD.get()) return false;
        if (message == null) return false;

        // Only handle Xaero waypoint share lines.
        if (!XaeroWaypointShareCompat.isWaypointShare(message)) return false;

        ServerPlayer self = (ServerPlayer) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) return false;

        String key = message.getString();
        if (key == null || key.isBlank()) return false;

        // Per-recipient dedupe: Xaero sometimes sends the same share line twice.
        // Mark the first sighting so any immediate re-send is suppressed.
        if (markOrIsRecentWaypointDuplicate(self.getUUID(), key)) {
            return true; // suppress duplicate
        }

        // If already tagged with a channel, let it through.
        Optional<ChatChannel> existing = ChatChannel.detectFromComponent(message);
        if (existing.isPresent()) return false;

        // Tag with the sharer's currently active channel.
        // Some Xaero builds do NOT include the sharer name in the per-recipient system line;
        // in that case fall back to the most recent "candidate" captured from executed commands.
        ServerPlayer sharer = XaeroWaypointShareCompat.findSharer(server, message);

        ChatChannel ch;
        if (sharer != null) {
            ch = ServerActiveChannelState.getOrDefault(sharer.getUUID(), ChatChannel.GLOBAL);
        } else {
            ch = XaeroWaypointCompat.peekPendingShareChannel();
            if (ch == null) return false;
        }

        Component tagged = XaeroWaypointShareCompat.tagChannel(ch, message);

        // Re-send a single tagged copy, and cancel the original untagged one.
        try {
            ROUTE_GUARD.set(Boolean.TRUE);
            if (hasOverlay) {
                self.sendSystemMessage(tagged, overlay);
            } else {
                self.sendSystemMessage(tagged);
            }
        } finally {
            ROUTE_GUARD.set(Boolean.FALSE);
        }
        return true;
    }

    private static boolean markOrIsRecentWaypointDuplicate(UUID recipient, String key) {
        if (recipient == null || key == null) return false;
        long now = System.currentTimeMillis();

        LinkedHashMap<String, Long> map = WAYPOINT_RECENT.computeIfAbsent(recipient, u -> new LinkedHashMap<>(WAYPOINT_DEDUP_MAX, 0.75f, true));
        synchronized (map) {
            purgeOld(map, now);
            Long prev = map.get(key);
            if (prev != null && (now - prev) <= WAYPOINT_DEDUP_WINDOW_MS) return true;
            map.put(key, now);
            while (map.size() > WAYPOINT_DEDUP_MAX) {
                String eldest = map.keySet().iterator().next();
                map.remove(eldest);
            }
            return false;
        }
    }

    private static void purgeOld(LinkedHashMap<String, Long> map, long now) {
        if (map == null) return;
        // Remove entries older than window * 2 to keep the map small.
        long cutoff = now - (WAYPOINT_DEDUP_WINDOW_MS * 2L);
        var it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            Long t = e.getValue();
            if (t == null || t < cutoff) {
                it.remove();
            }
        }
    }

    private void multichat$logDirectSystem(Component message) {
        if (ChatLogDatabase.isMixinSystemLoggingSuppressed()) return;
        if (ChatLogMixinContext.isInPlayerListBroadcast()) return; // broadcast already logged once
        if (message == null) return;

        String text = message.getString();
        if (text == null || text.isBlank()) return;

        ServerPlayer self = (ServerPlayer) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) return;

        // IMPORTANT:
        // Spy must NOT mirror arbitrary server->player system messages.
        // Admins only want to spy on chat/commands, not on system notifications, errors, etc.
        // (Those can be extremely noisy and look like duplicates.)

        if (!MultiChatConfig.CHATLOG_INCLUDE_SYSTEM_MESSAGES.getAsBoolean()) return;

        BlockPos pos = self.blockPosition();
        String dim = self.level().dimension().location().toString();

        ChatLogDatabase.logSystem(server, text, dim, pos.getX(), pos.getY(), pos.getZ());
    }
}
