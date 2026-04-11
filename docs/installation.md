---
title: "Installation"
order: 3
published: true
draft: false
---



# Installation

This page covers everything a server owner needs to set up and configure Unstable Rifts.

---

## Requirements

| Requirement | Value |
|---|---|
| Server Version | `2026.04.09-7243e82f8` or compatible |
| Dependencies | None |
| Asset Pack | Included (auto-loaded) |

---

## Setup

1. Download the latest `.jar` from the releases page.
2. Place it in your server's `mods/` directory.
3. Restart the server. The mod auto-registers all assets and configurations.
**Note:** The mod includes its own asset pack. No additional downloads or manual asset installation is required.
---

## Configuration Files

The mod is fully data-driven. All configuration files are located in `src/main/resources/` and require a server restart to apply changes.

| File | Purpose |
|------|---------|
| `dungeon.json` | Level layout, room counts, mob pools, branch configuration |
| `weapon_registry.json` | All weapon definitions, stats, and rarity ranges |
| `armor_registry.json` | All armor definitions, set bonuses, and stat values |
| `crate_loot.json` | Crate drop tables, weapon whitelists, rarity bounds |
| `destructible_blocks.json` | Breakable block definitions and crate tier mapping |

### dungeon.json

Controls the dungeon structure. Key settings:

| Setting | Description |
|---------|-------------|
| `dungeonName` | Display name for the dungeon |
| `maxPartySize` | Maximum players per party (default: 4) |
| `startEquipment` | Item IDs given to each player at run start |
| `levels` | Array of level configurations |

Each level contains:

| Setting | Description |
|---------|-------------|
| `id` | Internal level identifier |
| `name` | Display name shown in HUD |
| `rooms` | Prefab patterns for each room type |
| `main.maxRooms` | Maximum rooms on the main path |
| `main.challengeRooms` | Min/max challenge rooms |
| `main.treasureRooms` | Min/max treasure rooms |
| `main.shopRooms` | Min/max shop rooms |
| `main.mobsToSpawn` | Min/max total mobs on main path |
| `branch.splitterCount` | Number of branching paths |
| `branch.maxRooms` | Max rooms per branch |
| `mobs` | Weighted mob pool (mob ID to spawn weight) |

### weapon_registry.json

Each weapon entry includes:

| Field | Description |
|-------|-------------|
| `itemId` | Item ID registered in the server |
| `displayName` | Name shown to players |
| `category` | LASER, BULLET, MELEE, or SUMMONING |
| `damage` | Base damage per hit |
| `cooldown` | Seconds between shots |
| `reloadTime` | Seconds to reload |
| `maxAmmo` | Maximum ammunition |
| `range` | Maximum effective range |
| `spread` | Projectile spread angle |
| `pellets` | Projectiles per shot |
| `minRarity` / `maxRarity` | Rarity range for drops |
| `spawnWeight` | Relative drop frequency weight |
| `lockedEffect` | Fixed elemental effect (if any) |

### armor_registry.json

Each armor entry includes:

| Field | Description |
|-------|-------------|
| `itemId` | Item ID registered in the server |
| `setId` | Armor set group identifier |
| `slot` | HELMET, CHESTPLATE, LEGGINGS, or BOOTS |
| `protection` | Base protection value |
| `setAbility` | Ability granted when full set is worn |
| `minRarity` / `maxRarity` | Rarity range for drops |
| `spawnWeight` | Relative drop frequency weight |
| Additional stats | knockback, closeDamageReduction, farDamageReduction, spikeDamage, lifeBoost, speedBoost |

### crate_loot.json

Each crate tier entry includes:

| Field | Description |
|-------|-------------|
| `crateId` | Block ID of the crate |
| `minRarity` / `maxRarity` | Rarity bounds for rolled items |
| `weaponChance` | Percentage chance to drop a weapon |
| `armorChance` | Percentage chance to drop armor |
| `coinMin` / `coinMax` | Coin drop range |
| `ammoChance` | Percentage chance for ammo |
| `healChance` | Percentage chance for healing |
| `weaponWhitelist` | List of weapon IDs eligible to drop |
| `armorWhitelist` | List of armor IDs eligible to drop |

---

## Dungeon Instance Lifecycle

Understanding the instance lifecycle helps with debugging and monitoring:

| State | Description |
|-------|-------------|
| `GENERATING` | Dungeon rooms are being procedurally assembled |
| `LOADING` | Instance world is initializing |
| `ACTIVE` | Gameplay in progress |
| `TRANSITIONING` | Moving between levels |
| `VICTORY` | Boss defeated, 30-second countdown to portal |
| `COMPLETE` | Run ended, cleanup in progress |

---

## Admin Commands

| Command | Description |
|---------|-------------|
| `/unstablerifts dungeon <level>` | Force-create a dungeon instance for testing |
| `/unstablerifts giveportal` | Give yourself an Ancient Party Portal item |
| `/unstablerifts loot` | Spawn a random weapon drop at your feet |
| `/unstablerifts coins set <amount>` | Set a player's coin count |
| `/unstablerifts coins add <amount>` | Add coins to a player |
| `/unstablerifts coins reset` | Reset coin scores |
| `/unstablerifts coins get` | View current coins |
| `/unstablerifts coins list` | List all player coin scores |
| `/unstablerifts pickup` | Debug pickup tracking |

---

## Mod Manifest

| Field | Value |
|-------|-------|
| Group | ninesliced |
| Name | Unstable Rifts |
| Version | 1.0.0 |
| Authors | Theobosse, Paralaxe |
| Website | [ninesliced.com](https://ninesliced.com) |
| Main Class | `dev.ninesliced.unstablerifts.UnstableRifts` |
| Includes Asset Pack | Yes |
| Disabled By Default | No |

---

## Related Pages

- [Commands](commands.md) -- Full command reference (player and admin)
- [Dungeon Levels](dungeon-levels.md) -- Level configuration details
- [Loot and Crates](loot-crates.md) -- Crate loot configuration
