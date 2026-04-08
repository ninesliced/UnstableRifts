package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ninesliced.unstablerifts.dungeon.DungeonConstants;
import dev.ninesliced.unstablerifts.dungeon.RoomData;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Top-center boss fight HUD showing the active boss name and health bar.
 */
public final class BossFightHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/BossFight.ui";
    public static final String HUD_ID = "UnstableRiftsBossFight";
    private static final int HEALTH_BAR_WIDTH = 380;
    private static final int HEALTH_BAR_HEIGHT = 12;

    @Nonnull
    private final String bossName;
    private final float currentHealth;
    private final float maxHealth;

    private BossFightHud(@Nonnull PlayerRef playerRef,
                         @Nonnull String bossName,
                         float currentHealth,
                         float maxHealth) {
        super(playerRef);
        this.bossName = bossName;
        this.currentHealth = Math.max(0f, currentHealth);
        this.maxHealth = Math.max(1f, maxHealth);
    }

    @Nullable
    public static BossFightHud fromRoom(@Nonnull PlayerRef playerRef, @Nullable RoomData bossRoom) {
        BossStatus status = BossStatus.resolve(bossRoom);
        if (status == null) {
            return null;
        }
        return new BossFightHud(playerRef, status.name(), status.currentHealth(), status.maxHealth());
    }

    public static void applyHud(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull BossFightHud hud) {
        if (HudVisibilityService.isHidden(playerRef.getUuid())) {
            return;
        }
        if (!MultiHudCompat.setHud(player, playerRef, HUD_ID, hud)) {
            // Fallback intentionally omitted to avoid replacing the primary weapon HUD.
        }
    }

    public static void hideHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        MultiHudCompat.hideHud(player, playerRef, HUD_ID);
    }

    private static void setHealthBarWidth(@Nonnull UICommandBuilder ui, int width) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(0));
        anchor.setTop(Value.of(1));
        anchor.setWidth(Value.of(Math.max(0, Math.min(HEALTH_BAR_WIDTH, width))));
        anchor.setHeight(Value.of(HEALTH_BAR_HEIGHT));
        ui.setObject("#BossHealthBarFill.Anchor", anchor);
    }

    @Nonnull
    private static String resolveBarColor(float ratio) {
        if (ratio <= 0.25f) {
            return DungeonConstants.COLOR_CRITICAL;
        }
        if (ratio <= 0.6f) {
            return DungeonConstants.COLOR_WARNING;
        }
        return "#7be36f";
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#BossFightRoot.Visible", true);
        ui.set("#BossNameLabel.TextSpans", Message.raw(bossName));

        float ratio = Math.max(0f, Math.min(1f, currentHealth / maxHealth));
        int fillWidth = Math.round(HEALTH_BAR_WIDTH * ratio);
        setHealthBarWidth(ui, fillWidth);
        ui.set("#BossHealthBarFill.Background", resolveBarColor(ratio));

        String hpText = Math.round(currentHealth) + " / " + Math.round(maxHealth) + " HP";
        ui.set("#BossHealthValue.TextSpans", Message.raw(hpText));
    }

    private record BossStatus(@Nonnull String name, float currentHealth, float maxHealth) {

        @Nullable
        private static BossStatus resolve(@Nullable RoomData bossRoom) {
            if (bossRoom == null) {
                return null;
            }

            BossStatus fallback = null;
            for (Ref<EntityStore> mobRef : bossRoom.getSpawnedMobs()) {
                if (mobRef == null || !mobRef.isValid()) {
                    continue;
                }

                Store<EntityStore> store = mobRef.getStore();
                NPCEntity npc = store.getComponent(mobRef, NPCEntity.getComponentType());
                EntityStatMap statMap = store.getComponent(mobRef, EntityStatMap.getComponentType());
                if (npc == null || statMap == null) {
                    continue;
                }

                int healthIdx = DefaultEntityStatTypes.getHealth();
                EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
                if (healthStat == null || healthStat.getMax() <= 0f) {
                    continue;
                }

                float currentHealth = Math.max(0f, healthStat.get());
                if (currentHealth <= (healthStat.getMin() + 0.001f)) {
                    continue;
                }
                float maxHealth = Math.max(1f, healthStat.getMax());
                String roleName = npc.getRoleName();
                BossStatus candidate = new BossStatus(resolveBossName(roleName), currentHealth, maxHealth);

                if (roleName != null && roleName.startsWith("Boss_")) {
                    return candidate;
                }

                if (fallback == null || candidate.maxHealth() > fallback.maxHealth()) {
                    fallback = candidate;
                }
            }

            return fallback;
        }

        @Nonnull
        private static String resolveBossName(@Nullable String roleName) {
            if (roleName == null || roleName.isBlank()) {
                return "Boss";
            }
            if (roleName.startsWith("Boss_Forklift")) {
                return "Forklift";
            }
            if (roleName.startsWith("Boss_Zombie_Commander")) {
                return "Zombie Commander";
            }

            String raw = roleName.startsWith("Boss_") ? roleName.substring("Boss_".length()) : roleName;
            String[] parts = raw.split("_");
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                if (part.length() > 1) {
                    builder.append(part.substring(1).toLowerCase(Locale.ROOT));
                }
            }
            return builder.length() > 0 ? builder.toString() : "Boss";
        }
    }
}