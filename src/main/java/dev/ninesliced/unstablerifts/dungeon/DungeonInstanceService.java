package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.logging.UnstableRiftsLog;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DungeonInstanceService {

    public static final String DEFAULT_LEVEL_SELECTOR = "unstablerifts";
    public static final String DUNGEON_WEATHER = "UnstableRifts_Dungeon";
    private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Dungeon");
    private static final String INSTANCE_TEMPLATE = "UnstableRifts";
    private final UnstableRifts plugin;
    /**
     * The generator used for the last spawn, kept so the generated Level can be retrieved.
     */
    @Nullable
    private DungeonGenerator lastGenerator;

    public DungeonInstanceService(@Nonnull UnstableRifts plugin) {
        this.plugin = plugin;
    }

    @Nonnull
    public static Transform captureReturnPoint(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        return transformComponent != null ? transformComponent.getTransform().clone() : new Transform(0, 100, 0);
    }

    @Nullable
    public DungeonConfig.LevelConfig resolveLevel(@Nullable String selector) {
        DungeonConfig config = this.plugin.loadDungeonConfig();
        if (config.getLevels().isEmpty()) {
            return null;
        }
        if (selector != null && !selector.isBlank()) {
            return config.findLevel(selector);
        }

        DungeonConfig.LevelConfig defaultLevel = config.findLevel(DEFAULT_LEVEL_SELECTOR);
        return defaultLevel != null ? defaultLevel : config.getLevels().get(0);
    }

    @Nonnull
    public CompletableFuture<World> spawnGeneratedInstance(@Nonnull World currentWorld,
                                                           @Nonnull Transform returnPoint,
                                                           @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                           @Nullable Consumer<String> statusConsumer,
                                                           @Nullable Consumer<Float> progressConsumer,
                                                           @Nullable MobSpawningService mobSpawningService) {
        InstancesPlugin instancesPlugin = InstancesPlugin.get();
        CompletableFuture<World> worldFuture = instancesPlugin.spawnInstance(INSTANCE_TEMPLATE, currentWorld,
                returnPoint);

        return worldFuture.thenCompose(world -> {
            CompletableFuture<World> generationFuture = new CompletableFuture<>();
            world.execute(() -> {
                try {
                    long seed = System.nanoTime();
                    DungeonGenerator generator = new DungeonGenerator();
                    generator.setMobSpawningService(mobSpawningService);
                    generator.setProgressConsumer(progressConsumer);
                    generator.generate(world, seed, levelConfig);
                    if (generator.getGeneratedLevel() == null) {
                        throw new IllegalStateException("Dungeon generation produced no level for '" + levelConfig.getSelector() + "'.");
                    }
                    this.lastGenerator = generator;
                    applyDungeonWorldSettings(world);
                    LOGGER.at(Level.INFO).log("Dungeon instance created: " + world.getName() + " for selector " + levelConfig.getSelector());
                    if (statusConsumer != null) {
                        statusConsumer.accept("Dungeon ready: " + levelConfig.getName());
                    }
                    generationFuture.complete(world);
                } catch (Exception e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Dungeon generation failed");
                    if (statusConsumer != null) {
                        statusConsumer.accept("Dungeon generation had errors.");
                    }
                    generationFuture.completeExceptionally(e);
                }
            });
            return generationFuture;
        });
    }

    /**
     * Returns the Level generated by the last call to {@link #spawnGeneratedInstance}.
     */
    @Nullable
    public dev.ninesliced.unstablerifts.dungeon.Level getLastGeneratedLevel() {
        return lastGenerator != null ? lastGenerator.getGeneratedLevel() : null;
    }

    /**
     * Returns the rooms whose mobs were pre-spawned during the last generation.
     */
    @Nullable
    public java.util.Set<RoomData> getLastPreSpawnedRooms() {
        return lastGenerator != null ? lastGenerator.getPreSpawnedRooms() : null;
    }

    /**
     * Generates a level into an already-existing world at the given origin offset.
     * Used for background generation of upcoming levels in the same instance.
     *
     * @param world      the existing dungeon world
     * @param levelConfig the level configuration to generate
     * @param origin     world position where the spawn room is placed
     * @param levelIndex the index of this level within the dungeon run
     * @return a future that completes with the generated Level
     */
    @Nonnull
    public CompletableFuture<dev.ninesliced.unstablerifts.dungeon.Level> generateLevelInWorld(
            @Nonnull World world,
            @Nonnull DungeonConfig.LevelConfig levelConfig,
            @Nonnull Vector3i origin,
            int levelIndex,
            @Nullable Consumer<Float> progressConsumer) {
        CompletableFuture<dev.ninesliced.unstablerifts.dungeon.Level> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                long seed = System.nanoTime();
                DungeonGenerator generator = new DungeonGenerator();
                generator.setProgressConsumer(progressConsumer);
                generator.generate(world, seed, levelConfig, origin, levelIndex);
                dev.ninesliced.unstablerifts.dungeon.Level generated = generator.getGeneratedLevel();
                if (generated != null) {
                    LOGGER.at(Level.INFO).log("Background generated level '%s' (index %d) at origin %s with %d rooms",
                            levelConfig.getName(), levelIndex, origin, generated.getRooms().size());
                    future.complete(generated);
                } else {
                    LOGGER.at(Level.WARNING).log("Background generation produced no level for '%s' (index %d)",
                            levelConfig.getName(), levelIndex);
                    future.completeExceptionally(new IllegalStateException("Generation produced no level"));
                }
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Background level generation failed for '%s' (index %d)",
                        levelConfig.getName(), levelIndex);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void applyDungeonWorldSettings(@Nonnull World world) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        WeatherResource weatherResource = store.getResource(WeatherResource.getResourceType());
        weatherResource.setForcedWeather(DUNGEON_WEATHER);
        world.getWorldConfig().setForcedWeather(DUNGEON_WEATHER);
        world.getWorldConfig().setFallDamageEnabled(false);
    }

    public void sendPlayerToReadyInstance(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull CompletableFuture<World> readyFuture,
                                          @Nullable Transform returnPoint,
                                          @Nullable Consumer<String> failureConsumer) {
        World currentWorld = store.getExternalData().getWorld();
        readyFuture.whenComplete((targetWorld, throwable) -> {
            if (throwable != null) {
                LOGGER.at(Level.WARNING).withCause(throwable).log("Failed to prepare dungeon instance");
                if (failureConsumer != null) {
                    failureConsumer.accept("Dungeon creation failed.");
                }
                return;
            }

            currentWorld.execute(() -> {
                if (!ref.isValid()) {
                    return;
                }

                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) {
                    return;
                }

                try {
                    InstancesPlugin.teleportPlayerToInstance(ref, store, targetWorld, returnPoint);
                } catch (Exception exception) {
                    LOGGER.at(Level.WARNING).withCause(exception)
                            .log("Failed to send player to ready dungeon instance");
                    if (failureConsumer != null) {
                        failureConsumer.accept("Teleport to dungeon failed.");
                    }
                }
            });
        });
    }
}
