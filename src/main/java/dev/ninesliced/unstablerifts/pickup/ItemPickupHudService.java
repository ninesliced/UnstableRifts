package dev.ninesliced.unstablerifts.pickup;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.armor.ArmorDefinition;
import dev.ninesliced.unstablerifts.armor.ArmorDefinitions;
import dev.ninesliced.unstablerifts.armor.ArmorItemMetadata;
import dev.ninesliced.unstablerifts.armor.ArmorModifier;
import dev.ninesliced.unstablerifts.guns.*;
import dev.ninesliced.unstablerifts.hud.MultiHudCompat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Show/hide logic for the item pickup HUD with per-player state deduplication.
 */
public final class ItemPickupHudService {

    private static final String HUD_IDENTIFIER = "UnstableRifts_ItemPickup";
    private static final long STATE_HIDDEN = 0L;

    private static final ConcurrentHashMap<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();

    private ItemPickupHudService() {
    }

    public static void show(@Nonnull Player player,
                            @Nonnull PlayerRef playerRef,
                            @Nonnull String itemName,
                            @Nullable String itemIconPath,
                            int itemQuantity,
                            boolean crouching,
                            @Nullable ItemStack itemStack) {

        // Read weapon metadata from the ItemStack
        boolean isWeapon = false;
        boolean isArmor = false;
        WeaponRarity rarity = WeaponRarity.BASIC;
        DamageEffect effect = DamageEffect.NONE;
        WeaponCategory category = WeaponCategory.LASER;
        WeaponDefinition definition = null;
        ArmorDefinition armorDefinition = null;
        List<WeaponModifier> modifiers = Collections.emptyList();
        List<ArmorModifier> armorModifiers = Collections.emptyList();
        int baseMaxAmmo = 0;
        int maxAmmo = 0;

        if (itemStack != null) {
            String itemId = itemStack.getItemId();
            if (itemId != null) {
                definition = WeaponDefinitions.getById(itemId);
                if (definition == null) {
                    armorDefinition = ArmorDefinitions.getById(itemId);
                }
            }
            if (definition != null) {
                isWeapon = true;
                rarity = GunItemMetadata.getRarity(itemStack);
                effect = GunItemMetadata.getEffect(itemStack);
                category = definition.category();
                modifiers = GunItemMetadata.getModifiers(itemStack);
                baseMaxAmmo = definition.baseMaxAmmo() > 0
                        ? definition.baseMaxAmmo()
                        : GunItemMetadata.getBaseMaxAmmo(itemStack, -1);
                maxAmmo = GunItemMetadata.getEffectiveMaxAmmo(itemStack, baseMaxAmmo);
                if (definition.displayName() != null && !definition.displayName().isBlank()) {
                    itemName = definition.displayName();
                }
            } else if (armorDefinition != null) {
                isArmor = true;
                rarity = ArmorItemMetadata.getRarity(itemStack);
                armorModifiers = ArmorItemMetadata.getModifiers(itemStack);
                if (armorDefinition.displayName() != null && !armorDefinition.displayName().isBlank()) {
                    itemName = armorDefinition.displayName();
                }
            }
        }

        long state = computeState(itemName, itemIconPath, itemQuantity, crouching,
                rarity.ordinal(), effect.ordinal(), baseMaxAmmo, maxAmmo,
                isWeapon ? modifiers.hashCode() : armorModifiers.hashCode());
        UUID uuid = playerRef.getUuid();

        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == state) {
            return;
        }
        LAST_STATE.put(uuid, state);

        ItemPickupHud hud = new ItemPickupHud(playerRef, itemName, itemIconPath, itemQuantity,
                crouching, rarity, effect, category, definition, modifiers, isWeapon, baseMaxAmmo, maxAmmo,
                isArmor, armorDefinition, armorModifiers);

        if (!MultiHudCompat.setHud(player, playerRef, HUD_IDENTIFIER, hud)) {
            player.getHudManager().setCustomHud(playerRef, hud);
        }
    }

    public static void hide(@Nonnull Player player,
                            @Nonnull PlayerRef playerRef) {

        UUID uuid = playerRef.getUuid();

        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == STATE_HIDDEN) {
            return;
        }
        LAST_STATE.put(uuid, STATE_HIDDEN);

        if (!MultiHudCompat.hideHud(player, playerRef, HUD_IDENTIFIER)) {
            player.getHudManager().setCustomHud(playerRef, null);
        }
    }

    public static boolean isActive(@Nonnull UUID playerUuid) {
        Long state = LAST_STATE.get(playerUuid);
        return state != null && state != STATE_HIDDEN;
    }

    public static void clear(@Nonnull PlayerRef playerRef) {
        LAST_STATE.remove(playerRef.getUuid());
    }

    public static void clearAll() {
        LAST_STATE.clear();
    }

    private static long computeState(@Nullable String itemName,
                                     @Nullable String itemIconPath,
                                     int itemQuantity,
                                     boolean crouching,
                                     int rarityOrdinal,
                                     int effectOrdinal,
                                     int baseMaxAmmo,
                                     int maxAmmo,
                                     int modifiersHash) {
        long h = 7919L;
        h = 31L * h + (itemName == null ? 0 : itemName.hashCode());
        h = 31L * h + (itemIconPath == null ? 0 : itemIconPath.hashCode());
        h = 31L * h + itemQuantity;
        h = 31L * h + (crouching ? 1 : 0);
        h = 31L * h + rarityOrdinal;
        h = 31L * h + effectOrdinal;
        h = 31L * h + baseMaxAmmo;
        h = 31L * h + maxAmmo;
        h = 31L * h + modifiersHash;
        return h;
    }
}
