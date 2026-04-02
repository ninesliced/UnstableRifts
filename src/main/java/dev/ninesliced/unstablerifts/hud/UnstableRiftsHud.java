package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.guns.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public final class UnstableRiftsHud extends CustomUIHud {
    public static final String UI_PATH = "Hud/UnstableRifts/AmmoHud.ui";

    private static final double LOW_AMMO_THRESHOLD = 0.25;

    private static final String COLOR_BRIGHT = "#e8ecf0";
    private static final String COLOR_LOW = "#d4534a";
    private static final String BAR_NORMAL = "#7aa8d4";
    private static final String BAR_LOW = "#d4534a";
    private static final int BAR_WIDTH = 283;        // 310 - 3 accent - 10 left pad - 14 right pad
    private static final int BAR_HEIGHT = 5;

    private final int ammo;
    private final int baseMaxAmmo;
    private final int maxAmmo;
    @Nonnull
    private final WeaponRarity rarity;
    @Nonnull
    private final DamageEffect effect;
    @Nonnull
    private final WeaponCategory category;
    @Nullable
    private final WeaponDefinition definition;
    @Nonnull
    private final List<WeaponModifier> modifiers;
    @Nullable
    private final String weaponName;
    @Nullable
    private final String weaponIconPath;
    private final boolean crouching;

    public UnstableRiftsHud(@Nonnull PlayerRef playerRef, int ammo, int baseMaxAmmo, int maxAmmo,
                            @Nonnull WeaponRarity rarity, @Nonnull DamageEffect effect,
                            @Nonnull WeaponCategory category, @Nullable WeaponDefinition definition,
                            @Nonnull List<WeaponModifier> modifiers, @Nullable String weaponName,
                            @Nullable String weaponIconPath,
                            boolean crouching) {
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
        this.weaponIconPath = weaponIconPath;
        this.crouching = crouching;
    }

    private static void setAmmoBarWidth(@Nonnull UICommandBuilder ui, int width) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(0));
        anchor.setTop(Value.of(0));
        anchor.setWidth(Value.of(Math.max(0, Math.min(BAR_WIDTH, width))));
        anchor.setHeight(Value.of(BAR_HEIGHT));
        ui.setObject("#UnstableRiftsAmmoBarFill.Anchor", anchor);
    }

    @Nonnull
    private static String formatModifier(@Nonnull WeaponModifier mod) {
        StringBuilder sb = new StringBuilder("> ");
        sb.append(mod.type().getDisplayName()).append(' ');
        if (mod.rolledValue() >= 1.0) {
            sb.append('+').append((int) Math.round(mod.rolledValue()));
        } else {
            sb.append('+').append((int) Math.round(mod.rolledValue() * 100)).append('%');
        }
        return sb.toString();
    }

    @Nonnull
    private static String effectColorHex(@Nonnull DamageEffect e) {
        return String.format("#%02x%02x%02x", e.getTrailR(), e.getTrailG(), e.getTrailB());
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#UnstableRiftsAmmoRoot.Visible", true);

        // Set weapon icon if available
        if (weaponIconPath != null && !weaponIconPath.isBlank()) {
            ui.set("#UnstableRiftsWeaponIcon.AssetPath", weaponIconPath);
            ui.set("#UnstableRiftsWeaponIcon.Visible", true);
        }

        String title = buildWeaponTitle();
        ui.set("#UnstableRiftsWeaponTitle.TextSpans", Message.raw(title));
        ui.set("#UnstableRiftsWeaponTitle.Style.TextColor", rarity.getColorHex());

        ui.set("#UnstableRiftsAmmoPanel.Background", rarity.getColorHex());

        StringBuilder sub = new StringBuilder();
        if (rarity != WeaponRarity.BASIC) {
            sub.append(rarity.name()).append(" \u2022 ");
        }
        sub.append(category.name());
        ui.set("#UnstableRiftsCategoryLabel.TextSpans", Message.raw(sub.toString()));

        if (effect != DamageEffect.NONE) {
            ui.set("#UnstableRiftsEffectLabel.TextSpans", Message.raw("\u2022 " + effect.getDisplayName()));
            ui.set("#UnstableRiftsEffectLabel.Style.TextColor", effectColorHex(effect));
            ui.set("#UnstableRiftsEffectLabel.Visible", true);
        }

        if (crouching) {
            // Expanded view: show stats + modifiers
            ui.set("#UnstableRiftsStatsSection.Visible", true);

            if (category == WeaponCategory.SUMMONING) {
                buildSummoningStats(ui);
            } else {
                buildCombatStats(ui);
            }

            int modCount = Math.min(modifiers.size(), 5);
            if (modCount > 0) {
                ui.set("#UnstableRiftsModSection.Visible", true);
                for (int i = 0; i < modCount; i++) {
                    WeaponModifier mod = modifiers.get(i);
                    String modText = formatModifier(mod);
                    ui.set("#UnstableRiftsMod" + i + ".TextSpans", Message.raw(modText));
                }
                for (int i = modCount; i < 5; i++) {
                    ui.set("#UnstableRiftsMod" + i + ".Visible", false);
                }
            } else {
                ui.set("#UnstableRiftsModSection.Visible", false);
            }
        } else {
            // Compact view: hide stats + modifiers
            ui.set("#UnstableRiftsStatsSection.Visible", false);
            ui.set("#UnstableRiftsModSection.Visible", false);
        }

        if (category == WeaponCategory.MELEE) {
            ui.set("#UnstableRiftsAmmoSection.Visible", false);
        } else {
            ui.set("#UnstableRiftsAmmoValue.TextSpans", Message.raw(Integer.toString(this.ammo)));
            ui.set("#UnstableRiftsMaxAmmoValue.TextSpans", Message.raw(Integer.toString(this.maxAmmo)));

            double ratio = (double) this.ammo / (double) this.maxAmmo;
            boolean isLow = ratio <= LOW_AMMO_THRESHOLD && this.ammo > 0;
            boolean isEmpty = this.ammo <= 0;

            int fillWidth = (int) Math.round(ratio * BAR_WIDTH);
            setAmmoBarWidth(ui, fillWidth);

            ui.set("#UnstableRiftsAmmoValue.Style.TextColor", isEmpty || isLow ? COLOR_LOW : COLOR_BRIGHT);
            ui.set("#UnstableRiftsAmmoBarFill.Background", isEmpty || isLow ? BAR_LOW : BAR_NORMAL);
        }
    }

    private void buildSummoningStats(@Nonnull UICommandBuilder ui) {
        ui.set("#UnstableRiftsStatDamageLabel.TextSpans", Message.raw("Mob HP"));
        buildStatRow(ui, "Mob HP", "#UnstableRiftsStatDamageBase", "#UnstableRiftsStatDamageMod",
                definition != null ? definition.baseMobHealth() : 0, getModBonus(WeaponModifierType.MOB_HEALTH), true);

        ui.set("#UnstableRiftsStatSpeedLabel.TextSpans", Message.raw("Mob Dmg"));
        buildStatRow(ui, "Mob Dmg", "#UnstableRiftsStatSpeedBase", "#UnstableRiftsStatSpeedMod",
                definition != null ? definition.baseMobDamage() : 0, getModBonus(WeaponModifierType.MOB_DAMAGE), true);

        ui.set("#UnstableRiftsStatRangeLabel.TextSpans", Message.raw("Mob Life"));
        buildStatRow(ui, "Mob Life", "#UnstableRiftsStatRangeBase", "#UnstableRiftsStatRangeMod",
                definition != null ? definition.baseMobLifetime() : 0, getModBonus(WeaponModifierType.MOB_LIFETIME), true);

        ui.set("#UnstableRiftsStatPrecisionRow.Visible", false);
        ui.set("#UnstableRiftsStatKnockbackRow.Visible", false);

        ui.set("#UnstableRiftsStatMaxAmmoLabel.TextSpans", Message.raw("Max Ammo"));
        buildMaxAmmoRow(ui);

        ui.set("#UnstableRiftsStatPelletsRow.Visible", false);

        buildEffectTimeRow(ui);
    }

    private void buildCombatStats(@Nonnull UICommandBuilder ui) {
        ui.set("#UnstableRiftsStatDamageLabel.TextSpans", Message.raw("Damage"));
        buildStatRow(ui, "Damage", "#UnstableRiftsStatDamageBase", "#UnstableRiftsStatDamageMod",
                definition != null ? definition.baseDamage() : 0, getModBonus(WeaponModifierType.WEAPON_DAMAGE), true);

        ui.set("#UnstableRiftsStatSpeedLabel.TextSpans", Message.raw("Speed"));
        buildStatRow(ui, "Speed", "#UnstableRiftsStatSpeedBase", "#UnstableRiftsStatSpeedMod",
                definition != null ? definition.baseCooldown() : 0, getModBonus(WeaponModifierType.ATTACK_SPEED), true);

        if (category == WeaponCategory.MELEE) {
            // Melee: show knockback but hide range, precision, max ammo, pellets
            ui.set("#UnstableRiftsStatRangeRow.Visible", false);
            ui.set("#UnstableRiftsStatPrecisionRow.Visible", false);

            ui.set("#UnstableRiftsStatKnockbackLabel.TextSpans", Message.raw("Knockback"));
            buildStatRow(ui, "Knockback", "#UnstableRiftsStatKnockbackBase", "#UnstableRiftsStatKnockbackMod",
                    definition != null ? definition.baseKnockback() : 0, getModBonus(WeaponModifierType.KNOCKBACK), true);

            ui.set("#UnstableRiftsStatMaxAmmoRow.Visible", false);
            ui.set("#UnstableRiftsStatPelletsRow.Visible", false);
        } else {
            ui.set("#UnstableRiftsStatRangeLabel.TextSpans", Message.raw("Range"));
            buildStatRow(ui, "Range", "#UnstableRiftsStatRangeBase", "#UnstableRiftsStatRangeMod",
                    definition != null ? definition.baseRange() : 0, getModBonus(WeaponModifierType.MAX_RANGE), true);

            ui.set("#UnstableRiftsStatPrecisionLabel.TextSpans", Message.raw("Precision"));
            double baseSpread = definition != null ? definition.baseSpread() : 0;
            double basePrecision = definition != null && definition.basePrecision() >= 0
                    ? definition.basePrecision()
                    : Math.max(0, 100.0 - baseSpread * 10.0);
            double precisionBonus = getModBonus(WeaponModifierType.PRECISION);
            ui.set("#UnstableRiftsStatPrecisionBase.TextSpans", Message.raw(
                    String.format("%.0f%%", basePrecision)));
            if (precisionBonus > 0.001) {
                int bonusPoints = (int) Math.round(baseSpread * precisionBonus * 10.0);
                if (bonusPoints > 0) {
                    ui.set("#UnstableRiftsStatPrecisionMod.TextSpans", Message.raw("(+" + bonusPoints + ")"));
                } else {
                    ui.set("#UnstableRiftsStatPrecisionMod.TextSpans", Message.raw(""));
                }
            } else {
                ui.set("#UnstableRiftsStatPrecisionMod.TextSpans", Message.raw(""));
            }

            ui.set("#UnstableRiftsStatKnockbackLabel.TextSpans", Message.raw("Knockback"));
            buildStatRow(ui, "Knockback", "#UnstableRiftsStatKnockbackBase", "#UnstableRiftsStatKnockbackMod",
                    definition != null ? definition.baseKnockback() : 0, getModBonus(WeaponModifierType.KNOCKBACK), true);

            ui.set("#UnstableRiftsStatMaxAmmoLabel.TextSpans", Message.raw("Max Ammo"));
            buildMaxAmmoRow(ui);

            int basePellets = definition != null ? definition.basePellets() : 1;
            if (basePellets > 1) {
                ui.set("#UnstableRiftsStatPelletsLabel.TextSpans", Message.raw("Pellets"));
                buildStatRow(ui, "Pellets", "#UnstableRiftsStatPelletsBase", "#UnstableRiftsStatPelletsMod",
                        basePellets, getModBonus(WeaponModifierType.ADDITIONAL_BULLETS), false);
            } else {
                ui.set("#UnstableRiftsStatPelletsRow.Visible", false);
            }
        }

        buildEffectTimeRow(ui);
    }

    @Nonnull
    private String buildWeaponTitle() {
        StringBuilder sb = new StringBuilder();
        if (rarity != WeaponRarity.BASIC) {
            sb.append(rarity.name()).append(' ');
        }
        if (weaponName != null && !weaponName.isBlank()) {
            sb.append(weaponName);
        } else if (definition != null) {
            sb.append(definition.displayName());
        }
        return sb.toString().toUpperCase();
    }

    private void buildMaxAmmoRow(@Nonnull UICommandBuilder ui) {
        int maxAmmoBonus = this.maxAmmo - this.baseMaxAmmo;
        ui.set("#UnstableRiftsStatMaxAmmoBase.TextSpans", Message.raw(Integer.toString(this.baseMaxAmmo)));
        if (maxAmmoBonus > 0) {
            ui.set("#UnstableRiftsStatMaxAmmoMod.TextSpans", Message.raw("(+" + maxAmmoBonus + ")"));
        } else {
            ui.set("#UnstableRiftsStatMaxAmmoMod.TextSpans", Message.raw(""));
        }
    }

    private void buildEffectTimeRow(@Nonnull UICommandBuilder ui) {
        if (effect == DamageEffect.NONE || !effect.hasDoT()) {
            ui.set("#UnstableRiftsStatEffectTimeRow.Visible", false);
            return;
        }

        float baseMin = effect.getDotDurationMin();
        float baseMax = effect.getDotDurationMax();
        float bonus = rarity.getEffectDurationBonus();

        ui.set("#UnstableRiftsStatEffectTimeLabel.TextSpans", Message.raw("Effect Time"));
        ui.set("#UnstableRiftsStatEffectTimeLabel.Style.TextColor", effectColorHex(effect));

        if (baseMin >= baseMax) {
            ui.set("#UnstableRiftsStatEffectTimeBase.TextSpans",
                    Message.raw(String.format("%.1fs", baseMin)));
        } else {
            ui.set("#UnstableRiftsStatEffectTimeBase.TextSpans",
                    Message.raw(String.format("%.1f-%.1fs", baseMin, baseMax)));
        }

        if (bonus > 0.001f) {
            ui.set("#UnstableRiftsStatEffectTimeMod.TextSpans",
                    Message.raw(String.format("(+%.1fs)", bonus)));
        } else {
            ui.set("#UnstableRiftsStatEffectTimeMod.TextSpans", Message.raw(""));
        }
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
                    // Speed modifier REDUCES cooldown — show as negative
                    if (absoluteBonus > 0.005) {
                        ui.set(modId + ".TextSpans", Message.raw(String.format("(-%.2fs)", absoluteBonus)));
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
