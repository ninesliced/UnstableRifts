package dev.ninesliced.shotcave.hud;

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

public final class ShotcaveHud extends CustomUIHud {
    public static final String UI_PATH = "Hud/Shotcave/AmmoHud.ui";

    private static final double LOW_AMMO_THRESHOLD = 0.25;

    private static final String COLOR_BRIGHT = "#e8ecf0";
    private static final String COLOR_LOW = "#d4534a";
    private static final String BAR_NORMAL = "#7aa8d4";
    private static final String BAR_LOW = "#d4534a";

    private final int ammo;
    private final int baseMaxAmmo;
    private final int maxAmmo;
    @Nonnull private final WeaponRarity rarity;
    @Nonnull private final DamageEffect effect;
    @Nonnull private final WeaponCategory category;
    @Nullable private final WeaponDefinition definition;
    @Nonnull private final List<WeaponModifier> modifiers;
    @Nullable private final String weaponName;

    public ShotcaveHud(@Nonnull PlayerRef playerRef, int ammo, int baseMaxAmmo, int maxAmmo,
                       @Nonnull WeaponRarity rarity, @Nonnull DamageEffect effect,
                       @Nonnull WeaponCategory category, @Nullable WeaponDefinition definition,
                       @Nonnull List<WeaponModifier> modifiers, @Nullable String weaponName) {
        super(playerRef);
        this.ammo = Math.max(0, ammo);
        this.baseMaxAmmo = Math.max(1, baseMaxAmmo);
        this.maxAmmo = Math.max(1, maxAmmo);
        this.rarity = rarity;
        this.effect = effect;
        this.category = category;
        this.definition = definition;
        this.modifiers = modifiers;
        this.weaponName = weaponName;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#ShotcaveAmmoRoot.Visible", true);

        // ── Weapon title ──
        String title = buildWeaponTitle();
        ui.set("#ShotcaveWeaponTitle.TextSpans", Message.raw(title));
        ui.set("#ShotcaveWeaponTitle.Style.TextColor", rarity.getColorHex());

        // ── Rarity accent bar ──
        ui.set("#ShotcaveRarityAccent.Background", rarity.getColorHex());

        // ── Category label ──
        ui.set("#ShotcaveCategoryLabel.TextSpans", Message.raw(category.name()));

        // ── Stats grid (category-aware) ──
        if (category == WeaponCategory.SUMMONING) {
            // Summoning weapons: relabel and show mob-relevant stats
            ui.set("#ShotcaveStatDamageLabel.TextSpans", Message.raw("Mob HP"));
            buildStatRow(ui, "Mob HP", "#ShotcaveStatDamageBase", "#ShotcaveStatDamageMod",
                    definition != null ? definition.getBaseMobHealth() : 0, getModBonus(WeaponModifierType.MOB_HEALTH), true);

            ui.set("#ShotcaveStatSpeedLabel.TextSpans", Message.raw("Mob Dmg"));
            buildStatRow(ui, "Mob Dmg", "#ShotcaveStatSpeedBase", "#ShotcaveStatSpeedMod",
                    definition != null ? definition.getBaseMobDamage() : 0, getModBonus(WeaponModifierType.MOB_DAMAGE), true);

            ui.set("#ShotcaveStatRangeLabel.TextSpans", Message.raw("Mob Life"));
            buildStatRow(ui, "Mob Life", "#ShotcaveStatRangeBase", "#ShotcaveStatRangeMod",
                    definition != null ? definition.getBaseMobLifetime() : 0, getModBonus(WeaponModifierType.MOB_LIFETIME), true);

            // Hide unused stat rows
            hideStatRow(ui, "#ShotcaveStatPrecisionLabel", "#ShotcaveStatPrecisionBase", "#ShotcaveStatPrecisionMod");
            hideStatRow(ui, "#ShotcaveStatKnockbackLabel", "#ShotcaveStatKnockbackBase", "#ShotcaveStatKnockbackMod");

            ui.set("#ShotcaveStatMaxAmmoLabel.TextSpans", Message.raw("Max Ammo"));
            {
                int maxAmmoBonus = this.maxAmmo - this.baseMaxAmmo;
                ui.set("#ShotcaveStatMaxAmmoBase.TextSpans", Message.raw(Integer.toString(this.baseMaxAmmo)));
                if (maxAmmoBonus > 0) {
                    ui.set("#ShotcaveStatMaxAmmoMod.TextSpans", Message.raw("(+" + maxAmmoBonus + ")"));
                } else {
                    ui.set("#ShotcaveStatMaxAmmoMod.TextSpans", Message.raw(""));
                }
            }

            hideStatRow(ui, "#ShotcaveStatPelletsLabel", "#ShotcaveStatPelletsBase", "#ShotcaveStatPelletsMod");
        } else {
            // LASER / BULLET weapons: show combat stats with default labels
            ui.set("#ShotcaveStatDamageLabel.TextSpans", Message.raw("Damage"));
            buildStatRow(ui, "Damage", "#ShotcaveStatDamageBase", "#ShotcaveStatDamageMod",
                    definition != null ? definition.getBaseDamage() : 0, getModBonus(WeaponModifierType.WEAPON_DAMAGE), true);

            ui.set("#ShotcaveStatSpeedLabel.TextSpans", Message.raw("Speed"));
            buildStatRow(ui, "Speed", "#ShotcaveStatSpeedBase", "#ShotcaveStatSpeedMod",
                    definition != null ? definition.getBaseCooldown() : 0, getModBonus(WeaponModifierType.ATTACK_SPEED), true);

            ui.set("#ShotcaveStatRangeLabel.TextSpans", Message.raw("Range"));
            buildStatRow(ui, "Range", "#ShotcaveStatRangeBase", "#ShotcaveStatRangeMod",
                    definition != null ? definition.getBaseRange() : 0, getModBonus(WeaponModifierType.MAX_RANGE), true);

            // Precision: 100% = no spread, lower = more spread
            ui.set("#ShotcaveStatPrecisionLabel.TextSpans", Message.raw("Precision"));
            double baseSpread = definition != null ? definition.getBaseSpread() : 0;
            double basePrecision = Math.max(0, 100.0 - baseSpread * 10.0);
            double precisionBonus = getModBonus(WeaponModifierType.PRECISION);
            ui.set("#ShotcaveStatPrecisionBase.TextSpans", Message.raw(
                    String.format("%.0f%%", basePrecision)));
            if (precisionBonus > 0.001) {
                int bonusPoints = (int) Math.round(baseSpread * precisionBonus * 10.0);
                if (bonusPoints > 0) {
                    ui.set("#ShotcaveStatPrecisionMod.TextSpans", Message.raw("(+" + bonusPoints + ")"));
                } else {
                    ui.set("#ShotcaveStatPrecisionMod.TextSpans", Message.raw(""));
                }
            } else {
                ui.set("#ShotcaveStatPrecisionMod.TextSpans", Message.raw(""));
            }

            ui.set("#ShotcaveStatKnockbackLabel.TextSpans", Message.raw("Knockback"));
            buildStatRow(ui, "Knockback", "#ShotcaveStatKnockbackBase", "#ShotcaveStatKnockbackMod",
                    definition != null ? definition.getBaseKnockback() : 0, getModBonus(WeaponModifierType.KNOCKBACK), true);

            ui.set("#ShotcaveStatMaxAmmoLabel.TextSpans", Message.raw("Max Ammo"));
            {
                int maxAmmoBonus = this.maxAmmo - this.baseMaxAmmo;
                ui.set("#ShotcaveStatMaxAmmoBase.TextSpans", Message.raw(Integer.toString(this.baseMaxAmmo)));
                if (maxAmmoBonus > 0) {
                    ui.set("#ShotcaveStatMaxAmmoMod.TextSpans", Message.raw("(+" + maxAmmoBonus + ")"));
                } else {
                    ui.set("#ShotcaveStatMaxAmmoMod.TextSpans", Message.raw(""));
                }
            }

            // Only show Pellets for multi-pellet weapons (blunderbusses)
            int basePellets = definition != null ? definition.getBasePellets() : 1;
            if (basePellets > 1) {
                ui.set("#ShotcaveStatPelletsLabel.TextSpans", Message.raw("Pellets"));
                buildStatRow(ui, "Pellets", "#ShotcaveStatPelletsBase", "#ShotcaveStatPelletsMod",
                        basePellets, getModBonus(WeaponModifierType.ADDITIONAL_BULLETS), false);
            } else {
                hideStatRow(ui, "#ShotcaveStatPelletsLabel", "#ShotcaveStatPelletsBase", "#ShotcaveStatPelletsMod");
            }
        }

        // ── Modifiers summary ──
        if (!modifiers.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(modifiers.size()).append(" modifier(s):\n- ");
            for (int i = 0; i < modifiers.size(); i++) {
                WeaponModifier mod = modifiers.get(i);
                if (i > 0) sb.append("\n- ");
                sb.append(mod.type().getDisplayName()).append(' ');
                if (mod.rolledValue() >= 1.0) {
                    sb.append('+').append((int) Math.round(mod.rolledValue()));
                } else {
                    sb.append('+').append((int) Math.round(mod.rolledValue() * 100)).append('%');
                }
            }
            ui.set("#ShotcaveModifiersLabel.TextSpans", Message.raw(sb.toString()));
            ui.set("#ShotcaveModifiersLabel.Visible", true);
        } else {
            ui.set("#ShotcaveModifiersLabel.Visible", false);
        }

        // ── Ammo counter ──
        ui.set("#ShotcaveAmmoValue.TextSpans", Message.raw(Integer.toString(this.ammo)));
        ui.set("#ShotcaveMaxAmmoValue.TextSpans", Message.raw(Integer.toString(this.maxAmmo)));

        double ratio = (double) this.ammo / (double) this.maxAmmo;
        boolean isLow = ratio <= LOW_AMMO_THRESHOLD && this.ammo > 0;
        boolean isEmpty = this.ammo <= 0;

        int barWidth = 190;
        int fillRight = barWidth - (int) Math.round(ratio * barWidth);
        ui.set("#ShotcaveAmmoBarFill.Anchor.Right", fillRight);

        if (isEmpty || isLow) {
            ui.set("#ShotcaveAmmoValue.Style.TextColor", COLOR_LOW);
            ui.set("#ShotcaveAmmoBarFill.Background", BAR_LOW);
        }
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
        if (weaponName != null && !weaponName.isBlank()) {
            sb.append(weaponName);
        } else if (definition != null) {
            sb.append(definition.getDisplayName());
        }
        return sb.toString().toUpperCase();
    }

