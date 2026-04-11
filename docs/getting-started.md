---
title: Getting Started
description: How to play Unstable Rifts
---

<style>
:root { --ur-accent: #bb2f2c; --ur-border: #2a2a4a; --ur-text-muted: #8892a4; }
.ur-gallery { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; } .ur-gallery figure { flex: 1 1 200px; max-width: 320px; margin: 0; text-align: center; } .ur-gallery figure img { width: 100%; border-radius: 6px; border: 1px solid var(--ur-border); } .ur-gallery figure figcaption { font-size: 0.82em; color: var(--ur-text-muted); margin-top: 4px; }
.ur-info-card { border-left: 3px solid var(--ur-accent); padding: 10px 16px; margin: 14px 0; background: rgba(187,47,44,0.05); border-radius: 0 4px 4px 0; }
</style>

# Getting Started

Unstable Rifts is a fully custom game mode that transforms Hytale into a top-down cooperative dungeon crawler. Players form a party, enter a portal, and fight through procedurally generated rooms filled with enemies, loot crates, shops, and bosses.

<div class="ur-gallery">
<figure>

![Gameplay Overview](images/gameplay_overview.png)
<figcaption>Top-down dungeon gameplay with a party of 4</figcaption>
</figure>
</div>

---

## How to Play

1. **Form a Party** -- Use `/unstablerifts party create` or interact with the Ancient Party Portal item.
2. **Enter the Dungeon** -- The party leader starts the run with `/unstablerifts party start`.
3. **Fight Through Rooms** -- Clear enemies, break crates for loot, and find the boss room.
4. **Defeat the Boss** -- Each level ends with a boss fight. Defeat it to unlock the exit portal.
5. **Progress or Exit** -- Step through the portal to advance to the next level or return to the lobby.

<div class="ur-gallery">
<figure>

![Ancient Party Portal](images/ancient_party_portal.png)
<figcaption>Ancient Party Portal item -- used to form a party</figcaption>
</figure>
<figure>

![Dungeon Entrance](images/dungeon_entrance.png)
<figcaption>Entering the dungeon spawn room</figcaption>
</figure>
<figure>

![Top-Down Camera](images/top_camera.png)
<figcaption>Top-down camera perspective during gameplay</figcaption>
</figure>
</div>

---

## Starting Equipment

Every player spawns with two weapons at the beginning of each run:

| Slot | Item | Description |
|------|------|-------------|
| Primary | Pistol | Reliable sidearm for close to medium range combat |
| Secondary | Crystal Sword | Melee weapon for close-range combat |

<div class="ur-info-card">

**Tip:** You can find better weapons by breaking crates, defeating enemies, or purchasing from shops inside the dungeon.
</div>

---

## Core Gameplay Loop

### Exploration

Each dungeon is procedurally generated with a main path and branching side paths. Rooms connect via corridors with different door types that may require keys or triggers to open.

### Combat

Use the top-down camera to aim and shoot enemies. Switch between weapons based on the situation -- use ranged weapons for groups and the Crystal Sword for close encounters.

### Loot

Break crates scattered throughout rooms to find weapons, armor, ammo, healing items, and coins. Higher-tier crates found deeper in the dungeon contain better loot.

### Shops

Each level contains one shop room where you can spend coins on weapons, armor, and consumables. Shops are safe zones with no enemies.

### Boss Fights

The final room of each level contains a boss. Defeating the boss opens a portal to the next level or to the lobby exit.

---

## Revive System

If you die during a run, a revive marker appears at your death location. Any party member can hold **F** near the marker to revive you within a 30-second window. If no one revives you in time, you are removed from the dungeon.

<div class="ur-gallery">
<figure>

![Revive Marker](images/mechanics/revive_marker.png)
<figcaption>Revive marker at a player's death location</figcaption>
</figure>
<figure>

![Revive in Progress](images/mechanics/revive_progress.png)
<figcaption>Reviving a downed teammate</figcaption>
</figure>
</div>

---

## Camera Controls

The game uses a fixed top-down camera. You can rotate the camera using the `/unstablerifts topcamera` command. Four rotation angles are available:

| Rotation | Angle |
|----------|-------|
| 0 | 0 degrees (default) |
| 1 | 90 degrees |
| 2 | 180 degrees |
| 3 | 270 degrees |

---

## Coins

Coins drop from enemies and crates. They are shared across the entire party and used to purchase items from shops. Coins are only valid for the current dungeon run.

| Source | Amount |
|--------|--------|
| DeadWood Enemies | 1-3 (45% chance) |
| Industrial/Wolf Enemies | 1-4 (55% chance) |
| Boss Forklift | 8-14 (guaranteed) |
| Crates (Level 1-3) | 1-7 depending on tier |

---

## Safety Systems

| System | Description |
|--------|-------------|
| Fall Damage | Disabled inside the dungeon |
| Void Protection | Players who fall into void are teleported back to safety |
| Slot Lock | Prevents weapon/armor swaps during critical actions |
| Dungeon Inventory | Separate from your overworld inventory; restored on reconnect |

---

## Next Steps

- [Weapons](weapons.md) -- Learn about the 19 available weapons
- [Armor Sets](armor.md) -- Find out about armor set bonuses
- [Dungeon Levels](dungeon-levels.md) -- Explore what awaits in each level
- [Party System](party-system.md) -- Team up with friends
- [Commands](commands.md) -- Full command reference
