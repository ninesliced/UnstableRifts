---
title: Enemies and Bosses
description: All enemies, bosses, and ally NPCs in Unstable Rifts
---

<style>
:root { --ur-accent: #bb2f2c; --ur-border: #2a2a4a; --ur-text-muted: #8892a4; }
.ur-gallery { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; } .ur-gallery figure { flex: 1 1 200px; max-width: 320px; margin: 0; text-align: center; } .ur-gallery figure img { width: 100%; border-radius: 6px; border: 1px solid var(--ur-border); } .ur-gallery figure figcaption { font-size: 0.82em; color: var(--ur-text-muted); margin-top: 4px; }
.ur-columns { display: flex; gap: 24px; flex-wrap: wrap; } .ur-columns > div { flex: 1; min-width: 280px; }
.ur-info-card { border-left: 3px solid var(--ur-accent); padding: 10px 16px; margin: 14px 0; background: rgba(187,47,44,0.05); border-radius: 0 4px 4px 0; }
</style>

# Enemies and Bosses

The dungeon is populated with hostile mobs, a powerful boss, and a few friendly allies. Each enemy type has unique behavior, stats, and drop tables.

<div class="ur-gallery">
<figure>

![DeadWood Rootling](images/enemies/deadwood_rootling.png)
<figcaption>DeadWood Rootling</figcaption>
</figure>
<figure>

![DeadWood Sproutling](images/enemies/deadwood_sproutling.png)
<figcaption>DeadWood Sproutling</figcaption>
</figure>
<figure>

![Radioactive Wolf](images/enemies/radioactive_wolf.png)
<figcaption>Radioactive Wolf</figcaption>
</figure>
<figure>

![Industrial Nosuit](images/enemies/industrial_nosuit.png)
<figcaption>Industrial Nosuit</figcaption>
</figure>
<figure>

![Kweebec Seedling](images/enemies/kweebec_seedling.png)
<figcaption>Kweebec Seedling</figcaption>
</figure>
<figure>

![DeadWood Seedling](images/enemies/deadwood_seedling.png)
<figcaption>DeadWood Seedling</figcaption>
</figure>
<figure>

![Industrial Hazmat](images/enemies/industrial_hazmat.png)
<figcaption>Industrial Hazmat (Toxic Launcher)</figcaption>
</figure>
</div>

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

<div class="ur-gallery">
<figure>

![Boss Forklift](images/bosses/forklift_idle.png)
<figcaption>Boss Forklift -- idle stance</figcaption>
</figure>
<figure>

![Forklift Dash Attack](images/bosses/forklift_dash.png)
<figcaption>Forklift performing its multi-hit dash attack</figcaption>
</figure>
<figure>

![Forklift Barrel Toss](images/bosses/forklift_barrel.png)
<figcaption>Forklift throwing a radioactive barrel</figcaption>
</figure>
</div>

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

<div class="ur-gallery">
<figure>

![Kweebec Ally](images/allies/kweebec_seedling_ally.png)
<figcaption>Friendly Kweebec Seedling -- summoned by the Kweebec Launcher</figcaption>
</figure>
</div>

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

- [Dungeon Levels](dungeon-levels.md) -- Where each enemy spawns
- [Weapons](weapons.md) -- What to fight them with
- [Loot and Crates](loot-crates.md) -- Enemy and crate drop tables
- [Getting Started](getting-started.md) -- Combat basics
