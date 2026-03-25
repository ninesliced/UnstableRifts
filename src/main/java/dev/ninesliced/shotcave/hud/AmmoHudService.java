package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.armor.ArmorChargeComponent;
import dev.ninesliced.shotcave.armor.ArmorDefinition;
import dev.ninesliced.shotcave.armor.ArmorDefinitions;
import dev.ninesliced.shotcave.armor.ArmorItemMetadata;
import dev.ninesliced.shotcave.armor.ArmorModifier;
import dev.ninesliced.shotcave.armor.ArmorSetAbility;
import dev.ninesliced.shotcave.armor.ArmorSetTracker;
import dev.ninesliced.shotcave.armor.ArmorStatResolver;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponCategory;
import dev.ninesliced.shotcave.guns.WeaponDefinition;
import dev.ninesliced.shotcave.guns.WeaponDefinitions;
import dev.ninesliced.shotcave.guns.WeaponModifier;
import dev.ninesliced.shotcave.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized weapon info HUD update/hide behavior.
 */
public final class AmmoHudService {

    private static final String HUD_IDENTIFIER = "Shotcave_Ammo";
    private static final String ARMOR_HUD_IDENTIFIER = "Shotcave_Armor";
    private static final long STATE_HIDDEN = 0L;
    private static final ConcurrentHashMap<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_ARMOR_STATE = new ConcurrentHashMap<>();

    private AmmoHudService() {
    }

    public static void updateForHeldItem(@Nonnull Player player, @Nonnull PlayerRef playerRef,
            @Nullable ItemStack heldItem) {
        updateForHeldItem(player, playerRef, heldItem, false, null);
    }

    public static void updateForHeldItem(@Nonnull Player player, @Nonnull PlayerRef playerRef,
            @Nullable ItemStack heldItem, boolean crouching) {
        updateForHeldItem(player, playerRef, heldItem, crouching, null);
    }

    public static void updateForHeldItem(@Nonnull Player player, @Nonnull PlayerRef playerRef,
            @Nullable ItemStack heldItem, boolean crouching,
            @Nullable Ref<EntityStore> ref) {

        boolean isWeapon = false;

        if (heldItem != null) {
            String itemId = heldItem.getItemId();
            WeaponDefinition definition = itemId != null ? WeaponDefinitions.getById(itemId) : null;
            double armorAmmoCapacityBonus = getEquippedArmorAmmoCapacityBonus(ref);

            WeaponCategory category = definition != null ? definition.getCategory() : null;
            boolean isMelee = category == WeaponCategory.MELEE;

            int baseMaxAmmo = definition != null && definition.getBaseMaxAmmo() > 0
                    ? definition.getBaseMaxAmmo()
                    : GunItemMetadata.getBaseMaxAmmo(heldItem, -1);

            if (baseMaxAmmo > 0 || isMelee) {
                isWeapon = true;
                int maxAmmo = isMelee ? 0 : GunItemMetadata.getEffectiveMaxAmmo(heldItem, baseMaxAmmo, armorAmmoCapacityBonus);
                int ammo = isMelee ? 0 : GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, maxAmmo);
                ammo = Math.max(0, Math.min(ammo, maxAmmo));

                WeaponRarity rarity = GunItemMetadata.getRarity(heldItem);
                DamageEffect effect = GunItemMetadata.getEffect(heldItem);
                List<WeaponModifier> modifiers = GunItemMetadata.getModifiers(heldItem);
                if (category == null) category = WeaponCategory.LASER;

                long state = computeState(itemId, ammo, baseMaxAmmo, maxAmmo, rarity.ordinal(), effect.ordinal(), modifiers.hashCode(), crouching);

                UUID uuid = playerRef.getUuid();
                Long previous = LAST_STATE.get(uuid);
                if (previous == null || previous != state) {
                    LAST_STATE.put(uuid, state);

                    String displayName = definition != null ? definition.getDisplayName() : extractWeaponName(heldItem);
                    ShotcaveHud hud = new ShotcaveHud(playerRef, ammo, baseMaxAmmo, maxAmmo,
                            rarity, effect, category, definition, modifiers, displayName, crouching);

                    player.getHudManager().showHudComponents(playerRef, HudComponent.AmmoIndicator);
                    if (!MultiHudCompat.setHud(player, playerRef, HUD_IDENTIFIER, hud)) {
                        player.getHudManager().setCustomHud(playerRef, hud);
                    }
                }
                // Weapon is shown — also update armor HUD (always visible)
                if (ref != null) {
                    updateArmorHud(player, playerRef, ref, crouching);
                }
                return;
            }
        }

