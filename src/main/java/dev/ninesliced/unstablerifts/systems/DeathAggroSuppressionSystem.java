package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import dev.ninesliced.unstablerifts.UnstableRifts;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps custom-dead players out of NPC aggro systems that only understand the engine death marker.
 *
 * <p>The engine's {@code TargetMemorySystems.isValidTarget()} only checks for the engine-level
 * death component, which is never added because UnstableRifts intercepts lethal damage. This system
 * bridges that gap by overriding attitude to IGNORE and purging dead-player entries from every
 * NPC's target memory, marked-entity slots, and active tasks.</p>
 *
 * <p>Target memory entries are removed by iterating the hostile/friendly lists and checking each
 * entry for UnstableRifts's {@link DeathComponent} rather than matching by {@code Ref} identity,
 * because component additions (Intangible, Invulnerable) can migrate the entity to a new
 * archetype and change its index.</p>
 */
public final class DeathAggroSuppressionSystem extends EntityTickingSystem<EntityStore> {

    private static final double AGGRO_OVERRIDE_SECONDS = 5.0d;

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts == null) {
            return;
        }

        List<Ref<EntityStore>> deadPlayerRefs = unstablerifts.getGameManager().getDeadPlayerRefsInStore(store);
        if (deadPlayerRefs.isEmpty()) {
            return;
        }

        NPCEntity npcEntity = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return;
        }

        Role role = npcEntity.getRole();
        if (role == null) {
            return;
        }

        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        if (!npcRef.isValid()) {
            return;
        }

        // Collect dead player indices for fast lookup during list iteration.
        Set<Integer> deadIndices = new HashSet<>();
        for (Ref<EntityStore> deadPlayerRef : deadPlayerRefs) {
            if (deadPlayerRef == null || !deadPlayerRef.isValid() || deadPlayerRef.equals(npcRef)) {
                continue;
            }
            deadIndices.add(deadPlayerRef.getIndex());
            role.getWorldSupport().overrideAttitude(deadPlayerRef, Attitude.IGNORE, AGGRO_OVERRIDE_SECONDS);
        }

        if (deadIndices.isEmpty()) {
            return;
        }

        TargetMemory targetMemory = store.getComponent(npcRef, TargetMemory.getComponentType());
        purgeDeadFromTargetMemory(targetMemory, store, deadIndices);
        purgeDeadFromMarkedTargets(role, store, deadIndices);
    }

    /**
     * Removes dead players from the NPC's target memory by scanning each entry and checking for
     * death state. This avoids reliance on Ref identity which breaks across archetype migrations.
     */
    private void purgeDeadFromTargetMemory(TargetMemory targetMemory,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull Set<Integer> deadIndices) {
        if (targetMemory == null) {
            return;
        }

        // --- Hostiles ---
        Int2FloatOpenHashMap hostiles = targetMemory.getKnownHostiles();
        List<Ref<EntityStore>> hostilesList = targetMemory.getKnownHostilesList();
        for (int i = hostilesList.size() - 1; i >= 0; i--) {
            Ref<EntityStore> ref = hostilesList.get(i);
            if (ref == null || !ref.isValid() || isDeadPlayer(ref, store, deadIndices)) {
                if (ref != null) {
                    hostiles.remove(ref.getIndex());
                }
                hostilesList.remove(i);
            }
        }

        // --- Friendlies ---
        Int2FloatOpenHashMap friendlies = targetMemory.getKnownFriendlies();
        List<Ref<EntityStore>> friendliesList = targetMemory.getKnownFriendliesList();
        for (int i = friendliesList.size() - 1; i >= 0; i--) {
            Ref<EntityStore> ref = friendliesList.get(i);
            if (ref == null || !ref.isValid() || isDeadPlayer(ref, store, deadIndices)) {
                if (ref != null) {
                    friendlies.remove(ref.getIndex());
                }
                friendliesList.remove(i);
            }
        }

        // Also remove dead index keys that might be orphaned (old index after migration).
        for (int deadIdx : deadIndices) {
            hostiles.remove(deadIdx);
            friendlies.remove(deadIdx);
        }

        // --- Closest hostile ---
        Ref<EntityStore> closest = targetMemory.getClosestHostile();
        if (closest != null) {
            if (!closest.isValid() || isDeadPlayer(closest, store, deadIndices)) {
                targetMemory.setClosestHostile(null);
            }
        }
    }

    private void purgeDeadFromMarkedTargets(@Nonnull Role role,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull Set<Integer> deadIndices) {
        boolean clearedAny = false;
        int slotCount = role.getMarkedEntitySupport().getMarkedEntitySlotCount();
        for (int slot = 0; slot < slotCount; slot++) {
            Ref<EntityStore> markedRef = role.getMarkedEntitySupport().getMarkedEntityRef(slot);
            if (markedRef != null && (isDeadPlayer(markedRef, store, deadIndices) || !markedRef.isValid())) {
                role.getMarkedEntitySupport().clearMarkedEntity(slot);
                clearedAny = true;
            }
        }

        if (clearedAny) {
            role.getEntitySupport().clearTargetPlayerActiveTasks();
        }
    }

    /**
     * Returns {@code true} if the given ref belongs to a UnstableRifts-dead player.
     * Checks both the known dead-index set and the component directly.
     */
    private boolean isDeadPlayer(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Set<Integer> deadIndices) {
        if (deadIndices.contains(ref.getIndex())) {
            return true;
        }
        DeathComponent deathComp = store.getComponent(ref, DeathComponent.getComponentType());
        return deathComp != null && deathComp.isDead();
    }
}