package com.roften.multichat.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-ChatScreen ephemeral state for @mention suggestions.
 */
public final class MentionSuggestState {
    /** Index of '@' that started the current suggestion, or -1 when closed. */
    public int atIndex = -1;

    /** Cursor position used for building suggestions. */
    public int cursor = 0;

    /** Current filtered candidates (player names). */
    public final List<String> candidates = new ArrayList<>();

    /** Selected candidate index. */
    public int selected = 0;

    public boolean isOpen() {
        return atIndex >= 0 && !candidates.isEmpty();
    }

    public void close() {
        atIndex = -1;
        cursor = 0;
        candidates.clear();
        selected = 0;
    }
}
