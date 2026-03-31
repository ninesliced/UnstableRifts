package dev.ninesliced.unstablerifts.pickup;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.armor.ArmorDefinition;
import dev.ninesliced.unstablerifts.armor.ArmorDefinitions;
import dev.ninesliced.unstablerifts.guns.WeaponDefinition;
import dev.ninesliced.unstablerifts.guns.WeaponDefinitions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Intercepts item entity creation/removal to apply custom pickup behavior
 * (F-key or score-collect) based on item asset tags, and registers them
 * in {@link ItemPickupTracker}.
 */
public final class ItemDropSystem extends RefSystem<EntityStore> {

    public ItemDropSystem() {
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
     * Resolves a display name from the asset registry, falling back to
     * a prettified version of the raw item ID.
     */
    @Nonnull
    private static String resolveDisplayName(@Nonnull String itemId) {
        WeaponDefinition def = WeaponDefinitions.getById(itemId);
        if (def != null) {
            return def.displayName();
        }
        ArmorDefinition armorDef = ArmorDefinitions.getById(itemId);
        if (armorDef != null) {
            return armorDef.displayName();
        }

        String name = itemId;
        int lastUnderscore = name.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < name.length() - 1) {
            if (name.startsWith("Weapon_") || name.startsWith("Ingredient_")
                    || name.startsWith("Plant_") || name.startsWith("UnstableRifts_Props_")) {
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

        String displayName = resolveDisplayName(itemId);
        String iconPath = resolveIconPath(itemId);

        boolean isFKey = ItemPickupConfig.isFKeyPickup(itemId);
        boolean isScoreCollect = ItemPickupConfig.isScoreCollect(itemId);

        if (!isFKey && !isScoreCollect) {
            return;
        }

        ItemPickupTracker.track(new ItemPickupTracker.TrackedItem(
                ref, itemId, displayName, iconPath, isFKey, isScoreCollect));

        if (isFKey) {
            applyFKeyPickup(ref, commandBuffer);
        }

        if (isScoreCollect) {
            applyScoreCollectPickupPrevention(ref, commandBuffer);
        }
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
}
