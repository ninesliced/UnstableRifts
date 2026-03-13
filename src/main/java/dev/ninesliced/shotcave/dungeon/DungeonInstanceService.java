package dev.ninesliced.shotcave.dungeon;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.ShotcaveLog;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DungeonInstanceService {

    public static final String DEFAULT_LEVEL_SELECTOR = "shotcave";

    private static final HytaleLogger LOGGER = ShotcaveLog.forModule("Dungeon");
    private static final String INSTANCE_TEMPLATE = "Shotcave";

    private final Shotcave plugin;

    public DungeonInstanceService(@Nonnull Shotcave plugin) {
        this.plugin = plugin;
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
            @Nullable Consumer<String> statusConsumer) {
        InstancesPlugin instancesPlugin = InstancesPlugin.get();
        CompletableFuture<World> worldFuture = instancesPlugin.spawnInstance(INSTANCE_TEMPLATE, currentWorld,
                returnPoint);

        return worldFuture.thenCompose(world -> {
            CompletableFuture<World> generationFuture = new CompletableFuture<>();
            world.execute(() -> {
                try {
                    long seed = System.nanoTime();
                    new DungeonGenerator().generate(world, seed, levelConfig);
                    LOGGER.at(Level.INFO).log("Dungeon instance created: %s for selector %s", world.getName(),
                            levelConfig.getSelector());
                    if (statusConsumer != null) {
                        statusConsumer.accept("Dungeon ready: " + levelConfig.getName());
                    }
                    generationFuture.complete(world);
                } catch (Exception e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Dungeon generation failed");
                    if (statusConsumer != null) {
                        statusConsumer.accept("Dungeon generation had errors.");
                    }
                    generationFuture.complete(world);
                }
            });
            return generationFuture;
        });
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

    @Nonnull
    public static Transform captureReturnPoint(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        return transformComponent != null ? transformComponent.getTransform().clone() : new Transform(0, 100, 0);
    }
}
