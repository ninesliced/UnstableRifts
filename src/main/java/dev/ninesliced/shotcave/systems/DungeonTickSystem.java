package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.dungeon.Game;
import dev.ninesliced.shotcave.dungeon.GameManager;
import dev.ninesliced.shotcave.dungeon.GameState;
import dev.ninesliced.shotcave.dungeon.Level;
import dev.ninesliced.shotcave.dungeon.RoomData;
import dev.ninesliced.shotcave.dungeon.RoomType;
import dev.ninesliced.shotcave.hud.DungeonInfoHud;
import dev.ninesliced.shotcave.hud.PartyStatusHud;
import dev.ninesliced.shotcave.party.PartyManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ECS ticking system that drives the dungeon game logic for players in a dungeon world.
 * <ul>
 *   <li>Updates HUDs (dungeon info + party status)</li>
 *   <li>Detects boss room entry and triggers boss phase</li>
 *   <li>Tracks mob deaths and triggers boss defeat</li>
 *   <li>Awards money for kills</li>
 * </ul>
 */
public final class DungeonTickSystem extends EntityTickingSystem<EntityStore> {

    private static final double BOSS_ROOM_ENTER_DISTANCE = 30.0;
    private int tickCounter = 0;

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        tickCounter++;

        // Only process every 10 ticks (~200ms at 50 ticks/sec) for performance
        if (tickCounter % 10 != 0) return;

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null || !playerRef.isValid()) return;

        Shotcave shotcave = Shotcave.getInstance();
        if (shotcave == null) return;

        GameManager gameManager = shotcave.getGameManager();
        Game game = gameManager.findGameForPlayer(playerRef.getUuid());
        if (game == null) {
            shotcave.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            return;
        }

        // Only process if the game is in an active state
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS) {
            shotcave.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            return;
        }

        if (game.getInstanceWorld() == null || player.getWorld() != game.getInstanceWorld()) {
            shotcave.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            return;
        }

        DungeonConfig config = shotcave.loadDungeonConfig();

        // ── Update HUDs (every 20 ticks = ~400ms) ──
        if (tickCounter % 20 == 0) {
            updateHuds(player, playerRef, game, config, shotcave);
        }

        // ── Game logic ──
        Level level = game.getCurrentLevel();
        if (level == null) return;

        // Track mob deaths and award money
        trackMobDeaths(game, level, config);

        // Detect boss room proximity
        if (game.getState() == GameState.ACTIVE) {
            checkBossRoomEntry(player, ref, store, game, level, gameManager);
        }

        // Detect boss defeat
        if (game.getState() == GameState.BOSS) {
            RoomData bossRoom = level.getBossRoom();
            if (bossRoom != null && bossRoom.areAllMobsDead() && !bossRoom.getSpawnedMobs().isEmpty()) {
                bossRoom.setCleared(true);
                gameManager.onBossDefeated(game);
            }
        }
    }

    private void updateHuds(@Nonnull Player player, @Nonnull PlayerRef playerRef,
                            @Nonnull Game game, @Nonnull DungeonConfig config,
                            @Nonnull Shotcave shotcave) {
        // Dungeon Info HUD
        DungeonInfoHud infoHud = DungeonInfoHud.fromGame(playerRef, game, config.getDungeonName());
        DungeonInfoHud.applyHud(player, playerRef, infoHud);

        // Party Status HUD
        PartyManager partyManager = shotcave.getPartyManager();
        PartyManager.PartySnapshot snapshot = partyManager.getPartySnapshot(playerRef.getUuid());
        if (snapshot != null && !snapshot.members().isEmpty()) {
            List<PartyStatusHud.MemberStatus> members = new ArrayList<>();
            for (PartyManager.PartyMemberSnapshot member : snapshot.members()) {
                members.add(PartyStatusHud.MemberStatus.fromUuid(
                        UUID.fromString(member.id()), member.name()));
            }
            PartyStatusHud partyHud = new PartyStatusHud(playerRef, members);
            PartyStatusHud.applyHud(player, playerRef, partyHud);
        } else {
            PartyStatusHud.hideHud(player, playerRef);
        }
    }

    private void trackMobDeaths(@Nonnull Game game, @Nonnull Level level, @Nonnull DungeonConfig config) {
        DungeonConfig.LevelConfig levelConfig = null;
        if (game.getCurrentLevelIndex() < config.getLevels().size()) {
            levelConfig = config.getLevels().get(game.getCurrentLevelIndex());
        }

        int moneyPerKill = levelConfig != null ? levelConfig.getMoneyPerKill() : 10;

        for (RoomData room : level.getRooms()) {
            if (room.isCleared() || room.getType() == RoomType.BOSS) continue;

            int prevAlive = room.getAliveMobCount();
            // Count invalid refs to detect newly dead mobs
            int newlyDead = 0;
            for (var mob : room.getSpawnedMobs()) {
                if (!mob.isValid()) {
                    newlyDead++;
                }
            }

            // Simple approach: if all mobs are dead, mark cleared and award remaining money
            if (room.areAllMobsDead() && !room.getSpawnedMobs().isEmpty() && !room.isCleared()) {
                room.setCleared(true);
                // Money was already awarded per-kill via tracking
            }
        }

        // Award money for overall kill tracking
        // We track via the total alive count change
        int currentAlive = level.getAliveMobCount();
        int totalSpawned = level.getTotalSpawnedMobs();
        int killed = totalSpawned - currentAlive;
        long expectedMoney = (long) killed * moneyPerKill;
        long moneyToAdd = expectedMoney - game.getMoney();
        if (moneyToAdd > 0) {
            game.addMoney(moneyToAdd);
        }
    }

    private void checkBossRoomEntry(@Nonnull Player player, @Nonnull Ref<EntityStore> ref,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull Game game, @Nonnull Level level,
                                    @Nonnull GameManager gameManager) {
        RoomData bossRoom = level.getBossRoom();
        if (bossRoom == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3i bossAnchor = bossRoom.getAnchor();
        double dx = transform.getPosition().x - bossAnchor.x;
        double dy = transform.getPosition().y - bossAnchor.y;
        double dz = transform.getPosition().z - bossAnchor.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq < BOSS_ROOM_ENTER_DISTANCE * BOSS_ROOM_ENTER_DISTANCE) {
            gameManager.enterBossPhase(game);
        }
    }
}


