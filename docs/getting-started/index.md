---
title: "Getting Started"
order: 2
published: true
draft: false
---

# Getting Started

Unstable Rifts is a top-down co-op dungeon mode. You and your team enter a portal, clear rooms, grab loot, visit shops, and beat the boss.

![Gameplay Overview](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/general/gameplay_overview.png)

---

## How to Play

1. **Form a Party** -- Use `/unstablerifts party create` or interact with the Ancient Party Portal item.
2. **Enter the Dungeon** -- The party leader starts the run with `/unstablerifts party start`.
3. **Fight Through Rooms** -- Clear enemies, break crates for loot, and find the boss room.
4. **Defeat the Boss** -- Each level ends with a boss fight. Defeat it to unlock the exit portal.
5. **Progress or Exit** -- Step through the portal to advance to the next level or return to the lobby.

![Ancient Party Portal](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/getting-started/ancient_party_portal.png)
![Dungeon Entrance](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/getting-started/dungeon_entrance.png)
![Top-Down Camera](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/getting-started/top_camera.png)

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

Each run builds a fresh dungeon layout. You follow a main path, with side paths for extra loot and fights.

### Combat

Use the top-down view to aim and shoot. Swap weapons based on distance: guns for space, sword when enemies get close.

### Loot

Break crates for weapons, armor, ammo, healing, and coins. Better crates appear later in the run.

### Shops

Each level contains one shop room where you can spend coins on weapons, armor, and consumables. Shops are safe zones with no enemies.

### Boss Fights

The final room of each level contains a boss. Defeating the boss opens a portal to the next level or to the lobby exit.

---

## Revive System

If you die during a run, a revive marker appears where you fell. A teammate can hold **F** near it to bring you back within 30 seconds. If nobody revives you in time, you are removed from the dungeon.

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

- [Weapons](weapons-1) -- Learn about the 19 available weapons
- [Armor Sets](armor-sets-1) -- Find out about armor set bonuses
- [Dungeon Levels](dungeon-levels-1) -- Explore what awaits in each level
- [Party System](party-system-1) -- Team up with friends
- [Commands](commands-1) -- Full command reference