    private void buildStatRow(@Nonnull UICommandBuilder ui,
                               @Nonnull String stat,
                               @Nonnull String baseId, @Nonnull String modId,
                               double baseValue, double modBonus, boolean isMultiplier) {
        if (stat.equals("Speed")) {
            ui.set(baseId + ".TextSpans", Message.raw(String.format("%.2fs", baseValue)));
        } else {
            ui.set(baseId + ".TextSpans", Message.raw(
                    baseValue == (int) baseValue ? Integer.toString((int) baseValue) : String.format("%.1f", baseValue)));
        }

        if (modBonus > 0.001) {
            if (isMultiplier) {
                double absoluteBonus = baseValue * modBonus;
                if (stat.equals("Speed")) {
                    if (absoluteBonus > 0.005) {
                        ui.set(modId + ".TextSpans", Message.raw(String.format("(+%.2fs)", absoluteBonus)));
                    } else {
                        ui.set(modId + ".TextSpans", Message.raw(""));
                    }
                } else {
                    int bonus = (int) Math.round(absoluteBonus);
                    if (bonus > 0) {
                        ui.set(modId + ".TextSpans", Message.raw("(+" + bonus + ")"));
                    } else {
                        ui.set(modId + ".TextSpans", Message.raw(""));
                    }
                }
            } else {
                // Flat non-multiplier (e.g. Pellets (+1))
                int bonus = (int) Math.round(modBonus);
                if (bonus > 0) {
                    ui.set(modId + ".TextSpans", Message.raw("(+" + bonus + ")"));
                } else {
                    ui.set(modId + ".TextSpans", Message.raw(""));
                }
            }
        } else {
            ui.set(modId + ".TextSpans", Message.raw(""));
        }
    }

    private void hideStatRow(@Nonnull UICommandBuilder ui,
                              @Nonnull String labelId, @Nonnull String baseId, @Nonnull String modId) {
        ui.set(labelId + ".TextSpans", Message.raw(""));
        ui.set(baseId + ".TextSpans", Message.raw(""));
        ui.set(modId + ".TextSpans", Message.raw(""));
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
}
