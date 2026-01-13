package com.roften.multichat.moderation;

import java.util.UUID;

/**
 * Represents a mute entry. If expiresAtEpochMs < 0 => permanent.
 */
public record MuteEntry(
        UUID targetUuid,
        String targetName,
        UUID actorUuid,
        String actorName,
        long createdAtEpochMs,
        long expiresAtEpochMs,
        String reason
) {
    public boolean isPermanent() {
        return expiresAtEpochMs < 0;
    }

    public boolean isExpired(long nowEpochMs) {
        return !isPermanent() && nowEpochMs >= expiresAtEpochMs;
    }

// Backwards-compatible aliases
public long mutedAtMs() {
    return createdAtEpochMs;
}

public long expiresAtMs() {
    return expiresAtEpochMs;
}

}
