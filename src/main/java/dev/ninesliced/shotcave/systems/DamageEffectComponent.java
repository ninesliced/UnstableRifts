package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS component tracking an active damage-over-time effect on an entity.
 * Applied by shoot interactions when a weapon has an ACID, FIRE, or ICE effect.
 */
public final class DamageEffectComponent implements Component<EntityStore> {

    private static final int TICK_INTERVAL_MS = 100;

    private static ComponentType<EntityStore, DamageEffectComponent> componentType;

    private int effectOrdinal;
    private int remainingMs;
    private float damagePerTick;
    private int timeSinceLastTickMs;
    private boolean applySlow;

    public DamageEffectComponent() {
    }

    public static void setComponentType(@Nonnull ComponentType<EntityStore, DamageEffectComponent> type) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<EntityStore, DamageEffectComponent> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("DamageEffectComponent has not been registered yet");
        }
        return componentType;
    }

    public void apply(int effectOrdinal, int durationMs, float damagePerTick, boolean applySlow) {
        this.effectOrdinal = effectOrdinal;
        this.remainingMs = durationMs;
        this.damagePerTick = damagePerTick;
        this.timeSinceLastTickMs = 0;
        this.applySlow = applySlow;
    }

    public int getEffectOrdinal() {
        return effectOrdinal;
    }

    public int getRemainingMs() {
        return remainingMs;
    }

    public float getDamagePerTick() {
        return damagePerTick;
    }

    public boolean shouldApplySlow() {
        return applySlow;
    }

    public boolean isExpired() {
        return remainingMs <= 0;
    }

    /**
     * Advances the timer by the given delta in milliseconds.
     * Returns true if a damage tick should fire this frame.
     */
    public boolean advance(int deltaMs) {
        remainingMs -= deltaMs;
        timeSinceLastTickMs += deltaMs;
        if (timeSinceLastTickMs >= TICK_INTERVAL_MS) {
            timeSinceLastTickMs -= TICK_INTERVAL_MS;
            return true;
        }
        return false;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        DamageEffectComponent copy = new DamageEffectComponent();
        copy.effectOrdinal = this.effectOrdinal;
        copy.remainingMs = this.remainingMs;
        copy.damagePerTick = this.damagePerTick;
        copy.timeSinceLastTickMs = this.timeSinceLastTickMs;
        copy.applySlow = this.applySlow;
        return copy;
    }
}
