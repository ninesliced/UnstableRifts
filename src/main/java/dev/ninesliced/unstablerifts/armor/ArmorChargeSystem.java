package dev.ninesliced.unstablerifts.armor;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Ticks the passive armor charge timer for all entities with an
 * {@link ArmorChargeComponent}. Fills the charge from 0→100% over 30s.
 * Also syncs the charge progress to the entity's SignatureEnergy stat
 * so the client Ability1 bar reflects the fill state.
 */
public final class ArmorChargeSystem extends EntityTickingSystem<EntityStore> {

    private static boolean hasCompleteSetAbility(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull Store<EntityStore> store) {
        InventoryComponent.Armor armorComponent = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComponent == null) {
            return false;
        }

        ItemContainer armorInventory = armorComponent.getInventory();
        String setId = null;
        int count = 0;
        for (int slot = 0; slot < 4; slot++) {
            ItemStack stack = armorInventory.getItemStack((short) slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }

            String currentSetId = ArmorItemMetadata.getSetId(stack);
            if (currentSetId == null) {
                return false;
            }
            if (setId == null) {
                setId = currentSetId;
            } else if (!setId.equals(currentSetId)) {
                return false;
            }
            count++;
        }

        if (setId == null || count < 4) {
            return false;
        }

        return ArmorDefinitions.getBySetId(setId).stream()
                .findFirst()
                .map(ArmorDefinition::setAbility)
                .filter(ability -> ability != ArmorSetAbility.NONE)
                .isPresent();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(ArmorChargeComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ArmorChargeComponent charge = archetypeChunk.getComponent(index, ArmorChargeComponent.getComponentType());
        if (charge == null) return;
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        boolean hasCompleteSetAbility = hasCompleteSetAbility(ref, store);

        // Tick active buff expiration
        if (charge.hasActiveBuff()) {
            ArmorSetAbility activeAbility = charge.getActiveAbility();

            // Regeneration: heal every tick while buff is active
            if (activeAbility == ArmorSetAbility.REGENERATION) {
                EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
                if (statMap != null) {
                    int healthIdx = DefaultEntityStatTypes.getHealth();
                    EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
                    if (healthStat != null) {
                        // 10s duration, heal full HP over that time: maxHP / (10 * 20 TPS)
                        float regenPerTick = healthStat.getMax() / (float) activeAbility.getDurationTicks();
                        float newHealth = Math.min(healthStat.getMax(), healthStat.get() + regenPerTick);
                        statMap.setStatValue(healthIdx, newHealth);
                    }
                }
            }

            if (charge.tickBuff()) {
                ArmorAbilityBuffSystem.expireBuff(ref, commandBuffer, activeAbility);
                charge.clearBuff();
            }
        }

        // Advance passive charge (only when no active buff)
        if (!charge.hasActiveBuff()) {
            charge.advanceCharge();
        }

        // Sync to SignatureEnergy stat for client ability bar display
        EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (statMap != null) {
            int sigEnergyIdx = DefaultEntityStatTypes.getSignatureEnergy();
            if (sigEnergyIdx >= 0) {
                float progress = (charge.hasActiveBuff() || hasCompleteSetAbility)
                        ? charge.getChargeProgress() * 100.0f
                        : 0.0f;
                statMap.setStatValue(sigEnergyIdx, progress);
            }
        }
    }
}
