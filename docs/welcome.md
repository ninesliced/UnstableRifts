---
title: Unstable Rifts
description: Top-down roguelike dungeon shooter for Hytale
---

<style>
:root { --ur-accent: #bb2f2c; --ur-gold: #ffd700; --ur-basic: #c9d2dd; --ur-uncommon: #3e9049; --ur-rare: #2770b7; --ur-epic: #8b339e; --ur-legendary: #bb8a2c; --ur-unique: #bb2f2c; --ur-surface: #1a1a2e; --ur-surface-alt: #16213e; --ur-border: #2a2a4a; --ur-text-muted: #8892a4; }
.ur-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 0.8em; font-weight: 600; color: #fff; }
.ur-badge.basic { background: var(--ur-basic); color: #1a1a2e; } .ur-badge.uncommon { background: var(--ur-uncommon); } .ur-badge.rare { background: var(--ur-rare); } .ur-badge.epic { background: var(--ur-epic); } .ur-badge.legendary { background: var(--ur-legendary); } .ur-badge.unique { background: var(--ur-unique); }
.ur-gallery { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; } .ur-gallery figure { flex: 1 1 200px; max-width: 320px; margin: 0; text-align: center; } .ur-gallery figure img { width: 100%; border-radius: 6px; border: 1px solid var(--ur-border); } .ur-gallery figure figcaption { font-size: 0.82em; color: var(--ur-text-muted); margin-top: 4px; }
.ur-gallery-small figure { flex: 1 1 120px; max-width: 180px; }
.ur-info-card { border-left: 3px solid var(--ur-accent); padding: 10px 16px; margin: 14px 0; background: rgba(187,47,44,0.05); border-radius: 0 4px 4px 0; }
.ur-columns { display: flex; gap: 24px; flex-wrap: wrap; } .ur-columns > div { flex: 1; min-width: 280px; }
.ur-page-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; margin: 20px 0; }
.ur-page-card { border: 1px solid var(--ur-border); border-radius: 8px; padding: 16px; transition: border-color 0.2s; }
.ur-page-card:hover { border-color: var(--ur-accent); }
.ur-page-card h4 { margin: 0 0 6px 0; } .ur-page-card p { margin: 0; font-size: 0.9em; color: var(--ur-text-muted); }
</style>

# Unstable Rifts

> A top-down roguelike dungeon shooter for Hytale. Fight through procedurally generated levels with up to 4 players, collect weapons and armor, and defeat bosses to progress.

|  |  |
|---|---|
| **Version** | 1.0.0 |
| **Authors** | Theobosse, Paralaxe |
| **Players** | 1 - 4 (Co-op) |
| **Weapons** | 19 |
| **Armor Sets** | 6 (24 pieces) |
| **Dungeon Levels** | 1 |
| **Bosses** | 1 |
| **Enemies** | 14+ types |
| **Website** | [curseforge.com](https://www.curseforge.com) |

<div class="ur-gallery">
<figure>

![Unstable Rifts Logo](images/unstablerifts_logo.png)
<figcaption>Unstable Rifts -- roguelike shooter in Hytale</figcaption>
</figure>
<figure>

![Gameplay Overview](images/gameplay_overview.png)
<figcaption>Top-down dungeon gameplay with a party of 4</figcaption>
</figure>
</div>

---

## Wiki Pages

### Getting Started

<div class="ur-page-grid">
<div class="ur-page-card">

#### [Getting Started](getting-started.md)
How to play, starting equipment, and basic gameplay loop.

</div>
<div class="ur-page-card">

#### [Installation](installation.md)
Server setup, requirements, configuration files, and admin tools.

</div>
<div class="ur-page-card">

#### [Commands](commands.md)
Full command reference for players and server administrators.

</div>
</div>

### Gear and Loot

<div class="ur-page-grid">
<div class="ur-page-card">

#### [Weapons](weapons.md)
All 19 weapons -- stats, rarities, elemental effects, and categories.

</div>
<div class="ur-page-card">

#### [Armor Sets](armor.md)
6 armor sets, set abilities, stat breakdowns, and rarity ranges.

</div>
<div class="ur-page-card">

#### [Loot and Crates](loot-crates.md)
Crate tiers, drop tables, weapon and armor pool details.

</div>
</div>

### Dungeons and Combat

<div class="ur-page-grid">
<div class="ur-page-card">

#### [Dungeon Levels](dungeon-levels.md)
Level layouts, room types, mob pools, and generation details.

</div>
<div class="ur-page-card">

#### [Enemies and Bosses](enemies-bosses.md)
All enemy types, boss mechanics, attack patterns, and ally NPCs.

</div>
<div class="ur-page-card">

#### [Shops](shops.md)
In-dungeon shops, purchasing, and available items.

</div>
</div>

### Systems

<div class="ur-page-grid">
<div class="ur-page-card">

#### [Party System](party-system.md)
Party creation, invites, privacy, reconnection, and shared resources.

</div>
<div class="ur-page-card">

#### [HUD and Interface](hud-interface.md)
All HUD elements -- ammo, boss health, party status, revive prompts, and map.

</div>
</div>

---

<div style="text-align: center; padding: 24px 0; color: var(--ur-text-muted); font-size: 0.88em;">

Unstable Rifts v1.0.0 -- by Theobosse and Paralaxe -- [curseforge.com](https://www.curseforge.com)

</div>