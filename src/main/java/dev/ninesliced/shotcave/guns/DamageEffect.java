package dev.ninesliced.shotcave.guns;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public enum DamageEffect {
    NONE(255, 255, 255, 0.0f, 0.0f, "", null),
    ACID(110, 255, 120, 0.5f, 0.5f, "ACID", "Shotcave_Poison"),
    FIRE(255, 150, 90, 0.5f, 0.5f, "FIRE", "Flame_Staff_Burn"),
    ICE(120, 220, 255, 0.5f, 0.5f, "ICE", "Slow"),
    ELECTRICITY(255, 235, 90, 0.5f, 0.5f, "ELECTRICITY", "Stun"),
    VOID(210, 90, 255, 0.5f, 0.5f, "VOID", "Shotcave_Void_Portal_DOT");

    private final int trailR;
    private final int trailG;
    private final int trailB;
    private final float dotDurationMin;
    private final float dotDurationMax;
    @Nonnull
    private final String displayName;
    @Nullable
    private final String entityEffectId;

    DamageEffect(int trailR, int trailG, int trailB,
                 float dotDurationMin, float dotDurationMax,
                 @Nonnull String displayName,
                 @Nullable String entityEffectId) {
        this.trailR = trailR;
        this.trailG = trailG;
        this.trailB = trailB;
        this.dotDurationMin = dotDurationMin;
        this.dotDurationMax = dotDurationMax;
        this.displayName = displayName;
        this.entityEffectId = entityEffectId;
    }

    public int getTrailR() {
        return trailR;
    }

    public int getTrailG() {
        return trailG;
    }

    public int getTrailB() {
        return trailB;
    }

    public float getDotDurationMin() {
        return dotDurationMin;
    }

    public float getDotDurationMax() {
        return dotDurationMax;
    }

    public boolean hasDoT() {
        return dotDurationMin > 0.0f;
    }

    @Nullable
    public String getEntityEffectId() {
        return entityEffectId;
    }

    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    @Nonnull
    public static DamageEffect fromOrdinal(int ordinal) {
        DamageEffect[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return NONE;
        }
        return values[ordinal];
    }

    @Nonnull
    public static DamageEffect fromString(@Nonnull String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
