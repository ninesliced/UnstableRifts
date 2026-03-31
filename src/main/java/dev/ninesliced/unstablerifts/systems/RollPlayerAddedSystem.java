package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class RollPlayerAddedSystem extends HolderSystem<EntityStore> {

    @Nonnull
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;
    @Nonnull
    private final ComponentType<EntityStore, RollComponent> rollComponentType;
    @Nonnull
    private final Query<EntityStore> query;

    public RollPlayerAddedSystem(
            @Nonnull ComponentType<EntityStore, PlayerRef> playerRefComponentType,
            @Nonnull ComponentType<EntityStore, RollComponent> rollComponentType) {
        this.playerRefComponentType = playerRefComponentType;
        this.rollComponentType = rollComponentType;
        this.query = this.playerRefComponentType;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder,
                            @Nonnull AddReason reason,
                            @Nonnull Store<EntityStore> store) {
        holder.ensureComponent(this.rollComponentType);
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
