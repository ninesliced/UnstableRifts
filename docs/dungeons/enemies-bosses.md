---
title: "Enemies and Bosses"
order: 2
published: true
draft: false
---

# Enemies and Bosses

You will fight many enemy types in the dungeon, plus one boss. Each enemy has different behavior and drops.

![DeadWood Rootling](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/rootling.png)
_DeadWood Rootling -- the most common enemy type_
![DeadWood Sproutling](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/sproutling.png)
_DeadWood Sproutling -- smaller and faster than Rootlings_
![Radioactive Wolf](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/wolf_radioactive.png)
_Radioactive Wolf -- fast pack hunter_
![Industrial Nosuit](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/industrial_nosuit.png)
_Industrial Nosuit -- unarmored worker enemy_
![Kweebec Seedling](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/kweebec_seedling.png)
_Kweebec Seedling -- small explosive enemy_
![DeadWood Seedling](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/seedling.png)
_DeadWood Seedling -- larger DeadWood with more health_
![Industrial Hazmat](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/hazmat.png)
_Industrial Hazmat -- close range industrial enemy_
![Industrial Hazmat (FlameThrower)](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/hazmat_flamethrower.png)
_Industrial Hazmat (FlameThrower) -- ranged industrial enemy_
![Industrial Hazmat (Toxic Launcher)](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/enemies/hazmat_toxic.png)
_Industrial Hazmat (Toxic Launcher) -- ranged industrial enemy_

---

## DeadWood Enemies

DeadWood are corrupted tree enemies and make up most fights. They come in different sizes and weapon variants.

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

### Hazmat

A close-range industrial enemy that attacks with his fist.

| Stat | Value |
|------|-------|
| Spawn Weight | 3 (Desert only) |
| Attack Type | Melee |

### Hazmat (FlameThrower)

A close-range industrial enemy that attacks with a flamethrower.

| Stat | Value |
|------|-------|
| Spawn Weight | 3 (Desert only) |
| Attack Type | Ranged flames projectile |

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
![Boss Forklift](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/bosses/forklift.png)
_Boss Forklift_
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

The Forklift moves a lot and is hard to keep in one place.

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

A small ally summoned by the Kweebec Launcher. It rushes enemies and explodes on contact.
![Kweebec Ally](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/allies/kweebec_seedling.png)
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
