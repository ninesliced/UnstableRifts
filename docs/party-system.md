---
title: "Party System"
order: 10
published: true
draft: false
---



# Party System

Unstable Rifts is designed for cooperative play. The party system lets you team up with up to 3 other players before entering the dungeon.
![Party UI](images/party/party_ui.png)
_Party management UI showing member list and controls_
![Party Portal](images/party/party_portal.png)
_Ancient Party Portal -- used to create a party_
---

## Quick Start

1. Create a party: `/ur party create` (or `/ur party create public` for a public party)
2. Invite friends: `/ur party invite <player>`
3. Friends accept: `/ur accept`
4. Start the run: `/ur party start`

---

## Commands

| Command | Description | Who Can Use |
|---------|-------------|-------------|
| `/ur party create [privacy]` | Create a new party (default: private) | Anyone |
| `/ur party invite <player>` | Invite a player | Leader |
| `/ur accept` | Accept your most recent invite | Invited player |
| `/ur join` | Alias for accept | Invited player |
| `/ur party join <id>` | Join a public party by ID or leader name | Anyone |
| `/ur party kick <player>` | Remove a member | Leader |
| `/ur party privacy <setting>` | Set public or private | Leader |
| `/ur party leave` | Leave the party | Any member |
| `/ur party disband` | Delete the party | Leader |
| `/ur party list` | Show available public parties | Anyone |
| `/ur party start` | Launch into the dungeon | Leader |
| `/ur party ui` | Open party management interface | Any member |

---

## Party Rules

| Rule | Value |
|------|-------|
| Maximum party size | 4 players |
| Invite expiry | 5 minutes |
| Privacy options | Public (anyone can join) / Private (invite only) |
| Party name | Auto-generated: "{Leader}'s Party" |
| Leader transfer | Not supported -- if leader leaves, party is disbanded |

---

## Features

### Shared Coins

All coins earned during a dungeon run are added to a shared team pool. Any party member can spend coins at shops.

### Reconnection

If a party member disconnects during a dungeon run, they are tracked as a disconnected member. When they reconnect to the server, they are automatically returned to the dungeon at their last position.

The party member count always includes both active and disconnected members. This prevents new players from joining an in-progress run to fill a disconnected player's slot.

### Party UI

The party management UI (`/ur party ui`) provides:

- **Create Party** card when you're not in a party
- **Incoming Invites** section with accept buttons
- **Member List** with status indicators for each player
- **Leader Controls**: invite, kick, set privacy, start run, disband
- **Member Controls**: leave party
- **Public Parties** browser for joining open parties

### Dungeon Entry

When the leader starts the run:

1. All online party members are teleported to the dungeon instance
2. Each player's return position is saved for after the run
3. Starting equipment is distributed (Pistol + Crystal Sword)
4. The dungeon begins generating
**Note:** Only online members are teleported. Disconnected members will be teleported when they reconnect.
---

## Party Lifecycle

| Phase | Description |
|-------|-------------|
| Created | Party exists, members can be invited |
| Ready | At least 1 member, leader can start |
| In Dungeon | Party is running a dungeon instance |
| Completed | Dungeon run finished, members returned to overworld |
| Disbanded | Party destroyed (leader left, or manually disbanded) |

---

## Related Pages

- [Commands](commands.md) -- Full command reference
- [Getting Started](getting-started.md) -- How to play
- [HUD and Interface](hud-interface.md) -- Party status HUD elements
