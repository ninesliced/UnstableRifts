package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Ensures every player entity has a {@link DashComponent}
 * Using {@link HolderSystem} taking reference from
 * {@code RegisterTrackerSystem}
 */
public final class DashPlayerAddedSystem extends HolderSystem<EntityStore> {

    @Nonnull
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;

    @Nonnull
    private final ComponentType<EntityStore, DashComponent> dashComponentType;

    @Nonnull
    private final Query<EntityStore> query;

    public DashPlayerAddedSystem(
            @Nonnull ComponentType<EntityStore, PlayerRef> playerRefComponentType,
            @Nonnull ComponentType<EntityStore, DashComponent> dashComponentType) {
        this.playerRefComponentType = playerRefComponentType;
        this.dashComponentType = dashComponentType;
        this.query = this.playerRefComponentType;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store) {
        holder.ensureComponent(this.dashComponentType);
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