        // Not holding a weapon — hide weapon HUD
        hide(player, playerRef);

        // Always update armor HUD regardless of weapon/crouch
        if (ref != null) {
            updateArmorHud(player, playerRef, ref, crouching);
        } else {
            hideArmorHud(player, playerRef);
        }
    }

    public static void hide(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == STATE_HIDDEN) {
            return;
        }
        LAST_STATE.put(uuid, STATE_HIDDEN);

        player.getHudManager().hideHudComponents(playerRef, HudComponent.AmmoIndicator);
        if (!MultiHudCompat.hideHud(player, playerRef, HUD_IDENTIFIER)) {
            player.getHudManager().setCustomHud(playerRef, null);
        }
    }

    public static void clear(@Nonnull PlayerRef playerRef) {
        LAST_STATE.remove(playerRef.getUuid());
        LAST_ARMOR_STATE.remove(playerRef.getUuid());
    }

    // ── Armor HUD ──────────────────────────────────────────────────────

    private static void updateArmorHud(@Nonnull Player player, @Nonnull PlayerRef playerRef,
                                        @Nonnull Ref<EntityStore> ref, boolean crouching) {
        InventoryComponent.Armor armorComp = ref.getStore().getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp == null) {
            hideArmorHud(player, playerRef);
            return;
        }

        ItemContainer armorInv = armorComp.getInventory();

        // ── Scan all 4 slots directly — compute set counts & aggregate stats ──
        Map<String, Integer> setCounts = new java.util.HashMap<>();
        float totalProtection = 0, totalCloseDef = 0, totalFarDef = 0;
        float totalKnockback = 0, totalSpikeDmg = 0, totalHP = 0, totalSpeed = 0;
        List<ArmorModifier> allModifiers = new java.util.ArrayList<>();
        WeaponRarity bestRarity = WeaponRarity.BASIC;
        int equippedCount = 0;

        for (int slot = 0; slot < 4; slot++) {
            ItemStack stack = armorInv.getItemStack((short) slot);
            if (ItemStack.isEmpty(stack)) continue;
            ArmorDefinition def = ArmorDefinitions.getById(stack.getItemId());
            if (def == null) continue;

            equippedCount++;
            String setId = ArmorItemMetadata.getSetId(stack);
            if (setId != null) {
                setCounts.merge(setId, 1, Integer::sum);
            }

            // Aggregate base stats
            totalProtection += def.getBaseProtection();
            totalCloseDef += def.getBaseCloseDmgReduce();
            totalFarDef += def.getBaseFarDmgReduce();
            totalKnockback += def.getBaseKnockback();
            totalSpikeDmg += def.getBaseSpikeDamage();
            totalHP += def.getBaseLifeBoost();
            totalSpeed += def.getBaseSpeedBoost();

            // Collect modifiers
            allModifiers.addAll(ArmorItemMetadata.getModifiers(stack));

            // Track highest rarity for accent
            WeaponRarity r = ArmorItemMetadata.getRarity(stack);
            if (r.ordinal() > bestRarity.ordinal()) {
                bestRarity = r;
            }
        }

        if (equippedCount == 0) {
            hideArmorHud(player, playerRef);
            return;
        }

        // Keep tracker in sync for ability activation system
        Shotcave shotcave = Shotcave.getInstance();
        ArmorSetTracker tracker = shotcave != null ? shotcave.getArmorSetTracker() : null;
        if (tracker != null) {
            tracker.onArmorChanged(playerRef.getUuid(), armorInv);
        }

        // Find best (largest) set
        String bestSetId = null;
        int bestSetCount = 0;
        for (Map.Entry<String, Integer> entry : setCounts.entrySet()) {
            if (entry.getValue() > bestSetCount) {
                bestSetId = entry.getKey();
                bestSetCount = entry.getValue();
            }
        }

        int setTotalPieces = 4;
        ArmorSetAbility ability = ArmorSetAbility.NONE;
        if (bestSetId != null) {
            List<ArmorDefinition> setDefs = ArmorDefinitions.getBySetId(bestSetId);
            if (!setDefs.isEmpty()) {
                setTotalPieces = setDefs.size();
                ability = setDefs.get(0).getSetAbility();
            }
        }

        // Read charge state
        float chargeProgress = 0f;
        boolean abilityActive = false;
        int chargeProgressPct = 0;
        ArmorChargeComponent charge = ref.getStore().getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge != null) {
            chargeProgress = charge.getChargeProgress();
            abilityActive = charge.hasActiveBuff();
            chargeProgressPct = charge.isReady() ? 100 : (int) (chargeProgress * 100.0f);
        }

        // State hash — includes crouching so expanded/compact triggers update
        long state = computeArmorState(bestSetId, bestSetCount, bestRarity.ordinal(),
                allModifiers.hashCode(), crouching ? 1 : 0,
                chargeProgressPct, abilityActive ? 1 : 0);

        UUID uuid = playerRef.getUuid();
        Long previous = LAST_ARMOR_STATE.get(uuid);
        if (previous != null && previous == state) {
            return;
        }
        LAST_ARMOR_STATE.put(uuid, state);

        ArmorHud hud = new ArmorHud(playerRef,
                bestSetId, bestSetCount, setTotalPieces,
                bestRarity,
                totalProtection, totalCloseDef, totalFarDef,
                totalKnockback, totalSpikeDmg, totalHP, totalSpeed,
                ability, chargeProgress, abilityActive,
                crouching,
                allModifiers);

        player.getHudManager().showHudComponents(playerRef, HudComponent.AmmoIndicator);
        if (!MultiHudCompat.setHud(player, playerRef, ARMOR_HUD_IDENTIFIER, hud)) {
            player.getHudManager().setCustomHud(playerRef, hud);
        }
    }

    private static void hideArmorHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        Long previous = LAST_ARMOR_STATE.get(uuid);
        if (previous != null && previous == STATE_HIDDEN) {
            return;
        }
        LAST_ARMOR_STATE.put(uuid, STATE_HIDDEN);

        if (!MultiHudCompat.hideHud(player, playerRef, ARMOR_HUD_IDENTIFIER)) {
            // Only clear custom hud if weapon hud is also hidden
            Long weaponState = LAST_STATE.get(uuid);
            if (weaponState != null && weaponState == STATE_HIDDEN) {
                player.getHudManager().hideHudComponents(playerRef, HudComponent.AmmoIndicator);
            }
        }
    }

    private static long computeArmorState(@Nullable String setId, int bestSetCount, int rarityOrdinal,
                                           int modifiersHash, int crouching,
                                           int chargeProgressPct, int abilityActive) {
        long h = 1125899906842597L;
        h = 31L * h + (setId == null ? 0 : setId.hashCode());
        h = 31L * h + bestSetCount;
        h = 31L * h + rarityOrdinal;
        h = 31L * h + modifiersHash;
        h = 31L * h + crouching;
        h = 31L * h + chargeProgressPct;
        h = 31L * h + abilityActive;
        h = 31L * h + 7; // distinguish from weapon state
        return h;
    }

    private static long computeState(@Nullable String itemId, int ammo, int baseMaxAmmo, int maxAmmo,
                                      int rarityOrdinal, int effectOrdinal, int modifiersHash, boolean crouching) {
        long h = 1125899906842597L;
        h = 31L * h + (itemId == null ? 0 : itemId.hashCode());
        h = 31L * h + ammo;
        h = 31L * h + baseMaxAmmo;
        h = 31L * h + maxAmmo;
        h = 31L * h + rarityOrdinal;
        h = 31L * h + effectOrdinal;
        h = 31L * h + modifiersHash;
        h = 31L * h + (crouching ? 1 : 0);
        return h;
    }

    private static double getEquippedArmorAmmoCapacityBonus(@Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return 0.0;
        }

        InventoryComponent.Armor armorComponent = ref.getStore().getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComponent == null) {
            return 0.0;
        }

        return ArmorStatResolver.getTotalAmmoCapacityBonus(armorComponent.getInventory());
    }

    @Nullable
    private static String extractWeaponName(@Nonnull ItemStack item) {
        String id = item.getItemId();
        if (id == null || id.isBlank()) {
            return null;
        }
        String name = id;
        if (name.startsWith("Weapon_")) {
            name = name.substring(7);
        }
        if (name.endsWith("_Shotcave")) {
            name = name.substring(0, name.length() - 9);
        }
        return name.replace('_', ' ').toUpperCase();
    }
}
