---
title: Dungeon Levels
description: Level layouts, room types, and mob pools in Unstable Rifts
---

<style>
:root { --ur-accent: #bb2f2c; --ur-border: #2a2a4a; --ur-text-muted: #8892a4; }
.ur-gallery { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; } .ur-gallery figure { flex: 1 1 200px; max-width: 320px; margin: 0; text-align: center; } .ur-gallery figure img { width: 100%; border-radius: 6px; border: 1px solid var(--ur-border); } .ur-gallery figure figcaption { font-size: 0.82em; color: var(--ur-text-muted); margin-top: 4px; }
.ur-columns { display: flex; gap: 24px; flex-wrap: wrap; } .ur-columns > div { flex: 1; min-width: 280px; }
.ur-info-card { border-left: 3px solid var(--ur-accent); padding: 10px 16px; margin: 14px 0; background: rgba(187,47,44,0.05); border-radius: 0 4px 4px 0; }
</style>

# Dungeon Levels

The dungeon is procedurally generated each run. Rooms connect via corridors, with branching paths, locked doors, and special rooms scattered throughout. No two runs are the same.

<div class="ur-gallery">
<figure>

![Dungeon Generation Example](images/dungeon_generation.png)
<figcaption>Example of a procedurally generated dungeon layout</figcaption>
</figure>
<figure>

![Dungeon Map View](images/dungeon_map.png)
<figcaption>In-game dungeon map showing explored rooms</figcaption>
</figure>
</div>

---

## Level 1: Kweebec

A corrupted forest overrun by twisted DeadWood creatures and radioactive wildlife.

<div class="ur-gallery">
<figure>

![Kweebec Entrance](images/kweebec_entrance.png)
<figcaption>The Kweebec level entrance room</figcaption>
</figure>
<figure>

![Kweebec Combat](images/kweebec_combat.png)
<figcaption>Fighting DeadWood mobs in the Kweebec level</figcaption>
</figure>
</div>

### Layout

| Property | Value |
|----------|-------|
| Main Path Rooms | 5 |
| Challenge Rooms | 2 |
| Treasure Rooms | 1 |
| Shop Rooms | 1 |
| Branch Paths | 2 |
| Rooms per Branch | 1 |
| Total Mobs (main) | ~250 |
| Total Mobs (branches) | ~60 |

### Mob Pool

| Enemy | Spawn Weight | Notes |
|-------|-------------|-------|
| DeadWood Rootling | 10 | Basic melee enemy |
| DeadWood Rootling (Sword) | 5 | Armed variant |
| DeadWood Rootling (Axe) | 5 | Armed variant |
| DeadWood Rootling (Lance) | 5 | Armed variant |
| DeadWood Sproutling | 10 | Small, fast enemy |
| DeadWood Sproutling (Sword) | 5 | Armed variant |
| DeadWood Sproutling (Axe) | 5 | Armed variant |
| DeadWood Sproutling (Lance) | 5 | Armed variant |
| DeadWood Seedling | 4 | Larger DeadWood enemy |
| Kweebec Seedling | 3 | Explosive Kweebec |
| Radioactive Wolf | 5 | Fast pack animal |
| Industrial Nosuit | 3 | Unarmored worker |

**Total Weight:** 65. Each mob's spawn chance = its weight / 65.

### Decorative Props

The Kweebec level includes themed environmental props:

| Block | Description |
|-------|-------------|
| Ruined Kweebec Bed | Damaged Kweebec furniture |
| Ruined Kweebec Candle | Extinguished candle |
| Ruined Kweebec Chest | Broken storage chest |
| Ruined Kweebec Door | Damaged doorway |
| Ruined Kweebec Lantern | Shattered lantern |
| Ruined Kweebec Plush | Tattered plush toy |
| Ruined Kweebec Sign | Weathered sign post |
| Ruined Kweebec Statue | Crumbled statue |
| Ruined Kweebec Stool | Broken stool |
| Ruined Kweebec Table | Damaged table |
| Ruined Kweebec Wardrobe | Ruined wardrobe |

<div class="ur-gallery">
<figure>

![Kweebec Props](images/props/kweebec_props.png)
<figcaption>Ruined Kweebec furniture props in a dungeon room</figcaption>
</figure>
<figure>

![Radioactive Barrels](images/props/radioactive_barrels.png)
<figcaption>Radioactive barrels -- breakable environmental hazards</figcaption>
</figure>
</div>

---

## Level 2: Desert

An arid industrial wasteland with hazardous enemies and tougher encounters.

<div class="ur-gallery">
<figure>

![Desert Entrance](images/desert_entrance.png)
<figcaption>The Desert level entrance room</figcaption>
</figure>
<figure>

![Desert Combat](images/desert_combat.png)
<figcaption>Fighting industrial enemies in the Desert level</figcaption>
</figure>
</div>

### Layout

| Property | Value |
|----------|-------|
| Main Path Rooms | 6 |
| Challenge Rooms | 2 |
| Treasure Rooms | 0 |
| Shop Rooms | 1 |
| Branch Paths | 1 |
| Rooms per Branch | 2 |
| Challenge per Branch | 1 |
| Total Mobs (main) | ~400 |
| Total Mobs (branches) | ~50-100 |

### Additional Enemy

| Enemy | Spawn Weight | Notes |
|-------|-------------|-------|
| Industrial Hazmat (Toxic Launcher) | 3 | Ranged toxic projectile attacker |

All Kweebec level enemies also appear in the Desert level with the same weights. Desert branches are longer and contain their own challenge rooms.

---

## Room Types

<div class="ur-columns">
<div>

### Gameplay Rooms

| Room Type | Description |
|-----------|-------------|
| Spawn | Starting room for each level |
| Corridor | Connecting passages between rooms |
| Challenge | Combat arenas with reward triggers |
| Treasure | High-value loot behind locked doors |
| Shop | Safe room with a shopkeeper NPC |
| Boss | Final room with the level boss |
| Branch | Side path rooms off the main path |
| Wall | Dead-end seals |

</div>
<div>

### Door Types

| Door Type | Description |
|-----------|-------------|
| Key Door | Requires a key item found elsewhere in the level |
| Activation Door | Opens when a nearby trigger is activated |
| Lock Door | Locked until a specific condition is met |
| Sealed Door | Permanently sealed passage |

</div>
</div>

<div class="ur-gallery">
<figure>

![Challenge Room](images/challenge_room.png)
<figcaption>A challenge room with mob spawners</figcaption>
</figure>
<figure>

![Treasure Room](images/treasure_room.png)
<figcaption>A treasure room behind a key door</figcaption>
</figure>
<figure>

![Shop Room](images/shop_room.png)
<figcaption>Shop room with the shopkeeper NPC</figcaption>
</figure>
</div>

---

## Dungeon Generation

Each run creates a unique layout by:

1. Placing the spawn room
2. Building the main corridor path up to the max room count
3. Inserting challenge rooms at random positions along the path
4. Adding treasure rooms (Kweebec only) and shop rooms
5. Generating branch paths from splitter positions
6. Placing the boss room at the end of the main path
7. Connecting rooms with doors based on type assignments
8. Distributing mobs across the level based on the weighted pool

<div class="ur-info-card">

**Note:** Branch paths are shorter than the main path and contain fewer mobs, but can still hold challenge rooms (Desert level branches always include one).
</div>

---

## Level Comparison

| Property | Kweebec | Desert |
|----------|---------|--------|
| Main Path Rooms | 5 | 6 |
| Challenge Rooms | 2 | 2 |
| Treasure Rooms | 1 | 0 |
| Shop Rooms | 1 | 1 |
| Branch Paths | 2 | 1 |
| Mobs (main) | ~250 | ~400 |
| Mobs (branches) | ~60 | ~50-100 |
| Unique Enemies | 12 types | 13 types (+Hazmat Toxic) |
| Branch Challenge | No | Yes (1 per branch) |

---

## Related Pages

- [Enemies and Bosses](enemies-bosses.md) -- Detailed enemy stats and boss mechanics
- [Loot and Crates](loot-crates.md) -- What drops in each level
- [Shops](shops.md) -- In-dungeon shop mechanics
- [HUD and Interface](hud-interface.md) -- Dungeon map and minimap features
