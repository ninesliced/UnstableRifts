package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.armor.ArmorModifier;
import dev.ninesliced.unstablerifts.armor.ArmorModifierType;
import dev.ninesliced.unstablerifts.armor.ArmorSetAbility;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Always-visible armor status HUD positioned to the right of the weapon HUD.
 * Compact mode shows set name, piece count, quick stat summary, and ability charge.
 * Expanded mode (crouching) adds full stat rows, modifiers, and set bonus details.
 */
public final class ArmorHud extends CustomUIHud {
    public static final String UI_PATH = "Hud/UnstableRifts/ArmorHud.ui";
    public static final String HUD_ID = "UnstableRifts_Armor";

    private static final int ABILITY_BAR_WIDTH = 197;
    private static final int ABILITY_BAR_HEIGHT = 3;

    // Compact fields
    @Nullable
    private final String bestSetId;
    private final int bestSetCount;
    private final int setTotalPieces;
    @Nonnull
    private final WeaponRarity accentRarity;

    // Aggregate stats across all equipped pieces
    private final float totalProtection;
    private final float totalCloseDef;
    private final float totalFarDef;
    private final float totalKnockback;
    private final float totalSpikeDmg;
    private final float totalHP;
    private final float totalSpeed;

    // Ability state
    @Nonnull
    private final ArmorSetAbility ability;
    private final float chargeProgress;
    private final boolean abilityActive;

    // Expanded content
    private final boolean crouching;
    @Nonnull
    private final List<ArmorModifier> allModifiers;

    public ArmorHud(@Nonnull PlayerRef playerRef,
                    @Nullable String bestSetId, int bestSetCount, int setTotalPieces,
                    @Nonnull WeaponRarity accentRarity,
                    float totalProtection, float totalCloseDef, float totalFarDef,
                    float totalKnockback, float totalSpikeDmg, float totalHP, float totalSpeed,
                    @Nonnull ArmorSetAbility ability, float chargeProgress, boolean abilityActive,
                    boolean crouching,
                    @Nonnull List<ArmorModifier> allModifiers) {
        super(playerRef, HUD_ID);
        this.bestSetId = bestSetId;
        this.bestSetCount = bestSetCount;
        this.setTotalPieces = setTotalPieces;
        this.accentRarity = accentRarity;
        this.totalProtection = totalProtection;
        this.totalCloseDef = totalCloseDef;
        this.totalFarDef = totalFarDef;
        this.totalKnockback = totalKnockback;
        this.totalSpikeDmg = totalSpikeDmg;
        this.totalHP = totalHP;
        this.totalSpeed = totalSpeed;
        this.ability = ability;
        this.chargeProgress = chargeProgress;
        this.abilityActive = abilityActive;
        this.crouching = crouching;
        this.allModifiers = allModifiers;
    }

