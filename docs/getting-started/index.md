---
title: "Getting Started"
order: 2
published: true
draft: false
---

# Getting Started

Unstable Rifts is a fully custom game mode that transforms Hytale into a top-down cooperative dungeon crawler. Players form a party, enter a portal, and fight through procedurally generated rooms filled with enemies, loot crates, shops, and bosses.

![Gameplay Overview](images/gameplay_overview.png)

---

## How to Play

1. **Form a Party** -- Use `/unstablerifts party create` or interact with the Ancient Party Portal item.
2. **Enter the Dungeon** -- The party leader starts the run with `/unstablerifts party start`.
3. **Fight Through Rooms** -- Clear enemies, break crates for loot, and find the boss room.
4. **Defeat the Boss** -- Each level ends with a boss fight. Defeat it to unlock the exit portal.
5. **Progress or Exit** -- Step through the portal to advance to the next level or return to the lobby.

![Ancient Party Portal](images/ancient_party_portal.png)
![Dungeon Entrance](images/dungeon_entrance.png)
![Top-Down Camera](images/top_camera.png)

---

## Starting Equipment

Every player spawns with two weapons at the beginning of each run:

| Slot | Item | Description |
|------|------|-------------|
| Primary | Pistol | Reliable sidearm for close to medium range combat |
| Secondary | Crystal Sword | Melee weapon for close-range combat |

**Tip:** You can find better weapons by breaking crates, defeating enemies, or purchasing from shops inside the dungeon.

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

![Revive Marker](images/mechanics/revive_marker.png)
_Revive marker at a player's death location_

![Revive in Progress](images/mechanics/revive_progress.png)
_Reviving a downed teammate_

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

- [Weapons](gear/weapons) -- Learn about the 19 available weapons
- [Armor Sets](gear/armor) -- Find out about armor set bonuses
- [Dungeon Levels](dungeons/dungeon-levels) -- Explore what awaits in each level
- [Party System](systems/party-system) -- Team up with friends
- [Commands](getting-started/commands) -- Full command reference
