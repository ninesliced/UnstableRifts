package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.ShotcaveLog;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponRarity;

/**
 * Intercepts item entity creation/removal to apply custom pickup behavior
 * (F-key or score-collect) based on item asset tags, and registers them
 * in {@link ItemPickupTracker}.
 */
public final class ItemDropSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = ShotcaveLog.forModule("Pickup");

    public ItemDropSystem() {
    }

    @Override
    public void onEntityAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        ItemComponent itemComponent = store.getComponent(ref, ItemComponent.getComponentType());
        if (itemComponent == null) {
            return;
        }

        ItemStack itemStack = itemComponent.getItemStack();
        if (ItemStack.isEmpty(itemStack)) {
            return;
        }

        String itemId = itemStack.getItemId();
        if (itemId == null) {
            return;
        }

        LOGGER.at(Level.INFO).log("[ItemDrop] onEntityAdded itemId='%s'", itemId);

        String displayName = resolveDisplayName(itemId);
        String iconPath = resolveIconPath(itemId);

        boolean isFKey = ItemPickupConfig.isFKeyPickup(itemId);
        boolean isScoreCollect = ItemPickupConfig.isScoreCollect(itemId);

        LOGGER.at(Level.INFO).log("[ItemDrop]   isFKey=%s isScoreCollect=%s", isFKey, isScoreCollect);

        if (!isFKey && !isScoreCollect) {
            LOGGER.at(Level.INFO).log("[ItemDrop]   not tracked, skipping");
            return;
        }

        LOGGER.at(Level.INFO).log("[ItemDrop]   TRACKING item, trackerSize=%d", ItemPickupTracker.size() + 1);
        ItemPickupTracker.track(new ItemPickupTracker.TrackedItem(
                ref, itemId, displayName, iconPath, isFKey, isScoreCollect));

        if (isFKey) {
            applyFKeyPickup(ref, commandBuffer);
        }

        if (isScoreCollect) {
            applyScoreCollectPickupPrevention(ref, commandBuffer);
        }

        // Apply rarity glow effect for weapons with BSON rarity metadata
        applyRarityGlow(ref, store, commandBuffer, itemStack);
    }

    @Override
    public void onEntityRemove(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        ItemPickupTracker.untrack(ref);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return ItemComponent.getComponentType();
    }

    /**
     * Applies PreventPickup to F-key items. Pickup is handled exclusively
     * by {@link FKeyPickupPacketHandler} — no per-entity Use mapping is
     * added to avoid collecting all overlapping items at once.
     */
    private static void applyFKeyPickup(@Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        commandBuffer.ensureComponent(ref, PreventPickup.getComponentType());
    }

    private static void applyScoreCollectPickupPrevention(@Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        commandBuffer.ensureComponent(ref, PreventPickup.getComponentType());
    }

    /**
     * If the dropped item has Shotcave rarity metadata, applies the matching
     * glow entity effect (Drop_Rare, Drop_Epic, Drop_Legendary).
     */
    private static void applyRarityGlow(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                         @Nonnull ItemStack itemStack) {
        if (!GunItemMetadata.hasInt(itemStack, GunItemMetadata.RARITY_KEY)) {
            return;
        }
        WeaponRarity rarity = GunItemMetadata.getRarity(itemStack);
        String glowEffectId = rarity.getGlowEffectId();
        if (glowEffectId == null) {
            return;
        }
        try {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(glowEffectId);
            if (effect == null) {
                LOGGER.at(Level.WARNING).log("[ItemDrop]   Glow effect '%s' not found in asset map", glowEffectId);
                return;
            }
            // ensureAndGetComponent creates the component synchronously and returns it,
            // unlike ensureComponent which queues async (store.getComponent returns null)
            EffectControllerComponent effectController =
                    commandBuffer.ensureAndGetComponent(ref, EffectControllerComponent.getComponentType());
            if (effectController != null) {
                effectController.addInfiniteEffect(ref,
                        EntityEffect.getAssetMap().getIndex(glowEffectId),
                        effect, commandBuffer);
                LOGGER.at(Level.INFO).log("[ItemDrop]   Applied glow '%s' for rarity %s", glowEffectId, rarity.name());
            }
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("[ItemDrop]   Failed to apply glow: %s", e.getMessage());
        }
    }

    /**
     * Resolves a display name from the asset registry, falling back to
     * a prettified version of the raw item ID.
     */
    @Nonnull
    private static String resolveDisplayName(@Nonnull String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null) {
                String key = item.getTranslationKey();
                if (key != null && !key.isBlank()) {
                    return key;
                }
            }
        } catch (Exception ignored) {
        }

        String name = itemId;
        int lastUnderscore = name.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < name.length() - 1) {
            if (name.startsWith("Weapon_") || name.startsWith("Ingredient_")
                    || name.startsWith("Plant_") || name.startsWith("Shotcave_Props_")) {
                name = name.substring(name.indexOf('_') + 1);
            }
        }
        return name.replace('_', ' ');
    }

    @Nullable
    private static String resolveIconPath(@Nonnull String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null) {
                String icon = item.getIcon();
                if (icon != null && !icon.isBlank()) {
                    return icon;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
