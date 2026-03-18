package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.WeaponCategory;
import dev.ninesliced.shotcave.guns.WeaponDefinition;
import dev.ninesliced.shotcave.guns.WeaponModifier;
import dev.ninesliced.shotcave.guns.WeaponModifierType;
import dev.ninesliced.shotcave.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * HUD overlay shown near F-key-protected items. Displays weapon name with rarity
 * color, effect label, F-key collect prompt, "crouch for details" hint, and an
 * expandable stats panel when crouching.
 */
public final class ItemPickupHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/Shotcave/CratePickupHud.ui";
    public static final String HUD_ID = "ItemPickup";

    private final String itemDisplayName;
    private final String itemIconPath;
    private final int itemQuantity;
    private final boolean crouching;

    // Weapon metadata (nullable for non-weapon items)
    @Nonnull private final WeaponRarity rarity;
    @Nonnull private final DamageEffect effect;
    @Nonnull private final WeaponCategory category;
    @Nullable private final WeaponDefinition definition;
    @Nonnull private final List<WeaponModifier> modifiers;
    private final boolean isWeapon;
    private final int baseMaxAmmo;
    private final int maxAmmo;

    public ItemPickupHud(@Nonnull PlayerRef playerRef,
            @Nonnull String itemDisplayName,
            @Nullable String itemIconPath,
            int itemQuantity,
            boolean crouching,
            @Nonnull WeaponRarity rarity,
            @Nonnull DamageEffect effect,
            @Nonnull WeaponCategory category,
            @Nullable WeaponDefinition definition,
            @Nonnull List<WeaponModifier> modifiers,
            boolean isWeapon,
            int baseMaxAmmo,
            int maxAmmo) {
        super(playerRef);
        this.itemDisplayName = itemDisplayName;
        this.itemIconPath = itemIconPath;
        this.itemQuantity = Math.max(0, itemQuantity);
        this.crouching = crouching;
        this.rarity = rarity;
        this.effect = effect;
        this.category = category;
        this.definition = definition;
        this.modifiers = modifiers;
        this.isWeapon = isWeapon;
        this.baseMaxAmmo = Math.max(0, baseMaxAmmo);
        this.maxAmmo = maxAmmo;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);

        if (isWeapon && crouching) {
            // ── EXPANDED view (details visible, higher position) ──
            ui.set("#CratePickupCompactWrapper.Visible", false);
            ui.set("#CratePickupExpandedWrapper.Visible", true);

            if (isWeapon) {
                ui.set("#CratePickupAccentExpanded.Background", rarity.getColorHex());
            }

            if (this.itemIconPath != null && !this.itemIconPath.isBlank()) {
                ui.set("#CratePickupIconExpanded.AssetPath", this.itemIconPath);
                ui.set("#CratePickupIconExpanded.Visible", true);
            }

            String title = buildWeaponTitle();
            ui.set("#CratePickupItemNameExpanded.TextSpans", Message.raw(title));
            ui.set("#CratePickupItemNameExpanded.Style.TextColor", rarity.getColorHex());

            if (effect != DamageEffect.NONE) {
                ui.set("#CratePickupEffectLabelExpanded.TextSpans", Message.raw(effect.getDisplayName()));
                ui.set("#CratePickupEffectLabelExpanded.Style.TextColor", effectColorHex(effect));
                ui.set("#CratePickupEffectLabelExpanded.Visible", true);
            }

            ui.set("#CratePickupKeyHintExpanded.Visible", true);
            ui.set("#CratePickupCollectLabelExpanded.TextSpans", Message.raw("Collect"));
            ui.set("#CratePickupCollectLabelExpanded.Visible", true);

            buildDetailsPanel(ui);
        } else {
            // ── COMPACT view (no details, lower position) ──
            ui.set("#CratePickupExpandedWrapper.Visible", false);
            ui.set("#CratePickupCompactWrapper.Visible", true);

            if (isWeapon) {
                ui.set("#CratePickupAccentCompact.Background", rarity.getColorHex());
            }

            if (this.itemIconPath != null && !this.itemIconPath.isBlank()) {
                ui.set("#CratePickupIconCompact.AssetPath", this.itemIconPath);
                ui.set("#CratePickupIconCompact.Visible", true);
            }

            if (isWeapon) {
                String title = buildWeaponTitle();
                ui.set("#CratePickupItemNameCompact.TextSpans", Message.raw(title));
                ui.set("#CratePickupItemNameCompact.Style.TextColor", rarity.getColorHex());
            } else {
                ui.set("#CratePickupItemNameCompact.TextSpans", Message.raw(this.itemDisplayName));
            }

            if (this.itemQuantity > 1) {
                ui.set("#CratePickupQuantityCompact.TextSpans", Message.raw("x" + this.itemQuantity));
                ui.set("#CratePickupQuantityCompact.Visible", true);
            }

            if (isWeapon && effect != DamageEffect.NONE) {
                ui.set("#CratePickupEffectLabelCompact.TextSpans", Message.raw(effect.getDisplayName()));
                ui.set("#CratePickupEffectLabelCompact.Style.TextColor", effectColorHex(effect));
                ui.set("#CratePickupEffectLabelCompact.Visible", true);
            }

            ui.set("#CratePickupKeyHintCompact.Visible", true);
            ui.set("#CratePickupCollectLabelCompact.TextSpans", Message.raw("Collect"));
            ui.set("#CratePickupCollectLabelCompact.Visible", true);

            if (isWeapon) {
                ui.set("#CratePickupCrouchHint.TextSpans", Message.raw("Crouch for details"));
                ui.set("#CratePickupCrouchHint.Visible", true);
            }
        }
    }

    private void buildDetailsPanel(@Nonnull UICommandBuilder ui) {
        if (category == WeaponCategory.SUMMONING) {
            buildStatRow(ui, 0, "Mob HP",
                    definition != null ? definition.getBaseMobHealth() : 0,
                    getModBonus(WeaponModifierType.MOB_HEALTH), true);
            buildStatRow(ui, 1, "Mob Dmg",
                    definition != null ? definition.getBaseMobDamage() : 0,
                    getModBonus(WeaponModifierType.MOB_DAMAGE), true);
            buildStatRow(ui, 2, "Mob Life",
                    definition != null ? definition.getBaseMobLifetime() : 0,
                    getModBonus(WeaponModifierType.MOB_LIFETIME), true);
            hideStatRow(ui, 3);
            hideStatRow(ui, 4);
            {
                int maxAmmoBonus = this.maxAmmo - this.baseMaxAmmo;
                String prefix = "#CratePickupStat";
                ui.set(prefix + "Label5.TextSpans", Message.raw("Max Ammo"));
                ui.set(prefix + "Base5.TextSpans", Message.raw(Integer.toString(this.baseMaxAmmo)));
                if (maxAmmoBonus > 0) {
                    ui.set(prefix + "Mod5.TextSpans", Message.raw("(+" + maxAmmoBonus + ")"));
                } else {
                    ui.set(prefix + "Mod5.TextSpans", Message.raw(""));
                }
            }
            hideStatRow(ui, 6);
        } else {
            buildStatRow(ui, 0, "Damage",
                    definition != null ? definition.getBaseDamage() : 0,
                    getModBonus(WeaponModifierType.WEAPON_DAMAGE), true);
            buildStatRow(ui, 1, "Speed",
                    definition != null ? definition.getBaseCooldown() : 0,
                    getModBonus(WeaponModifierType.ATTACK_SPEED), true);
            buildStatRow(ui, 2, "Range",
                    definition != null ? definition.getBaseRange() : 0,
                    getModBonus(WeaponModifierType.MAX_RANGE), true);

            // Precision
            double baseSpread = definition != null ? definition.getBaseSpread() : 0;
            double basePrecision = Math.max(0, 100.0 - baseSpread * 10.0);
            double precisionBonus = getModBonus(WeaponModifierType.PRECISION);
            String prefix = "#CratePickupStat";
            ui.set(prefix + "Label3.TextSpans", Message.raw("Precision"));
            ui.set(prefix + "Base3.TextSpans", Message.raw(String.format("%.0f%%", basePrecision)));
            if (precisionBonus > 0.001) {
                int bonusPoints = (int) Math.round(baseSpread * precisionBonus * 10.0);
                if (bonusPoints > 0) {
                    ui.set(prefix + "Mod3.TextSpans", Message.raw("(+" + bonusPoints + ")"));
                } else {
                    ui.set(prefix + "Mod3.TextSpans", Message.raw(""));
                }
            } else {
                ui.set(prefix + "Mod3.TextSpans", Message.raw(""));
            }

            buildStatRow(ui, 4, "Knockback",
                    definition != null ? definition.getBaseKnockback() : 0,
                    getModBonus(WeaponModifierType.KNOCKBACK), true);
            {
                int maxAmmoBonus = this.maxAmmo - this.baseMaxAmmo;
                ui.set(prefix + "Label5.TextSpans", Message.raw("Max Ammo"));
                ui.set(prefix + "Base5.TextSpans", Message.raw(Integer.toString(this.baseMaxAmmo)));
                if (maxAmmoBonus > 0) {
                    ui.set(prefix + "Mod5.TextSpans", Message.raw("(+" + maxAmmoBonus + ")"));
                } else {
                    ui.set(prefix + "Mod5.TextSpans", Message.raw(""));
                }
            }

            // Only show Pellets for multi-pellet weapons (blunderbusses)
            int basePellets = definition != null ? definition.getBasePellets() : 1;
            if (basePellets > 1) {
                buildStatRow(ui, 6, "Pellets", basePellets,
                        getModBonus(WeaponModifierType.ADDITIONAL_BULLETS), false);
            } else {
                hideStatRow(ui, 6);
            }
        }

        // Modifiers summary with correct display (flat vs percentage)
        if (!modifiers.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < modifiers.size(); i++) {
                WeaponModifier mod = modifiers.get(i);
                if (i > 0) sb.append(", ");
                sb.append(mod.type().getDisplayName()).append(' ');
                if (mod.rolledValue() >= 1.0) {
                    // Flat modifier (e.g. ADDITIONAL_BULLETS: +1, +2)
                    sb.append('+').append((int) Math.round(mod.rolledValue()));
                } else {
                    // Percentage modifier (e.g. WEAPON_DAMAGE: +25%)
                    sb.append('+').append((int) Math.round(mod.rolledValue() * 100)).append('%');
                }
            }
            ui.set("#CratePickupModifiersLabel.TextSpans", Message.raw(sb.toString()));
            ui.set("#CratePickupModifiersLabel.Visible", true);
        } else {
            ui.set("#CratePickupModifiersLabel.Visible", false);
        }
    }

    private void buildStatRow(@Nonnull UICommandBuilder ui, int index,
                               @Nonnull String label, double baseValue,
                               double modBonus, boolean isMultiplier) {
        String prefix = "#CratePickupStat";
        ui.set(prefix + "Label" + index + ".TextSpans", Message.raw(label));

        if (label.equals("Speed")) {
            ui.set(prefix + "Base" + index + ".TextSpans", Message.raw(String.format("%.2fs", baseValue)));
        } else {
            ui.set(prefix + "Base" + index + ".TextSpans", Message.raw(
                    baseValue == (int) baseValue ? Integer.toString((int) baseValue) : String.format("%.1f", baseValue)));
        }

        if (modBonus > 0.001) {
            if (isMultiplier) {
                double absoluteBonus = baseValue * modBonus;
                if (label.equals("Speed")) {
                    if (absoluteBonus > 0.005) {
                        ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw(String.format("(+%.2fs)", absoluteBonus)));
                    } else {
                        ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw(""));
                    }
                } else {
                    int bonus = (int) Math.round(absoluteBonus);
                    if (bonus > 0) {
                        ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw("(+" + bonus + ")"));
                    } else {
                        ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw(""));
                    }
                }
            } else {
                // Flat non-multiplier (e.g. Pellets (+1))
                int bonus = (int) Math.round(modBonus);
                if (bonus > 0) {
                    ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw("(+" + bonus + ")"));
                } else {
                    ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw(""));
                }
            }
        } else {
            ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw(""));
        }
    }

    private void hideStatRow(@Nonnull UICommandBuilder ui, int index) {
        String prefix = "#CratePickupStat";
        ui.set(prefix + "Label" + index + ".TextSpans", Message.raw(""));
        ui.set(prefix + "Base" + index + ".TextSpans", Message.raw(""));
        ui.set(prefix + "Mod" + index + ".TextSpans", Message.raw(""));
    }

    @Nonnull
    private String buildWeaponTitle() {
        StringBuilder sb = new StringBuilder();
        if (rarity != WeaponRarity.BASIC) {
            sb.append('[').append(rarity.name()).append("] ");
        }
        if (effect != DamageEffect.NONE) {
            sb.append(effect.getDisplayName()).append(' ');
        }
        String name = this.itemDisplayName;
        if ((name == null || name.isBlank()) && definition != null) {
            name = definition.getDisplayName();
        }
        if (name != null && !name.isBlank()) {
            sb.append(name);
        }
        return sb.toString().toUpperCase();
    }

    @Nonnull
    private static String effectColorHex(@Nonnull DamageEffect e) {
        return String.format("#%02x%02x%02x", e.getTrailR(), e.getTrailG(), e.getTrailB());
    }

    private double getModBonus(@Nonnull WeaponModifierType type) {
        double total = 0.0;
        for (WeaponModifier mod : modifiers) {
            if (mod.type() == type) {
                total += mod.rolledValue();
            }
        }
        return total;
    }

    @Nonnull
    public String getItemDisplayName() {
        return itemDisplayName;
    }

    @Nullable
    public String getItemIconPath() {
        return itemIconPath;
    }

    public int getItemQuantity() {
        return itemQuantity;
    }
}
