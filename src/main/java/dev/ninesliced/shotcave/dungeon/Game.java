package dev.ninesliced.shotcave.dungeon;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central runtime state for a single dungeon run tied to a party.
 */
public final class Game {

    private final UUID partyId;
    private final List<Level> levels = new ArrayList<>();
    /**
     * Saved inventory data per player UUID — file path on disk for crash-safe restore.
     */
    private final Map<UUID, String> savedInventoryPaths = new HashMap<>();
    /**
     * Return points per player UUID for teleporting back after the run.
     */
    private final Map<UUID, Transform> returnPoints = new HashMap<>();
    /**
     * Players currently inside the active dungeon instance.
     */
    private final Set<UUID> playersInInstance = new HashSet<>();
    private int currentLevelIndex = 0;
    private long money = 0L;
    private long startTime;
    private long levelStartTime;
    private GameState state = GameState.GENERATING;
    /**
     * The world name of the dungeon instance for this run.
     */
    @Nullable
    private World instanceWorld;
    /**
     * Generation future for async tracking.
     */
    @Nullable
    private CompletableFuture<World> generationFuture;
    /**
     * Generation progress 0.0–1.0
     */
    private volatile float generationProgress = 0.0f;
    /**
     * Whether the boss room has been sealed.
     */
    private boolean bossRoomSealed = false;

    public Game(@Nonnull UUID partyId) {
        this.partyId = partyId;
        this.startTime = System.currentTimeMillis();
        this.levelStartTime = this.startTime;
    }

    /**
     * Formats elapsed time as mm:ss.
     */
    @Nonnull
    public static String formatTime(long millis) {
        long totalSeconds = millis / 1000L;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Nonnull
    public UUID getPartyId() {
        return partyId;
    }

    @Nonnull
    public List<Level> getLevels() {
        return Collections.unmodifiableList(levels);
    }

    public void addLevel(@Nonnull Level level) {
        levels.add(level);
    }

    public void setLevel(int index, @Nonnull Level level) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index must be >= 0");
        }
        if (index < levels.size()) {
            levels.set(index, level);
            return;
        }
        if (index == levels.size()) {
            levels.add(level);
            return;
        }
        throw new IndexOutOfBoundsException("Cannot skip level slots when setting index " + index);
    }

    public int getCurrentLevelIndex() {
        return currentLevelIndex;
    }

    public void setCurrentLevelIndex(int currentLevelIndex) {
        this.currentLevelIndex = currentLevelIndex;
        this.levelStartTime = System.currentTimeMillis();
    }

    @Nullable
    public Level getCurrentLevel() {
        if (currentLevelIndex >= 0 && currentLevelIndex < levels.size()) {
            return levels.get(currentLevelIndex);
        }
        return null;
    }

    public boolean hasNextLevel() {
        return currentLevelIndex + 1 < levels.size();
    }

    public long getMoney() {
        return money;
    }

    public void setMoney(long money) {
        this.money = money;
    }

    public void addMoney(long amount) {
        this.money += amount;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getLevelStartTime() {
        return levelStartTime;
    }

    public void setLevelStartTime(long levelStartTime) {
        this.levelStartTime = levelStartTime;
    }

    /**
     * Elapsed game time in milliseconds.
     */
    public long getElapsedGameTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Elapsed level time in milliseconds.
     */
    public long getElapsedLevelTime() {
        return System.currentTimeMillis() - levelStartTime;
    }

    @Nonnull
    public GameState getState() {
        return state;
    }

    public void setState(@Nonnull GameState state) {
        this.state = state;
    }

    @Nonnull
    public Map<UUID, String> getSavedInventoryPaths() {
        return savedInventoryPaths;
    }

    @Nonnull
    public Map<UUID, Transform> getReturnPoints() {
        return returnPoints;
    }

    public boolean isPlayerInInstance(@Nonnull UUID playerId) {
        return playersInInstance.contains(playerId);
    }

    public void setPlayerInInstance(@Nonnull UUID playerId, boolean inInstance) {
        if (inInstance) {
            playersInInstance.add(playerId);
        } else {
            playersInInstance.remove(playerId);
        }
    }

    @Nonnull
    public Set<UUID> getPlayersInInstance() {
        return Collections.unmodifiableSet(playersInInstance);
    }

    @Nullable
    public World getInstanceWorld() {
        return instanceWorld;
    }

    public void setInstanceWorld(@Nullable World instanceWorld) {
        this.instanceWorld = instanceWorld;
    }

    @Nullable
    public CompletableFuture<World> getGenerationFuture() {
        return generationFuture;
    }

    public void setGenerationFuture(@Nullable CompletableFuture<World> generationFuture) {
        this.generationFuture = generationFuture;
    }

    public float getGenerationProgress() {
        return generationProgress;
    }

    public void setGenerationProgress(float generationProgress) {
        this.generationProgress = Math.max(0.0f, Math.min(1.0f, generationProgress));
    }

    public boolean isBossRoomSealed() {
        return bossRoomSealed;
    }

    public void setBossRoomSealed(boolean bossRoomSealed) {
        this.bossRoomSealed = bossRoomSealed;
    }
}

