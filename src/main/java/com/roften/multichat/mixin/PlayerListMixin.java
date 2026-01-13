package com.roften.multichat.mixin;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.chat.ChatChannel;
import com.roften.multichat.chat.server.ServerActiveChannelState;
import com.roften.multichat.chat.server.ServerChatRouter;
import com.roften.multichat.compat.XaeroWaypointCompat;
import com.roften.multichat.compat.XaeroWaypointShareCompat;
import com.roften.multichat.db.ChatLogDatabase;
import com.roften.multichat.internal.ChatLogMixinContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Logs system/mod messages that are broadcast by the server to the whole player list.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final private MinecraftServer server;

    /** Broadcast-level dedupe for Xaero shares (some builds broadcast the same line twice). */
    private static final long XAERO_BROADCAST_DEDUP_WINDOW_MS = 1200L;
    private static final int XAERO_BROADCAST_DEDUP_MAX = 64;
    private static final LinkedHashMap<String, Long> XAERO_RECENT_BROADCAST = new LinkedHashMap<>(XAERO_BROADCAST_DEDUP_MAX, 0.75f, true);

    @Inject(
            method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void multichat$broadcastSystemMessage_head(Component message, boolean overlay, CallbackInfo ci) {
        ChatLogMixinContext.enterPlayerListBroadcast();

        // Special case: Xaero waypoint share is a clickable SYSTEM broadcast.
        // On the client we treat clickable system lines as "private" (visible in every tab) to preserve mod UX.
        // For Xaero shares we MUST instead route them into the sharer's currently opened tab.
        if (message != null && (XaeroWaypointShareCompat.isWaypointShare(message) || XaeroWaypointCompat.looksLikeWaypointShareBroadcast(message))) {
            // If it's already tagged with a channel, let vanilla broadcast proceed.
            Optional<ChatChannel> existing = ChatChannel.detectFromComponent(message);
            if (existing.isEmpty()) {
                // Dedupe: if Xaero broadcasts the same share line twice, suppress the second broadcast entirely.
                if (isRecentXaeroBroadcastDuplicate(message)) {
                    ChatLogMixinContext.exitPlayerListBroadcast();
                    ci.cancel();
                    return;
                }

                // Determine the channel the sharer had opened.
                // Also try to determine the actual sharer player so we can apply channel recipient rules
                // (LOCAL radius, CLAN party/team members, ADMIN permissions).
                ServerPlayer sharer = null;
                ChatChannel ch = null;
                try {
                    sharer = XaeroWaypointShareCompat.findSharer(server, message);
                    if (sharer != null) {
                        ch = ServerActiveChannelState.getOrDefault(sharer.getUUID(), ChatChannel.GLOBAL);
                    }
                } catch (Throwable ignored) {
                }
                if (sharer == null) {
                    try {
                        var pendingUuid = XaeroWaypointCompat.peekPendingSharerUuid();
                        if (pendingUuid != null) {
                            sharer = server.getPlayerList().getPlayer(pendingUuid);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // Consume pending marker (if any) once we handle a share broadcast.
                ChatChannel pending = XaeroWaypointCompat.consumePendingShareChannel();
                if (ch == null) ch = pending;
                if (ch == null) ch = ChatChannel.GLOBAL;

				Component tagged = XaeroWaypointShareCompat.tagChannel(ch, message);

				// Copy to effectively-final locals for lambda capture below.
				final ChatChannel chFinal = ch;
				final ServerPlayer sharerFinal = sharer;
				final Component taggedFinal = tagged;
				final boolean overlayFinal = overlay;
				final var serverFinal = server;

                // Log once per broadcast (not once per recipient).
                if (!ChatLogDatabase.isMixinSystemLoggingSuppressed()
                        && MultiChatConfig.CHATLOG_INCLUDE_SYSTEM_MESSAGES.getAsBoolean()) {
                    try {
                        String text = message.getString();
                        if (text != null && !text.isBlank()) {
                            ChatLogDatabase.logSystem(server, text, "server", 0, 0, 0);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                // Re-send a single tagged copy to channel recipients and cancel the original broadcast.
                // Suppress mixin system logging because we already log the broadcast once here.
				ChatLogDatabase.runWithoutMixinSystemLogging(() -> {
					var targets = (sharerFinal != null)
							? ServerChatRouter.resolveTargetsForChannel(chFinal, sharerFinal)
							: serverFinal.getPlayerList().getPlayers();
					for (ServerPlayer p : targets) {
						if (p == null) continue;
						p.sendSystemMessage(taggedFinal, overlayFinal);
					}
				});

                ChatLogMixinContext.exitPlayerListBroadcast();
                ci.cancel();
                return;
            }
        }

        // Log once per broadcast (not once per recipient).
        if (!ChatLogDatabase.isMixinSystemLoggingSuppressed()
                && MultiChatConfig.CHATLOG_INCLUDE_SYSTEM_MESSAGES.getAsBoolean()
                && message != null) {
            String text = message.getString();
            if (text != null && !text.isBlank()) {
                ChatLogDatabase.logSystem(server, text, "server", 0, 0, 0);
            }
        }
    }

    private static boolean isRecentXaeroBroadcastDuplicate(Component message) {
        String key = "";
        try {
            String plain = message.getString();
            String cmd = XaeroWaypointShareCompat.firstCommandValue(message);
            key = (plain == null ? "" : plain) + "|" + (cmd == null ? "" : cmd);
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        synchronized (XAERO_RECENT_BROADCAST) {
            // Purge old entries.
            long cutoff = now - (XAERO_BROADCAST_DEDUP_WINDOW_MS * 2L);
            var it = XAERO_RECENT_BROADCAST.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                Long t = e.getValue();
                if (t == null || t < cutoff) it.remove();
            }

            Long prev = XAERO_RECENT_BROADCAST.get(key);
            if (prev != null && (now - prev) <= XAERO_BROADCAST_DEDUP_WINDOW_MS) {
                return true;
            }
            XAERO_RECENT_BROADCAST.put(key, now);
            while (XAERO_RECENT_BROADCAST.size() > XAERO_BROADCAST_DEDUP_MAX) {
                String eldest = XAERO_RECENT_BROADCAST.keySet().iterator().next();
                XAERO_RECENT_BROADCAST.remove(eldest);
            }
            return false;
        }
    }

    @Inject(
            method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("RETURN"),
            require = 0
    )
    private void multichat$broadcastSystemMessage_return(Component message, boolean overlay, CallbackInfo ci) {
        ChatLogMixinContext.exitPlayerListBroadcast();
    }
}

