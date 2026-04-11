---
title: "Commands"
order: 3
published: true
draft: false
---

# Commands

All commands use the base command `/unstablerifts` with the alias `/ur`.

---

## Player Commands

### Party Commands

| Command | Arguments | Description |
|---------|-----------|-------------|
| `/ur party create [privacy]` | Optional: `public` or `private` | Create a new party (default: private) |
| `/ur party invite <player>` | Player name | Invite an online player to your party |
| `/ur accept` | -- | Accept your most recent party invite |
| `/ur join` | -- | Alias for `/ur accept` |
| `/ur party join <id>` | Party ID or leader name | Join a public party or accept a specific invite |
| `/ur party kick <player>` | Player name | Remove a member from your party (leader only) |
| `/ur party privacy <setting>` | `public` or `private` | Change party visibility (leader only) |
| `/ur party leave` | -- | Leave your current party |
| `/ur party disband` | -- | Delete your party (leader only) |
| `/ur party list` | -- | Display all available public parties |
| `/ur party start` | -- | Launch party into a dungeon instance (leader only) |
| `/ur party ui` | -- | Open the party management UI |
**Note:** Party invites expire after 5 minutes. The maximum party size is 4 players.

### Dungeon Commands

| Command | Arguments | Description |
|---------|-----------|-------------|
| `/ur dungeon <level>` | Dungeon name | Create and enter a dungeon instance |

### Camera and UI

| Command | Aliases | Description |
|---------|---------|-------------|
| `/ur topcamera` | `/ur tcam` | Toggle top-down camera mode |
| `/ur togglehud` | `/ur hud` | Toggle all HUD elements on/off |

### Camera Rotation

When the top-down camera is active, it supports four rotation angles:

| Rotation | Angle |
|----------|-------|
| 0 | 0 degrees (default) |
| 1 | 90 degrees |
| 2 | 180 degrees |
| 3 | 270 degrees |

---

## Admin Commands

These commands require operator permissions or the `unstablerifts.admin` permission node.

### Portal and Loot

| Command | Arguments | Description |
|---------|-----------|-------------|
| `/ur giveportal` | -- | Place an Ancient Party Portal item in your hand |
| `/ur loot [rarity]` | Optional: `common`, `uncommon`, `rare`, `epic`, `legendary`, `unique` | Spawn a random weapon drop at your feet |

### Coin Management

| Command | Arguments | Description |
|---------|-----------|-------------|
| `/ur coins get` | -- | Show current team money |
| `/ur coins set <amount>` | Coin amount | Set team money to a specific value |
| `/ur coins add <amount>` | Coin amount | Add coins to team money |
| `/ur coins reset` | -- | Reset team money to 0 |
| `/ur coins list` | -- | List all active games and their money |

### Debug / Pickup Tracking

| Command | Description |
|---------|-------------|
| `/ur pickup status` | Show item tracker statistics |
| `/ur pickup list` | List all tracked items |
| `/ur pickup nearby` | List tracked items within pickup radius |
| `/ur pickup prune` | Remove invalid tracked items |
| `/ur pickup clear` | Clear all tracked items |

---

## Related Pages

- [Party System](systems/party-system-1) -- Detailed party mechanics
- [Getting Started](getting-started-1) -- Quick start guide
- [Installation](installation-1) -- Server setup and admin tools
