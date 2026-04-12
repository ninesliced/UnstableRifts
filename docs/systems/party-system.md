---
title: "Party System"
order: 1
published: true
draft: false
---

# Party System

Unstable Rifts is built for co-op. The party system lets you team up with up to 3 other players before entering the dungeon.

![Party UI](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/party/party_ui.png)
_Party management UI showing member list and controls_
![Party Portal](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/getting-started/ancient_party_portal.png)
_Ancient Party Portal - used to create a party_

---

## Quick Start

1. Start from an Ancient Party Portal, a crafted portal, or `/ur party ui`
2. Create a party: `/ur party create` (or `/ur party create public` for a public party)
3. Invite friends: `/ur party invite <player>`
4. Friends accept: `/ur accept`
5. Start the run: `/ur party start` or use the party UI

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
| Leader transfer | Not supported - if leader leaves, party is disbanded |

---

## Features

### Shared Coins

All coins earned during a dungeon run are added to a shared team pool. Any party member can spend coins at shops.

### Reconnection

If a player disconnects during a run, the game keeps their slot. When they reconnect, they are sent back into the run.

Disconnected players still count as party members, so another player cannot take that slot mid-run.

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

1. All online party members are teleported into the dungeon
2. Each player's return position is saved for after the run
3. Starting equipment is distributed (Pistol + Crystal Sword)
4. The run starts

> **Note:** Only players who are online are teleported immediately. Disconnected players rejoin when they reconnect.
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

- [Commands](commands-1) - Full command reference
- [Getting Started](getting-started-1) - How to play
- [HUD and Interface](hud-and-interface-1) - Party status HUD elements
