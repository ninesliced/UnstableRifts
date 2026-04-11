---
title: "Shops"
order: 3
published: true
draft: false
---

# Shops

Each dungeon level contains one shop room with a shopkeeper NPC. Shops are safe zones -- no enemies will spawn in a shop room.

![Shop Room](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/shop/shop_room.png)
_A shop room with the shopkeeper NPC and displayed items_
![Shopkeeper](https://raw.githubusercontent.com/ninesliced/UnstableRifts/refs/heads/main/img/shop/shopkeeper.png)
_The shopkeeper NPC_

---

## How to Buy

1. Enter the shop room. It is always a safe zone with no enemies.
2. Walk up to the shopkeeper NPC.
3. The shop UI opens automatically when you are within range.
4. Browse available items. Each item shows its stats, rarity, and price.
5. Click an item to open the purchase confirmation panel.
6. Confirm to spend team coins and receive the item.

---

## Payment

Shops use shared party coins. Any teammate can buy items, and the cost comes from the same coin pool.

| Source | Description |
|--------|-------------|
| Enemy drops | Coins dropped by defeated enemies |
| Crate loot | Coins found in destructible crates |
| Boss drops | Guaranteed coin drops from boss kills |

See [Getting Started](getting-started-1#coins) for detailed coin drop rates.

---

## Item Categories

| Category | Description |
|----------|-------------|
| Weapons | Randomly generated with rarity and elemental effects |
| Armor | Armor pieces with set bonuses and stat rolls |
| Consumables | Healing items and ammo refills |
| Utility | Special items and buffs |

---

## Pricing

- Base prices are set when the run starts.
- One-time purchase items become unavailable after being bought.
- Repeatable items (like consumables) get more expensive each time you buy them:

$$\text{Price} = \text{Base Price} + (\text{Purchase Count} \times \text{Price Step})$$

---

## Shop Refresh

Some shops have a refresh button to reroll the item list:

| Property | Description |
|----------|-------------|
| Refresh Cost | Costs team coins (varies by room) |
| Effect | Rerolls all unsold items |

> **Tip:** If you do not like the current items, refresh the shop to roll a new list.
---

## Shop Details

- Items are displayed as physical entities on the ground in the shop room for a preview.
- The shop UI shows full stat details before purchase: damage, protection, rarity, set bonus, elemental effect.
- Shop inventory is independent per dungeon level. Moving to the next level gives you a new shop.
- Shop state is cleared when the party leaves the dungeon or progresses to the next level.

---

## Related Pages

- [Weapons](weapons-1) -- Weapon stats and rarity tiers
- [Armor Sets](armor-sets-1) -- Armor stats and set bonuses
- [Getting Started](getting-started-1) -- Coin economy basics
- [Dungeon Levels](dungeon-levels-1) -- Shop room placement
