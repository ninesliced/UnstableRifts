---
title: "HUD and Interface"
order: 2
published: true
draft: false
---

# HUD and Interface

Unstable Rifts uses a custom HUD made for top-down dungeon play. You can show or hide it with `/ur togglehud`.

![Full HUD Overview](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/hud/hud_overview.png)
_Complete HUD layout during gameplay_

---

## HUD Elements

### Ammo Display

Shows current ammo and reload state. It updates as you shoot and reload.
![Ammo HUD](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/hud/ammo_hud.png)
_Ammo counter with reload indicator_

### Boss Health Bar

Shows during boss fights. It displays the boss name and remaining health. Color changes as HP gets low:

| Health Range | Color |
|-------------|-------|
| 75-100% | Green |
| 50-75% | Yellow |
| 25-50% | Orange |
| 0-25% | Red |
![Boss Health Bar](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/hud/boss_health.png)
_Boss health bar during the Forklift fight_

### Dungeon Info

Shows dungeon name, level, and room info in a corner of the screen.
![Dungeon Info](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/hud/dungeon_info.png)
_Dungeon info panel showing level name and progress_

### Party Status

Shows all party members, their HP, and connection status. Disconnected players appear grayed out.
![Party Status](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/hud/party_status.png)
_Party status panel with 4 members_

### Death and Revive

When a player dies, a revive prompt appears for nearby teammates. The HUD shows:

| Element | Description |
|---------|-------------|
| Revive Marker | Appears at the death location |
| Progress Bar | Fills as a teammate holds F to revive |
| Timer | 30-second window to revive before removal |

### Portal Prompt

Appears when a portal is available after the boss is defeated. It tells you when it is time to move on.
---

## Challenge HUD

During challenge rooms, a special HUD element appears showing the room's objective and progress.
![Challenge HUD](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/hud/challenge_hud.png)
_Challenge room progress indicator_
---

## Dungeon Map

The map shows the dungeon from above. Rooms appear as you discover them.
![Dungeon Map Full](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/map/dungeon_map_full.png)
_Dungeon map showing explored and unexplored rooms_
![Map Legend](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/map/map_legend.png)
_Map legend_

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

- [Getting Started](getting-started-1) -- Camera controls and basics
- [Party System](party-system-1) -- Party status details
- [Enemies and Bosses](enemies-bosses-1) -- Boss health bar context
- [Dungeon Levels](dungeon-levels-1) -- Room types shown on the map
