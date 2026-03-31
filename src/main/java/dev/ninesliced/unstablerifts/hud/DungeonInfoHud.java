package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.Level;

import javax.annotation.Nonnull;

/**
 * HUD overlay displaying dungeon information:
 * dungeon name, current level, money, game time, and mob count.
 */
public final class DungeonInfoHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/DungeonInfo.ui";
    public static final String HUD_ID = "UnstableRiftsDungeon";

    private final String dungeonName;
    private final String levelName;
    private final long money;
    private final String gameTime;
    private final int mobsAlive;
    private final int mobsTotal;

    public DungeonInfoHud(@Nonnull PlayerRef playerRef,
                          @Nonnull String dungeonName,
                          @Nonnull String levelName,
                          long money,
                          @Nonnull String gameTime,
                          int mobsAlive,
                          int mobsTotal) {
        super(playerRef);
        this.dungeonName = dungeonName;
        this.levelName = levelName;
        this.money = money;
        this.gameTime = gameTime;
        this.mobsAlive = mobsAlive;
        this.mobsTotal = mobsTotal;
    }

    /**
     * Creates an updated HUD from the current game state.
     */
    @Nonnull
    public static DungeonInfoHud fromGame(@Nonnull PlayerRef playerRef,
                                          @Nonnull Game game,
                                          @Nonnull String dungeonName) {
        Level level = game.getCurrentLevel();
        String levelName = level != null ? level.getName() : "Unknown";
        int mobsAlive = level != null ? level.getAliveMobCount() : 0;
        int mobsTotal = level != null ? level.getTotalSpawnedMobs() : 0;

        return new DungeonInfoHud(
                playerRef,
                dungeonName,
                levelName,
                game.getMoney(),
                Game.formatTime(game.getElapsedGameTime()),
                mobsAlive,
                mobsTotal
        );
    }

    /**
     * Applies the HUD update to a player, using MultiHudCompat if available.
     */
    public static void applyHud(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull DungeonInfoHud hud) {
        if (!MultiHudCompat.setHud(player, playerRef, HUD_ID, hud)) {
            // Fallback: cannot set secondary HUD without MultipleHUD, skip to avoid replacing ammo HUD
        }
    }

    /**
     * Hides the dungeon info HUD.
     */
    public static void hideHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        MultiHudCompat.hideHud(player, playerRef, HUD_ID);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);

        ui.set("#DungeonInfoRoot.Visible", true);
        ui.set("#DungeonNameLabel.TextSpans", Message.raw(dungeonName));
        ui.set("#LevelNameLabel.TextSpans", Message.raw(levelName));
        ui.set("#MoneyLabel.TextSpans", Message.raw(String.valueOf(money)));
        ui.set("#GameTimeLabel.TextSpans", Message.raw(gameTime));
        ui.set("#MobCountLabel.TextSpans", Message.raw(mobsAlive + " / " + mobsTotal));
    }
}

