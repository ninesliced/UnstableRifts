package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponCategory;
import dev.ninesliced.shotcave.guns.WeaponDefinition;
import dev.ninesliced.shotcave.guns.WeaponDefinitions;
import dev.ninesliced.shotcave.guns.WeaponModifierType;
import dev.ninesliced.shotcave.guns.WeaponRarity;
import dev.ninesliced.shotcave.armor.ArmorAbilityBuffSystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Inspects all damage events. When the source entity is a player holding a
 * MELEE weapon with SC_Effect or WEAPON_DAMAGE modifiers, applies the DoT
 * effect to the target and scales damage accordingly.
 * <p>
 * This bridges the gap between vanilla sword interactions (which know nothing
 * about Shotcave BSON metadata) and the mod's modifier/effect system.
 */
public final class MeleeDamageEffectSystem extends DamageEventSystem {

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        if (damage.isCancelled()) return;

        // Only process player-sourced damage
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) return;

        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (!sourceRef.isValid()) return;

        Player player = commandBuffer.getComponent(sourceRef, Player.getComponentType());
        if (player == null) return;

        // Get the player's held item
        if (player.getInventory() == null) return;
        ItemStack heldItem = player.getInventory().getItemInHand();
        if (heldItem == null) return;

        // Only process MELEE weapons
        WeaponDefinition definition = WeaponDefinitions.getById(heldItem.getItemId());
        if (definition == null || definition.getCategory() != WeaponCategory.MELEE) return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (!targetRef.isValid()) return;

        // Don't apply effects to self
        if (targetRef.equals(sourceRef)) return;

        // ── Scale damage by WEAPON_DAMAGE modifier + Berserker buff ──
        double dmgBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.WEAPON_DAMAGE);
        float berserkerMul = ArmorAbilityBuffSystem.getDamageMultiplier(sourceRef);
        float totalMul = (float) (1.0 + dmgBonus) * berserkerMul;
        if (totalMul != 1.0f) {
            damage.setAmount(damage.getAmount() * totalMul);
        }

        // ── Apply DoT effect with rarity-scaled duration ──
        // Skip DoT if target has Purification buff active
        DamageEffect effect = GunItemMetadata.getEffect(heldItem);
        if (effect != DamageEffect.NONE && effect.hasDoT()
                && !ArmorAbilityBuffSystem.isPurificationActive(targetRef)) {
            WeaponRarity rarity = GunItemMetadata.getRarity(heldItem);
            DamageEffectRuntime.apply(commandBuffer, targetRef, effect, rarity);

            // For freezing effects, strip upward knockback so entities don't float
            if (effect == DamageEffect.ICE || effect == DamageEffect.ELECTRICITY) {
                KnockbackComponent kb = damage.getIfPresentMetaObject(Damage.KNOCKBACK_COMPONENT);
                if (kb != null && kb.getVelocity() != null && kb.getVelocity().y > 0.0) {
                    kb.getVelocity().y = 0.0;
                }
            }
        }
    }
}
