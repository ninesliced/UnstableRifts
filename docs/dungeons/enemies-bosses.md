---
title: "Enemies and Bosses"
order: 2
published: true
draft: false
---

# Enemies and Bosses

The dungeon is populated with hostile mobs, a powerful boss, and a few friendly allies. Each enemy type has unique behavior, stats, and drop tables.

![DeadWood Rootling](images/enemies/deadwood_rootling.png)
![DeadWood Sproutling](images/enemies/deadwood_sproutling.png)
![Radioactive Wolf](images/enemies/radioactive_wolf.png)
![Industrial Nosuit](images/enemies/industrial_nosuit.png)
![Kweebec Seedling](images/enemies/kweebec_seedling.png)
_Kweebec Seedling_
![DeadWood Seedling](images/enemies/deadwood_seedling.png)
_DeadWood Seedling_
![Industrial Hazmat](images/enemies/industrial_hazmat.png)
_Industrial Hazmat (Toxic Launcher)_

---

## DeadWood Enemies

The DeadWood are corrupted tree creatures that make up the bulk of the dungeon's hostile population. They come in three size classes, each with armed variants.

### Rootlings

Medium-sized DeadWood. The most common enemy type.

| Variant | Weapon | Spawn Weight |
|---------|--------|-------------|
| Rootling | Unarmed | 10 |
| Rootling (Sword) | Sword | 5 |
| Rootling (Axe) | Axe | 5 |
| Rootling (Lance) | Lance | 5 |

- Detection range: 30 blocks
- Alert range: 54 blocks

### Sproutlings

Smaller, faster DeadWood. Same variants as Rootlings.

| Variant | Weapon | Spawn Weight |
|---------|--------|-------------|
| Sproutling | Unarmed | 10 |
| Sproutling (Sword) | Sword | 5 |
| Sproutling (Axe) | Axe | 5 |
| Sproutling (Lance) | Lance | 5 |

### Seedlings

Larger DeadWood with more health. Less common than Rootlings and Sproutlings.

| Variant | Spawn Weight |
|---------|-------------|
| DeadWood Seedling | 4 |

### DeadWood Drops

| Drop | Chance | Amount |
|------|--------|--------|
| Coins | 45% | 1-3 |
| Ammo | 16% | 1 |
| Healing | 12% | 1 |

---

## Wildlife

### Radioactive Wolf

Fast pack hunters that can close distance quickly. One of the more dangerous regular enemies.

| Stat | Value |
|------|-------|
| Health | 103 HP |
| Damage | 27 (Wolf Bite, +/-10%) |
| Speed | Inherited from Wolf_Black template |
| Spawn Weight | 5 |

| Drop | Chance | Amount |
|------|--------|--------|
| Coins | 50% | 1-3 |
| Ammo | 18% | 1 |
| Healing | 14% | 1 |

---

## Industrial Enemies

### Nosuit

Unarmored industrial workers. Rarer but tougher than DeadWood.

| Stat | Value |
|------|-------|
| Speed | 6.9 |
| Detection | 28 blocks |
| Alert Range | 54 blocks |
| Spawn Weight | 3 |

### Hazmat (Toxic Launcher)

A ranged industrial enemy exclusive to the Desert level. Fires toxic projectiles from a distance.

| Stat | Value |
|------|-------|
| Spawn Weight | 3 (Desert only) |
| Attack Type | Ranged toxic projectile |

### Industrial Drops

| Drop | Chance | Amount |
|------|--------|--------|
| Coins | 55% | 1-4 |
| Ammo | 20% | 1 |
| Healing | 15% | 1 |

---

## Kweebec Seedling (Enemy)

A small explosive Kweebec that rushes toward players and detonates. Use ranged weapons to eliminate them before they reach you.

| Stat | Value |
|------|-------|
| Detection | 32 blocks |
| Alert Range | 56 blocks |
| Spawn Weight | 3 |
| Behavior | Rush and explode |

---

## Boss

The dungeon ends with a boss fight. The boss room is always the last room on the main path.

### Boss: Forklift

A massive industrial machine that charges at players with devastating speed.
![Boss Forklift](images/bosses/forklift_idle.png)
_Boss Forklift -- idle stance_
![Forklift Dash Attack](images/bosses/forklift_dash.png)
_Forklift performing its multi-hit dash attack_
![Forklift Barrel Toss](images/bosses/forklift_barrel.png)
_Forklift throwing a radioactive barrel_
| Stat | Value |
|------|-------|
| Health | 2,500 HP |
| Speed | 7.5 |
| Detection | 22 blocks |
| Alert Range | 28 blocks |

#### Attack Patterns

| Attack | Description | Damage |
|--------|-------------|--------|
| Fork Attack | Double melee strike | 15 x2 |
| Turn Single | 360 degree sweep | Multiple hits |
| Turn Double | Double sweep rotation | Multiple hits |
| Leap Attack | 7.2 block distance leap | Area impact |
| Dash Attack | 6-hit dash (0.24s intervals) | 15 per hit |
| Barrel Toss | Throws radioactive barrel | Projectile damage |
| Charge | High-speed bull rush | Contact damage |

The Forklift prioritizes movement-based attacks (weight: 6 moving, 2 direct, 1 strafe), making it difficult to pin down.

#### Drops

| Drop | Chance | Amount |
|------|--------|--------|
| Coins | 100% | 8-14 |
| Ammo | 70% | 1 |
| Healing | 55% | 1 |

---

## Ally NPCs

A friendly NPC can fight alongside the player:

### Kweebec Seedling (Ally)

A small Kweebec summoned by the Kweebec Launcher weapon. Rushes enemies and explodes on contact.
![Kweebec Ally](images/allies/kweebec_seedling_ally.png)
_Friendly Kweebec Seedling -- summoned by the Kweebec Launcher_
| Stat | Value |
|------|-------|
| Health | 36 HP |
| Speed | 7.5 |
| Lifetime | 600 seconds |
| Leash Range | 56 blocks |
| Combat Style | Aggressive rush (24 moving weight) |
| Friendly Fire | Cannot damage players or allies |

---

## Enemy Comparison Table

| Enemy | Health | Damage | Coin Drop | Drop Rate | Level |
|-------|--------|--------|-----------|-----------|-------|
| DeadWood Rootling | -- | Varies by variant | 1-3 | 45% | 1, 2 |
| DeadWood Sproutling | -- | Varies by variant | 1-3 | 45% | 1, 2 |
| DeadWood Seedling | -- | -- | 1-3 | 45% | 1, 2 |
| Kweebec Seedling | -- | Explosive | 1-3 | 45% | 1, 2 |
| Radioactive Wolf | 103 | 27 | 1-3 | 50% | 1, 2 |
| Industrial Nosuit | -- | -- | 1-4 | 55% | 1, 2 |
| Industrial Hazmat | -- | Toxic projectile | 1-4 | 55% | 2 only |
| Boss Forklift | 2,500 | 15 (x6 dash) | 8-14 | 100% | Boss |

---

## Related Pages

- [Dungeon Levels](dungeon-levels-1) -- Where each enemy spawns
- [Weapons](weapons-1) -- What to fight them with
- [Loot and Crates](loot-crates-1) -- Enemy and crate drop tables
- [Getting Started](getting-started-1) -- Combat basics
