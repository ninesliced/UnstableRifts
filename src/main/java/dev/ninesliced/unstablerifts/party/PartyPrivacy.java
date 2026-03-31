package dev.ninesliced.unstablerifts.party;

import javax.annotation.Nullable;
import java.util.Locale;

public enum PartyPrivacy {
    PUBLIC,
    PRIVATE;

    @Nullable
    public static PartyPrivacy from(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public String getDisplayName() {
        return this == PUBLIC ? "Public" : "Private";
    }
}