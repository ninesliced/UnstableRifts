---
title: Loot and Crates
description: Crate tiers, drop tables, and loot mechanics in Unstable Rifts
---

<style>
:root { --ur-accent: #bb2f2c; --ur-border: #2a2a4a; --ur-text-muted: #8892a4; }
.ur-gallery { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; } .ur-gallery figure { flex: 1 1 200px; max-width: 320px; margin: 0; text-align: center; } .ur-gallery figure img { width: 100%; border-radius: 6px; border: 1px solid var(--ur-border); } .ur-gallery figure figcaption { font-size: 0.82em; color: var(--ur-text-muted); margin-top: 4px; }
.ur-info-card { border-left: 3px solid var(--ur-accent); padding: 10px 16px; margin: 14px 0; background: rgba(187,47,44,0.05); border-radius: 0 4px 4px 0; }
span.rarity-basic { background:#9e9e9e;color:#fff;padding:2px 8px;border-radius:4px;font-size:0.85em; } span.rarity-uncommon { background:#4caf50;color:#fff;padding:2px 8px;border-radius:4px;font-size:0.85em; } span.rarity-rare { background:#2196f3;color:#fff;padding:2px 8px;border-radius:4px;font-size:0.85em; } span.rarity-epic { background:#9c27b0;color:#fff;padding:2px 8px;border-radius:4px;font-size:0.85em; } span.rarity-unique { background:#e91e63;color:#fff;padding:2px 8px;border-radius:4px;font-size:0.85em; }
</style>

# Loot and Crates

Crates are destructible containers scattered throughout dungeon rooms. Break them to receive weapons, armor, ammo, healing items, and coins. Higher-tier crates found deeper in the dungeon yield better loot.

<div class="ur-gallery">
<figure>

![Standard Crate](images/crates/crate_standard.png)
<figcaption>Standard Crate</figcaption>
</figure>
<figure>

![Level 1 Crate](images/crates/crate_level1.png)
<figcaption>Level 1 Crate</figcaption>
</figure>
<figure>

![Level 2 Crate](images/crates/crate_level2.png)
<figcaption>Level 2 Crate</figcaption>
</figure>
<figure>

![Level 3 Crate](images/crates/crate_level3.png)
<figcaption>Level 3 Crate</figcaption>
</figure>
<figure>

![Large Crate (2x2)](images/crates/crate_2x2.png)
<figcaption>Large Crate (2x2)</figcaption>
</figure>
</div>

---

## Crate Tiers Overview

| Crate | Rarity Range | Weapon % | Armor % | Ammo % | Heal % | Coins |
|-------|-------------|----------|---------|--------|--------|-------|
| Standard | <span class="rarity-basic">Basic</span> -- <span class="rarity-rare">Rare</span> | 30% | 25% | 10% | 8% | 1-3 |
| Level 1 | <span class="rarity-basic">Basic</span> -- <span class="rarity-unique">Unique</span> | 30% | 30% | 10% | 8% | 1-3 |
| Level 2 | <span class="rarity-uncommon">Uncommon</span> -- <span class="rarity-epic">Epic</span> | 50% | 40% | 14% | 12% | 2-5 |
| Level 3 | <span class="rarity-rare">Rare</span> -- <span class="rarity-unique">Unique</span> | 65% | 55% | 20% | 16% | 3-7 |
| Large (2x2) | <span class="rarity-basic">Basic</span> -- <span class="rarity-epic">Epic</span> | 50% | 40% | 16% | 14% | 3-7 |

---

## Standard Crate

The most common crate found in early dungeon rooms.

| Property | Value |
|----------|-------|
| Weapon Rarity | <span class="rarity-basic">Basic</span> -- <span class="rarity-rare">Rare</span> |
| Armor Rarity | <span class="rarity-basic">Basic</span> -- <span class="rarity-rare">Rare</span> |
| Coins | 1-3 |

**Weapon Pool:** Pistol, Crystal Sword, Blunderbuss, Assault Rifle, Rifle, Blitzer Crystal, Fire/Ice/Lightning/Void Muskets

**Armor Pool:** Crystal Set (Head, Chest, Legs, Boots), Vine Set (Head, Chest, Legs, Boots)

---

## Level 1 Crate

Found in the Kweebec level. Same base drops as Standard but with a wider rarity range and access to all weapons.

| Property | Value |
|----------|-------|
| Weapon Rarity | <span class="rarity-basic">Basic</span> -- <span class="rarity-unique">Unique</span> |
| Armor Rarity | <span class="rarity-basic">Basic</span> -- <span class="rarity-unique">Unique</span> |
| Coins | 1-3 |

**Weapon Pool:** All 19 weapons including Corrupted Shotgun, Taser, Voidlance, Kweebec Launcher, and all Elemental Blunderbusses

**Armor Pool:** Crystal Set, Vine Set

---

## Level 2 Crate

Found in mid-game rooms. Higher minimum rarity and better drop rates.

| Property | Value |
|----------|-------|
| Weapon Rarity | <span class="rarity-uncommon">Uncommon</span> -- <span class="rarity-epic">Epic</span> |
| Armor Rarity | <span class="rarity-uncommon">Uncommon</span> -- <span class="rarity-epic">Epic</span> |
| Coins | 2-5 |

**Weapon Pool:** 17 weapons (excludes Taser and Voidlance)

**Armor Pool:** Crystal Set, Vine Set, Shale Set, Bone Set

---

## Level 3 Crate

The best standard crate. Found in late-game rooms with the highest drop rates and rarity floor.

| Property | Value |
|----------|-------|
| Weapon Rarity | <span class="rarity-rare">Rare</span> -- <span class="rarity-unique">Unique</span> |
| Armor Rarity | <span class="rarity-rare">Rare</span> -- <span class="rarity-unique">Unique</span> |
| Coins | 3-7 |

**Weapon Pool:** All 19 weapons

**Armor Pool:** All 6 sets -- Crystal, Vine, Shale, Bone, Void, Warden

<div class="ur-info-card">

**Note:** Level 3 crates are the only standard crate that can drop Void and Warden armor pieces.
</div>

---

## Large Crate (2x2)

A physically larger crate that takes up a 2x2 block space. Higher ammo and healing rates than Standard, and broader rarity than Level 1.

| Property | Value |
|----------|-------|
| Weapon Rarity | <span class="rarity-basic">Basic</span> -- <span class="rarity-epic">Epic</span> |
| Armor Rarity | <span class="rarity-basic">Basic</span> -- <span class="rarity-epic">Epic</span> |
| Coins | 3-7 |

**Weapon Pool:** 17 weapons (same as Level 2)

**Armor Pool:** Crystal Set, Vine Set, Shale Set, Bone Set

---

## Destructible Props

In addition to loot crates, the dungeon contains environmental objects that can be destroyed:

| Object | Description |
|--------|-------------|
| Radioactive Barrel | Explodes when destroyed, damaging nearby enemies |
| Rusted Radioactive Barrel | Decayed variant, same explosion behavior |

<div class="ur-gallery">
<figure>

![Radioactive Barrel](images/props/radioactive_barrel.png)
<figcaption>Radioactive Barrel -- explodes on destruction</figcaption>
</figure>
<figure>

![Rusted Barrel](images/props/rusted_barrel.png)
<figcaption>Rusted Radioactive Barrel</figcaption>
</figure>
</div>

---

## Loot Progression

As you advance through the dungeon, crate quality improves:

| Progression | Available Crates | Best Possible Rarity | Armor Sets Available |
|-------------|-----------------|---------------------|---------------------|
| Early Rooms | Standard, Level 1 | <span class="rarity-unique">Unique</span> | Crystal, Vine |
| Mid Rooms | Level 2, Large | <span class="rarity-epic">Epic</span> | Crystal, Vine, Shale, Bone |
| Late Rooms | Level 3 | <span class="rarity-unique">Unique</span> | All 6 sets |

<div class="ur-info-card">

**Tip:** Seek out Level 3 crates for the best chance at Void and Warden armor. Large (2x2) crates are also valuable for their high coin drops and 50% weapon chance.
</div>

---

## Related Pages

- [Weapons](weapons.md) -- What weapons can drop
- [Armor Sets](armor.md) -- What armor can drop
- [Dungeon Levels](dungeon-levels.md) -- Where each crate tier appears
- [Enemies and Bosses](enemies-bosses.md) -- Enemy drop tables
