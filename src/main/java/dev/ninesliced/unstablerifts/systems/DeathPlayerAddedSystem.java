package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Ensures every player entity has a {@link DeathComponent}.
 */
public final class DeathPlayerAddedSystem extends HolderSystem<EntityStore> {

    @Nonnull
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;

    @Nonnull
    private final ComponentType<EntityStore, DeathComponent> deathComponentType;

    @Nonnull
    private final Query<EntityStore> query;

    public DeathPlayerAddedSystem(
            @Nonnull ComponentType<EntityStore, PlayerRef> playerRefComponentType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathComponentType) {
        this.playerRefComponentType = playerRefComponentType;
        this.deathComponentType = deathComponentType;
        this.query = this.playerRefComponentType;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder,
                            @Nonnull AddReason reason,
                            @Nonnull Store<EntityStore> store) {
        holder.ensureComponent(this.deathComponentType);
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder,
                                @Nonnull RemoveReason reason,
                                @Nonnull Store<EntityStore> store) {
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
