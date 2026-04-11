---
title: "Getting Started"
order: 2
published: true
draft: false
---

# Getting Started

Unstable Rifts is a top-down co-op dungeon mode. You and your team enter a portal, clear rooms, grab loot, visit shops, and beat the boss.

![Gameplay Overview](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/general/gameplay_overview.png)

## Story Setup

The Industrial faction has invaded Orbis and is attacking the Kweebecs to steal resources and take control of the land. The rifts you enter are part of that invasion, and each completed run pushes the enemy back.

Many Kweebecs have already been lost to an unknown plague that turns them into DeadWood. If you want the full story, read [Lore](lore).

---

## How To Start A Run

You can start playing in three main ways:

1. **Find an Ancient Party Portal** in a Kweebec village.
2. **Craft your own portal** and place it yourself.
3. **Open `/ur party ui`** to create or manage a party directly from the interface.

After that, create a party, invite friends, and launch the run.

---

## How to Play

1. **Form a Party** -- Use a village portal, a crafted portal, or `/ur party ui` to create or manage your group.
2. **Invite Friends** -- Invite other players from the party menu or with `/ur party invite <player>`.
3. **Enter the Dungeon** -- The party leader starts the run from the party UI or with `/unstablerifts party start`.
4. **Fight Through Rooms** -- Clear enemies, break crates for loot, and find the boss room.
5. **Defeat the Boss** -- Each level ends with a boss fight. Defeat it to unlock the exit portal.
6. **Progress or Exit** -- Step through the portal to advance to the next level or return to the lobby.

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

- [Lore](unstable-rifts/lore) -- Learn why the invasion started and what the rifts mean
- [Weapons](unstable-rifts/weapons-1) -- Learn about the 19 available weapons
- [Armor Sets](unstable-rifts/armor-sets-1) -- Find out about armor set bonuses
- [Dungeon Levels](unstable-rifts/dungeon-levels-1) -- Explore what awaits in each level
- [Party System](unstable-rifts/party-system-1) -- Team up with friends
- [Commands](unstable-rifts/commands-1) -- Full command reference
