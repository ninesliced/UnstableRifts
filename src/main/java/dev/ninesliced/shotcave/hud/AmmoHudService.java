package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponCategory;
import dev.ninesliced.shotcave.guns.WeaponDefinition;
import dev.ninesliced.shotcave.guns.WeaponDefinitions;
import dev.ninesliced.shotcave.guns.WeaponModifier;
import dev.ninesliced.shotcave.guns.WeaponModifierType;
import dev.ninesliced.shotcave.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized weapon info HUD update/hide behavior.
 */
public final class AmmoHudService {

    private static final String HUD_IDENTIFIER = "Shotcave_Ammo";
    private static final long STATE_HIDDEN = 0L;
    private static final ConcurrentHashMap<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();

    private AmmoHudService() {
    }

    public static void updateForHeldItem(@Nonnull Player player, @Nonnull PlayerRef playerRef,
            @Nullable ItemStack heldItem) {

        if (heldItem == null) {
            hide(player, playerRef);
            return;
        }

        String itemId = heldItem.getItemId();

        // Look up weapon definition for base stats
        WeaponDefinition definition = itemId != null ? WeaponDefinitions.getById(itemId) : null;

        int baseMaxAmmo = definition != null && definition.getBaseMaxAmmo() > 0
                ? definition.getBaseMaxAmmo()
                : GunItemMetadata.getBaseMaxAmmo(heldItem, -1);
        if (baseMaxAmmo <= 0) {
            hide(player, playerRef);
            return;
        }

        int maxAmmo = GunItemMetadata.getEffectiveMaxAmmo(heldItem, baseMaxAmmo);

        int ammo = GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, maxAmmo);
        ammo = Math.max(0, Math.min(ammo, maxAmmo));

        // Read weapon attributes from BSON
        WeaponRarity rarity = GunItemMetadata.getRarity(heldItem);
        DamageEffect effect = GunItemMetadata.getEffect(heldItem);
        List<WeaponModifier> modifiers = GunItemMetadata.getModifiers(heldItem);
        WeaponCategory category = definition != null ? definition.getCategory() : WeaponCategory.LASER;

        long state = computeState(itemId, ammo, baseMaxAmmo, maxAmmo, rarity.ordinal(), effect.ordinal(), modifiers.hashCode());
        UUID uuid = playerRef.getUuid();
        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == state) {
            return;
        }
        LAST_STATE.put(uuid, state);

        String displayName = definition != null ? definition.getDisplayName() : extractWeaponName(heldItem);
        ShotcaveHud hud = new ShotcaveHud(playerRef, ammo, baseMaxAmmo, maxAmmo,
                rarity, effect, category, definition, modifiers, displayName);

        player.getHudManager().showHudComponents(playerRef, HudComponent.AmmoIndicator);
        if (!MultiHudCompat.setHud(player, playerRef, HUD_IDENTIFIER, hud)) {
            player.getHudManager().setCustomHud(playerRef, hud);
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
    }

    private static long computeState(@Nullable String itemId, int ammo, int baseMaxAmmo, int maxAmmo,
                                      int rarityOrdinal, int effectOrdinal, int modifiersHash) {
        long h = 1125899906842597L;
        h = 31L * h + (itemId == null ? 0 : itemId.hashCode());
        h = 31L * h + ammo;
        h = 31L * h + baseMaxAmmo;
        h = 31L * h + maxAmmo;
        h = 31L * h + rarityOrdinal;
        h = 31L * h + effectOrdinal;
        h = 31L * h + modifiersHash;
        return h;
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
