package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.dungeon.DungeonConstants;
import dev.ninesliced.unstablerifts.systems.DeathComponent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * HUD overlay displaying party member names, health bars, and one active effect.
 */
public final class PartyStatusHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/PartyStatus.ui";
    public static final String HUD_ID = "UnstableRiftsParty";

    private static final int MAX_DISPLAYED_MEMBERS = 8;
    private static final int PANEL_HEADER_HEIGHT = 20;
    private static final int PANEL_VERTICAL_PADDING = 16;
    private static final int FIRST_MEMBER_TOP = 4;
    private static final int MEMBER_ROW_HEIGHT = 32;
    private static final int MEMBER_SPACING = 2;
    /**
     * Width of the health bar background in the .ui file (parent element).
     */
    private static final int HEALTH_BAR_BG_WIDTH = 100;
    private static final int HEALTH_BAR_HEIGHT = 6;

    private final List<MemberStatus> members;

    public PartyStatusHud(@Nonnull PlayerRef playerRef, @Nonnull List<MemberStatus> members) {
        super(playerRef, HUD_ID);
        this.members = members;
    }

    /**
     * Applies the party status HUD.
     */
    public static void applyHud(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull PartyStatusHud hud) {
        if (HudVisibilityService.isHidden(playerRef.getUuid())) {
            return;
        }
        player.getHudManager().addCustomHud(playerRef, hud);
    }

    /**
     * Hides the party status HUD.
     */
    public static void hideHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        player.getHudManager().removeCustomHud(playerRef, HUD_ID);
    }

    private static void setHealthBarWidth(@Nonnull UICommandBuilder ui, @Nonnull String selector, int width) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(0));
        anchor.setTop(Value.of(0));
        anchor.setWidth(Value.of(Math.max(0, Math.min(HEALTH_BAR_BG_WIDTH, width))));
        anchor.setHeight(Value.of(HEALTH_BAR_HEIGHT));
        ui.setObject(selector + ".Anchor", anchor);
    }

    private static void setPanelHeight(@Nonnull UICommandBuilder ui, int height) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(200));
        anchor.setHeight(Value.of(Math.max(0, height)));
        ui.setObject("#PartyStatusPanel.Anchor", anchor);
    }

    private static int computePanelHeight(int displayedMembers) {
        int clamped = Math.max(0, Math.min(MAX_DISPLAYED_MEMBERS, displayedMembers));
        if (clamped == 0) {
            return PANEL_HEADER_HEIGHT + PANEL_VERTICAL_PADDING;
        }
        return PANEL_HEADER_HEIGHT
                + PANEL_VERTICAL_PADDING
                + FIRST_MEMBER_TOP
                + (clamped * MEMBER_ROW_HEIGHT)
                + (Math.max(0, clamped - 1) * MEMBER_SPACING);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#PartyStatusRoot.Visible", true);

        int displayed = Math.min(members.size(), MAX_DISPLAYED_MEMBERS);
        setPanelHeight(ui, computePanelHeight(displayed));
        for (int i = 0; i < MAX_DISPLAYED_MEMBERS; i++) {
            String prefix = "#Member" + i;
            if (i < displayed) {
                MemberStatus member = members.get(i);
                ui.set(prefix + ".Visible", true);
                ui.set(prefix + "Name.TextSpans", Message.raw(member.name));
                ui.set(prefix + "Status.TextSpans", Message.raw(member.statusText()));
                ui.set(prefix + "Status.Style.TextColor", member.statusColor());

                // ── Health bar ──
                if (member.dead || member.ghost) {
                    // Dead/ghost: empty health bar with appropriate color
                    setHealthBarWidth(ui, prefix + "HealthBar", 0);
                    ui.set(prefix + "HealthBar.Background",
                            member.ghost ? DungeonConstants.COLOR_GHOST : DungeonConstants.COLOR_CRITICAL);
                    ui.set(prefix + "Health.TextSpans", Message.raw(member.ghost ? "Ghost" : "Dead"));
                } else if (member.online && member.maxHealth > 0) {
                    float percent = Math.max(0f, Math.min(1f, member.currentHealth / member.maxHealth));
                    int fillWidth = Math.round(HEALTH_BAR_BG_WIDTH * percent);
                    setHealthBarWidth(ui, prefix + "HealthBar", fillWidth);
                    // Color the bar red when health is low (<30%)
                    ui.set(prefix + "HealthBar.Background",
                            percent < 0.3f ? DungeonConstants.COLOR_CRITICAL : DungeonConstants.COLOR_POSITIVE);
                    ui.set(prefix + "Health.TextSpans",
                            Message.raw(Math.round(member.currentHealth) + "/" + Math.round(member.maxHealth)));
                } else {
                    // Offline → collapse bar; Online but no stat data → full bar
                    setHealthBarWidth(ui, prefix + "HealthBar", member.online ? HEALTH_BAR_BG_WIDTH : 0);
                    ui.set(prefix + "HealthBar.Background", DungeonConstants.COLOR_POSITIVE);
                    ui.set(prefix + "Health.TextSpans", Message.raw(member.online ? "" : "—"));
                }

                // ── Active effect (first one found) ──
                if (member.effectName != null && !member.effectName.isEmpty()) {
                    ui.set(prefix + "Effect.Visible", true);
                    String effectLabel = member.effectIsDebuff ? "⬇ " : "⬆ ";
                    effectLabel += member.effectName;
                    if (!member.effectInfinite && member.effectRemainingSeconds > 0) {
                        effectLabel += " (" + member.effectRemainingSeconds + "s)";
                    }
                    ui.set(prefix + "Effect.TextSpans", Message.raw(effectLabel));
                    // Debuffs show reddish, buffs show purple
                    ui.set(prefix + "Effect.Style.TextColor",
                            member.effectIsDebuff ? DungeonConstants.COLOR_DEBUFF : DungeonConstants.COLOR_BUFF);
                } else {
                    ui.set(prefix + "Effect.Visible", false);
                }
            } else {
                ui.set(prefix + ".Visible", false);
            }
        }
    }

    /**
     * Snapshot of a party member's status including health and one active effect.
     */
    public record MemberStatus(
            @Nonnull String name,
            @Nonnull UUID uuid,
            boolean online,
            boolean dead,
            boolean ghost,
            float currentHealth,
            float maxHealth,
            @Nullable String effectName,
            boolean effectIsDebuff,
            boolean effectInfinite,
            int effectRemainingSeconds
    ) {
        /**
         * Builds a MemberStatus by resolving the player's ECS components for health
         * and one active effect. If the player is offline, health and effect are zeroed.
         */
        @Nonnull
        public static MemberStatus fromUuid(@Nonnull UUID uuid, @Nonnull String name,
                                            @Nonnull Set<UUID> deadPlayers) {
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            boolean online = playerRef != null && playerRef.isValid();

            float curHealth = 0f;
            float maxHealth = 0f;
            String effectName = null;
            boolean effectIsDebuff = false;
            boolean effectInfinite = false;
            int effectRemaining = 0;
            boolean isDead = false;
            boolean isGhost = false;

            if (online) {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null && ref.isValid()) {
                    Store<EntityStore> store = ref.getStore();

                    // ── Check death state ──
                    if (deadPlayers.contains(uuid)) {
                        isDead = true;
                        DeathComponent deathComp = store.getComponent(ref, DeathComponent.getComponentType());
                        if (deathComp != null) {
                            isGhost = deathComp.isGhost();
                        }
                    }

                    // ── Read health ──
                    try {
                        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
                        if (statMap != null) {
                            int healthIdx = DefaultEntityStatTypes.getHealth();
                            EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
                            if (healthStat != null) {
                                curHealth = healthStat.get();
                                maxHealth = Math.max(1f, healthStat.getMax());
                            }
                        }
                    } catch (Exception e) {
                        // Health stat may not be available for all entity types
                    }

                    // ── Read first active effect ──
                    try {
                        EffectControllerComponent effectController =
                                store.getComponent(ref, EffectControllerComponent.getComponentType());
                        if (effectController != null) {
                            ActiveEntityEffect[] activeEffects = effectController.getAllActiveEntityEffects();
                            if (activeEffects != null && activeEffects.length > 0) {
                                ActiveEntityEffect first = activeEffects[0];
                                int effectIndex = first.getEntityEffectIndex();
                                EntityEffect effectAsset = EntityEffect.getAssetMap().getAsset(effectIndex);
                                if (effectAsset != null) {
                                    String dispName = effectAsset.getName();
                                    effectName = (dispName != null && !dispName.isEmpty())
                                            ? dispName
                                            : effectAsset.getId();
                                }
                                effectIsDebuff = first.isDebuff();
                                effectInfinite = first.isInfinite();
                                effectRemaining = Math.max(0, Math.round(first.getRemainingDuration()));
                            }
                        }
                    } catch (Exception e) {
                        // Effect controller may not be available
                    }
                }
            }

            return new MemberStatus(name, uuid, online, isDead, isGhost,
                    curHealth, maxHealth,
                    effectName, effectIsDebuff, effectInfinite, effectRemaining);
        }

        /**
         * Convenience overload for contexts without death tracking.
         */
        @Nonnull
        public static MemberStatus fromUuid(@Nonnull UUID uuid, @Nonnull String name) {
            return fromUuid(uuid, name, Collections.emptySet());
        }

        @Nonnull
        public String statusText() {
            if (!online) return "Offline";
            if (ghost) return "Ghost";
            if (dead) return "Dead";
            return "Online";
        }

        @Nonnull
        public String statusColor() {
            if (!online) return DungeonConstants.COLOR_DANGER;
            if (ghost) return DungeonConstants.COLOR_GHOST;
            if (dead) return DungeonConstants.COLOR_DANGER;
            return DungeonConstants.COLOR_SUCCESS;
        }
    }
}
