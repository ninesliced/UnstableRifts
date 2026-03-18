package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS component attached to summoned NPCs to carry the weapon's damage effect.
 * When the NPC deals damage, the {@link SummonedNPCDamageEffectSystem} reads
 * this component and applies the corresponding DoT to the target.
 */
public final class SummonedEffectComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, SummonedEffectComponent> componentType;

    private int effectOrdinal;

    public SummonedEffectComponent() {
    }

    public static void setComponentType(@Nonnull ComponentType<EntityStore, SummonedEffectComponent> type) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<EntityStore, SummonedEffectComponent> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("SummonedEffectComponent has not been registered yet");
        }
        return componentType;
    }

    public void setEffectOrdinal(int effectOrdinal) {
        this.effectOrdinal = effectOrdinal;
    }

    public int getEffectOrdinal() {
        return effectOrdinal;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        SummonedEffectComponent copy = new SummonedEffectComponent();
        copy.effectOrdinal = this.effectOrdinal;
        return copy;
    }
}
