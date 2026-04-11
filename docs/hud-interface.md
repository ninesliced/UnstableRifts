---
title: HUD and Interface
description: All HUD elements and UI systems in Unstable Rifts
---

<style>
:root { --ur-accent: #bb2f2c; --ur-border: #2a2a4a; --ur-text-muted: #8892a4; }
.ur-gallery { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; } .ur-gallery figure { flex: 1 1 200px; max-width: 320px; margin: 0; text-align: center; } .ur-gallery figure img { width: 100%; border-radius: 6px; border: 1px solid var(--ur-border); } .ur-gallery figure figcaption { font-size: 0.82em; color: var(--ur-text-muted); margin-top: 4px; }
.ur-columns { display: flex; gap: 24px; flex-wrap: wrap; } .ur-columns > div { flex: 1; min-width: 280px; }
.ur-info-card { border-left: 3px solid var(--ur-accent); padding: 10px 16px; margin: 14px 0; background: rgba(187,47,44,0.05); border-radius: 0 4px 4px 0; }
</style>

# HUD and Interface

Unstable Rifts replaces the default Hytale HUD with a custom interface designed for the top-down dungeon crawler experience. All HUD elements can be toggled with `/ur togglehud`.

<div class="ur-gallery">
<figure>

![Full HUD Overview](images/hud/hud_overview.png)
<figcaption>Complete HUD layout during gameplay</figcaption>
</figure>
</div>

---

## HUD Elements

<div class="ur-columns">
<div>

### Ammo Display

Shows your current weapon's ammunition count and reload status. Updates in real-time as you fire and reload.

<div class="ur-gallery">
<figure>

![Ammo HUD](images/hud/ammo_display.png)
<figcaption>Ammo counter with reload indicator</figcaption>
</figure>
</div>

### Boss Health Bar

Appears during boss fights. Shows the boss name, current health, and health percentage. The bar color changes based on remaining HP:

| Health Range | Color |
|-------------|-------|
| 75-100% | Green |
| 50-75% | Yellow |
| 25-50% | Orange |
| 0-25% | Red |

<div class="ur-gallery">
<figure>

![Boss Health Bar](images/hud/boss_health.png)
<figcaption>Boss health bar during the Forklift fight</figcaption>
</figure>
</div>

### Dungeon Info

Displays the current dungeon name, level, and room information in the corner of the screen.

<div class="ur-gallery">
<figure>

![Dungeon Info](images/hud/dungeon_info.png)
<figcaption>Dungeon info panel showing level name and progress</figcaption>
</figure>
</div>

</div>
<div>

### Party Status

Shows all party members with their health bars, names, and connection status. Disconnected members appear grayed out.

<div class="ur-gallery">
<figure>

![Party Status](images/hud/party_status.png)
<figcaption>Party status panel with 4 members</figcaption>
</figure>
</div>

### Death and Revive

When a player dies, a revive prompt appears for nearby teammates. The HUD shows:

| Element | Description |
|---------|-------------|
| Revive Marker | Appears at the death location |
| Progress Bar | Fills as a teammate holds F to revive |
| Timer | 30-second window to revive before removal |

<div class="ur-gallery">
<figure>

![Revive HUD](images/hud/revive_prompt.png)
<figcaption>Revive progress bar while holding F</figcaption>
</figure>
</div>

### Portal Prompt

Appears when a portal is available (after defeating the boss). Shows a prompt to enter the portal and advance to the next level or exit.

<div class="ur-gallery">
<figure>

![Portal Prompt](images/hud/portal_prompt.png)
<figcaption>Portal prompt after boss defeat</figcaption>
</figure>
</div>

</div>
</div>

---

## Challenge HUD

During challenge rooms, a special HUD element appears showing the room's objective and progress.

<div class="ur-gallery">
<figure>

![Challenge HUD](images/hud/challenge_hud.png)
<figcaption>Challenge room progress indicator</figcaption>
</figure>
</div>

---

## Dungeon Map

The dungeon map shows a top-down view of the dungeon layout as you explore it. Rooms are revealed as you enter them.

<div class="ur-gallery">
<figure>

![Dungeon Map Full](images/hud/dungeon_map_full.png)
<figcaption>Dungeon map showing explored and unexplored rooms</figcaption>
</figure>
<figure>

![Dungeon Map Minimap](images/hud/dungeon_map_mini.png)
<figcaption>Minimap view in the corner of the screen</figcaption>
</figure>
</div>

### Map Features

| Feature | Description |
|---------|-------------|
| Room Icons | Different icons for room types (combat, treasure, shop, boss) |
| Player Markers | Shows party member positions |
| Fog of War | Unexplored rooms are hidden |
| Current Room | Highlighted with a border indicator |
| Door Status | Shows locked/unlocked door states |

---

## Controls

| Action | Key |
|--------|-----|
| Toggle HUD | `/ur togglehud` |
| Toggle Top Camera | `/ur topcamera` |
| Revive Teammate | Hold F near revive marker |
| Open Shop | Walk near shopkeeper NPC |
| Open Party UI | `/ur party ui` |

---

## Related Pages

- [Getting Started](getting-started.md) -- Camera controls and basics
- [Party System](party-system.md) -- Party status details
- [Enemies and Bosses](enemies-bosses.md) -- Boss health bar context
- [Dungeon Levels](dungeon-levels.md) -- Room types shown on the map