    private static void setBarFill(@Nonnull UICommandBuilder ui, float progress) {
        int fillWidth = Math.max(0, Math.min(ABILITY_BAR_WIDTH,
                Math.round(Math.max(0f, Math.min(1f, progress)) * ABILITY_BAR_WIDTH)));
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(0));
        anchor.setTop(Value.of(0));
        anchor.setWidth(Value.of(fillWidth));
        anchor.setHeight(Value.of(ABILITY_BAR_HEIGHT));
        ui.setObject("#ArmorAbilityBarFill.Anchor", anchor);
    }

    // ── Compact helpers ────────────────────────────────────────────────

    @Nonnull
    private static String formatInt(float value) {
        return value == (int) value ? Integer.toString((int) value) : String.format("%.1f", value);
    }

    // ── Expanded helpers ───────────────────────────────────────────────

    @Nonnull
    private static String formatModifier(@Nonnull ArmorModifier mod) {
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
    private static String capitalize(@Nonnull String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#ArmorRoot.Visible", true);

        // Accent color
        ui.set("#ArmorPanel.Background", accentRarity.getColorHex());

        // ── Title (always visible) ──
        String title = bestSetId != null ? capitalize(bestSetId) + " Set" : "Armor";
        ui.set("#ArmorSetTitle.TextSpans", Message.raw(title.toUpperCase()));
        ui.set("#ArmorSetTitle.Style.TextColor", accentRarity.getColorHex());

        // ── Piece count (always visible) ──
        ui.set("#ArmorPieceCount.TextSpans", Message.raw(bestSetCount + "/" + setTotalPieces + " equipped"));

        // ── Quick stats summary (always visible, compact line) ──
        String quickStats = buildQuickStats();
        if (!quickStats.isEmpty()) {
            ui.set("#ArmorQuickStats.Visible", true);
            ui.set("#ArmorQuickStats.TextSpans", Message.raw(quickStats));
        }

        // ── Ability section (always visible when set has ability) ──
        if (ability != ArmorSetAbility.NONE) {
            ui.set("#ArmorAbilitySection.Visible", true);
            ui.set("#ArmorAbilityName.TextSpans", Message.raw(ability.getDisplayName()));

            if (abilityActive) {
                ui.set("#ArmorAbilityStatus.TextSpans", Message.raw("ACTIVE"));
                ui.set("#ArmorAbilityStatus.Style.TextColor", "#d4534a");
                ui.set("#ArmorAbilityBarFill.Background", "#d4534a");
                setBarFill(ui, 1.0f);
            } else if (bestSetCount >= setTotalPieces) {
                if (chargeProgress >= 1.0f) {
                    ui.set("#ArmorAbilityStatus.TextSpans", Message.raw("READY! [F]"));
                    ui.set("#ArmorAbilityStatus.Style.TextColor", "#3e9049");
                    ui.set("#ArmorAbilityBarFill.Background", "#3e9049");
                    setBarFill(ui, 1.0f);
                } else {
                    int pct = (int) (chargeProgress * 100);
                    ui.set("#ArmorAbilityStatus.TextSpans", Message.raw(pct + "%"));
                    ui.set("#ArmorAbilityStatus.Style.TextColor", "#7aa8d4");
                    ui.set("#ArmorAbilityBarFill.Background", "#7aa8d4");
                    setBarFill(ui, chargeProgress);
                }
            } else {
                ui.set("#ArmorAbilityStatus.TextSpans", Message.raw(bestSetCount + "/" + setTotalPieces));
                ui.set("#ArmorAbilityStatus.Style.TextColor", "#8a9bb0");
                ui.set("#ArmorAbilityBarFill.Background", "#8a9bb0");
                setBarFill(ui, (float) bestSetCount / setTotalPieces);
            }
        }

        // ── Expanded sections (crouching only) ──
        if (crouching) {
            buildExpandedStats(ui);
            buildExpandedModifiers(ui);
            buildExpandedSetBonus(ui);
        }
    }

    @Nonnull
    private String buildQuickStats() {
        StringBuilder sb = new StringBuilder();
        if (totalHP > 0) {
            sb.append("+").append(formatInt(totalHP)).append(" HP");
        }
        if (totalProtection > 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("+").append(formatInt(totalProtection)).append(" Prot");
        }
        if (totalSpeed > 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("+").append(Math.round(totalSpeed * 100)).append("% Spd");
        }
        return sb.toString();
    }

    // ── Utility ────────────────────────────────────────────────────────

    private void buildExpandedStats(@Nonnull UICommandBuilder ui) {
        ui.set("#ArmorStatsSection.Visible", true);

        buildStatRow(ui, "Protection", totalProtection, ArmorModifierType.PROTECTION, false);
        buildStatRow(ui, "CloseDef", totalCloseDef, ArmorModifierType.CLOSE_DMG_REDUCE, true);
        buildStatRow(ui, "FarDef", totalFarDef, ArmorModifierType.FAR_DMG_REDUCE, true);
        buildStatRow(ui, "Knockback", totalKnockback, ArmorModifierType.KNOCKBACK_ENEMIES, false);
        buildStatRow(ui, "SpikeDmg", totalSpikeDmg, ArmorModifierType.SPIKE_DAMAGE, false);
        buildStatRow(ui, "MaxHP", totalHP, ArmorModifierType.LIFE_BOOST, false);
        buildStatRow(ui, "Speed", totalSpeed, ArmorModifierType.SPEED_BOOST, true);
    }

    private void buildStatRow(@Nonnull UICommandBuilder ui, @Nonnull String name,
                              float value, @Nonnull ArmorModifierType modType, boolean isPercent) {
        if (value <= 0) {
            ui.set("#ArmorStat" + name + "Row.Visible", false);
            return;
        }
        if (isPercent) {
            ui.set("#ArmorStat" + name + "Base.TextSpans", Message.raw(Math.round(value * 100) + "%"));
        } else {
            ui.set("#ArmorStat" + name + "Base.TextSpans", Message.raw(formatInt(value)));
        }

        double modBonus = getModBonus(modType);
        if (modBonus > 0.001) {
            if (isPercent) {
                int bonusPct = (int) Math.round(modBonus * 100);
                ui.set("#ArmorStat" + name + "Mod.TextSpans", Message.raw(bonusPct > 0 ? "(+" + bonusPct + "%)" : ""));
            } else {
                int bonus = (int) Math.round(value * modBonus);
                ui.set("#ArmorStat" + name + "Mod.TextSpans", Message.raw(bonus > 0 ? "(+" + bonus + ")" : ""));
            }
        } else {
            ui.set("#ArmorStat" + name + "Mod.TextSpans", Message.raw(""));
        }
    }

    private void buildExpandedModifiers(@Nonnull UICommandBuilder ui) {
        int modCount = Math.min(allModifiers.size(), 5);
        if (modCount > 0) {
            ui.set("#ArmorModSection.Visible", true);
            for (int i = 0; i < modCount; i++) {
                ArmorModifier mod = allModifiers.get(i);
                ui.set("#ArmorMod" + i + ".TextSpans", Message.raw(formatModifier(mod)));
            }
            for (int i = modCount; i < 5; i++) {
                ui.set("#ArmorMod" + i + ".Visible", false);
            }
        }
    }

    private void buildExpandedSetBonus(@Nonnull UICommandBuilder ui) {
        if (bestSetId == null || ability == ArmorSetAbility.NONE) return;

        ui.set("#ArmorSetBonusSection.Visible", true);
        ui.set("#ArmorSetBonusTitle.TextSpans",
                Message.raw(capitalize(bestSetId) + " Set (" + bestSetCount + "/" + setTotalPieces + ")"));
        ui.set("#ArmorSetBonusPartial.TextSpans", Message.raw("2/4: +10% all base stats"));
        ui.set("#ArmorSetBonusFull.TextSpans",
                Message.raw("4/4: " + ability.getDisplayName() + " ability"));

        if (bestSetCount >= 2) {
            ui.set("#ArmorSetBonusPartial.Style.TextColor", "#3e9049");
        }
        if (bestSetCount >= 4) {
            ui.set("#ArmorSetBonusFull.Style.TextColor", ability.getColorHex());
        }
    }

    private double getModBonus(@Nonnull ArmorModifierType type) {
        double total = 0.0;
        for (ArmorModifier mod : allModifiers) {
            if (mod.type() == type) {
                total += mod.rolledValue();
            }
        }
        return total;
    }
}
