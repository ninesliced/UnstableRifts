package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.dungeon.ChallengeObjective;
import dev.ninesliced.unstablerifts.dungeon.RoomData;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * HUD overlay displaying active challenge objectives with completion checkboxes.
 */
public final class ChallengeHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/Challenge.ui";
    public static final String HUD_ID = "UnstableRiftsChallenge";
    private static final int MAX_OBJECTIVES = 5;

    private final List<ChallengeObjective> objectives;
    private final int mobsRemaining;
    private final boolean hasMobClear;

    public ChallengeHud(@Nonnull PlayerRef playerRef,
                        @Nonnull List<ChallengeObjective> objectives,
                        int mobsRemaining,
                        boolean hasMobClear) {
        super(playerRef);
        this.objectives = objectives;
        this.mobsRemaining = mobsRemaining;
        this.hasMobClear = hasMobClear;
    }

    @Nonnull
    public static ChallengeHud fromRoom(@Nonnull PlayerRef playerRef, @Nonnull RoomData room) {
        List<ChallengeObjective> objectives = room.getChallenges();
        boolean hasMobClear = objectives.stream()
                .anyMatch(o -> o.getType() == ChallengeObjective.Type.MOB_CLEAR);
        int mobsRemaining = room.getAliveMobCount();
        return new ChallengeHud(playerRef, objectives, mobsRemaining, hasMobClear);
    }

    public static void applyHud(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull ChallengeHud hud) {
        if (!MultiHudCompat.setHud(player, playerRef, HUD_ID, hud)) {
            // Fallback: cannot set secondary HUD without MultipleHUD
        }
    }

    public static void hideHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        MultiHudCompat.hideHud(player, playerRef, HUD_ID);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);

        ui.set("#ChallengeRoot.Visible", true);

        for (int i = 0; i < MAX_OBJECTIVES; i++) {
            String objGroup = "#Obj" + i;
            if (i < objectives.size()) {
                ChallengeObjective obj = objectives.get(i);
                ui.set(objGroup + ".Visible", true);
                ui.set(objGroup + "Check.TextSpans",
                        Message.raw(obj.isCompleted() ? "[X]" : "[ ]"));
                ui.set(objGroup + "Text.TextSpans",
                        Message.raw(obj.getDisplayName()));
            } else {
                ui.set(objGroup + ".Visible", false);
            }
        }

        // Mob counter row.
        if (hasMobClear) {
            ui.set("#MobCounterRow.Visible", true);
            ui.set("#MobCounterLabel.TextSpans", Message.raw(String.valueOf(mobsRemaining)));
        } else {
            ui.set("#MobCounterRow.Visible", false);
        }
    }
}
