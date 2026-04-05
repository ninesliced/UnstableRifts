package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Determines and applies visual + gameplay scale for dungeon NPCs.
 * <ul>
 *   <li>Kweebec_DeadWood_Seedling / Kweebec_Seedling (ally) — x3</li>
 *   <li>Kweebec_DeadWood_Sproutling variants — x1.5</li>
 *   <li>Kweebec_DeadWood_Rootling variants — x1.2</li>
 *   <li>Industrial_Hazmat / Industrial_Nosuit — x1.2</li>
 *   <li>Boss_Forklift / Boss_Zombie_Commander — x1.2</li>
 * </ul>
 * Damage is scaled automatically by {@link ScaledNPCDamageSystem}.
 * Attack range in JSON configs should be pre-scaled to match.
 */
public final class KweebecScaleHelper {

    public static final float SEEDLING_SCALE = 2.0f;
    public static final float SPROUTLING_SCALE = 1.5f;
    public static final float ROOTLING_SCALE = 1.2f;
    public static final float INDUSTRIAL_SCALE = 1.2f;
    public static final float BOSS_SCALE = 1.2f;

    private KweebecScaleHelper() {
    }

    /**
     * Returns the scale to apply for the given NPC role name, or 0 if no
     * custom scale is needed.
     */
    public static float getScaleForRole(@Nullable String roleName) {
        if (roleName == null) return 0f;

        // Kweebec DeadWood tiers only
        if (roleName.equals("Kweebec_DeadWood_Seedling")) return SEEDLING_SCALE;
        if (roleName.startsWith("Kweebec_DeadWood_Sproutling")) return SPROUTLING_SCALE;
        if (roleName.startsWith("Kweebec_DeadWood_Rootling")) return ROOTLING_SCALE;
        // Bare template fallback (Kweebec_DeadWood without suffix)
        if (roleName.startsWith("Kweebec_DeadWood")) return ROOTLING_SCALE;

        // Industrial
        if (roleName.startsWith("Industrial_Hazmat") || roleName.equals("Industrial_Nosuit")) {
            return INDUSTRIAL_SCALE;
        }

        // Bosses
        if (roleName.startsWith("Boss_Forklift") || roleName.startsWith("Boss_Zombie_Commander")) {
            return BOSS_SCALE;
        }

        return 0f;
    }

    /**
     * Applies the appropriate {@link EntityScaleComponent} to a newly-spawned
     * NPC. If the mob ID does not match a scaled role, this is a no-op.
     */
    public static void applyScale(@Nonnull Store<EntityStore> store,
                                  @Nonnull Ref<EntityStore> mobRef,
                                  @Nonnull String mobId) {
        float scale = getScaleForRole(mobId);
        if (scale <= 0f) return;

        EntityScaleComponent existing = store.getComponent(mobRef, EntityScaleComponent.getComponentType());
        if (existing != null) {
            existing.setScale(scale);
        } else {
            store.addComponent(mobRef, EntityScaleComponent.getComponentType(),
                    new EntityScaleComponent(scale));
        }
    }

    /**
     * CommandBuffer-safe variant of {@link #applyScale} for use inside tick systems
     * where the store is in processing mode and direct writes are not allowed.
     */
    public static void applyScale(@Nonnull Store<EntityStore> store,
                                  @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull Ref<EntityStore> mobRef,
                                  @Nonnull String mobId) {
        float scale = getScaleForRole(mobId);
        if (scale <= 0f) return;

        EntityScaleComponent existing = store.getComponent(mobRef, EntityScaleComponent.getComponentType());
        if (existing != null) {
            existing.setScale(scale);
        } else {
            commandBuffer.putComponent(mobRef, EntityScaleComponent.getComponentType(),
                    new EntityScaleComponent(scale));
        }
    }

    /**
     * Reads the scale of an NPC from its {@link EntityScaleComponent}, falling
     * back to 1.0 when none is present.
     */
    public static float getScale(@Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> ref) {
        EntityScaleComponent sc = store.getComponent(ref, EntityScaleComponent.getComponentType());
        return sc != null ? sc.getScale() : 1.0f;
    }
}
