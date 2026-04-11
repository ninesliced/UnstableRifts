---
title: Shops
description: In-dungeon shop mechanics and purchases in Unstable Rifts
---

<style>
:root { --ur-accent: #bb2f2c; --ur-border: #2a2a4a; --ur-text-muted: #8892a4; }
.ur-gallery { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; } .ur-gallery figure { flex: 1 1 200px; max-width: 320px; margin: 0; text-align: center; } .ur-gallery figure img { width: 100%; border-radius: 6px; border: 1px solid var(--ur-border); } .ur-gallery figure figcaption { font-size: 0.82em; color: var(--ur-text-muted); margin-top: 4px; }
.ur-info-card { border-left: 3px solid var(--ur-accent); padding: 10px 16px; margin: 14px 0; background: rgba(187,47,44,0.05); border-radius: 0 4px 4px 0; }
</style>

# Shops

Each dungeon level contains one shop room with a shopkeeper NPC. Shops are safe zones -- no enemies will spawn in a shop room.

<div class="ur-gallery">
<figure>

![Shop Room](images/shop/shop_room.png)
<figcaption>A shop room with the shopkeeper NPC and displayed items</figcaption>
</figure>
<figure>

![Shop UI](images/shop/shop_ui.png)
<figcaption>Shop purchase interface showing item details</figcaption>
</figure>
</div>

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

Shops use the shared team coin pool. Any party member can make purchases, and the cost is deducted from the team's total.

| Source | Description |
|--------|-------------|
| Enemy drops | Coins dropped by defeated enemies |
| Crate loot | Coins found in destructible crates |
| Boss drops | Guaranteed coin drops from boss kills |

See [Getting Started](getting-started.md#coins) for detailed coin drop rates.

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

- Base prices are determined when the dungeon generates.
- One-time purchase items become unavailable after being bought.
- Repeatable items (consumables) increase in price with each purchase:

$$\text{Price} = \text{Base Price} + (\text{Purchase Count} \times \text{Price Step})$$

---

## Shop Refresh

Some shop rooms include a refresh option that regenerates the available item stock:

| Property | Description |
|----------|-------------|
| Refresh Cost | Costs team coins (varies by room) |
| Effect | Regenerates all unsold items with new random rolls |
| Limit | Available based on room configuration |

<div class="ur-info-card">

**Tip:** If the shop doesn't have the weapon or armor you need, try refreshing the stock. The refresh re-rolls all items, including their rarity and elemental effects.
</div>

---

## Shop Details

- Items are displayed as physical entities on the ground in the shop room for a preview.
- The shop UI shows full stat details before purchase: damage, protection, rarity, set bonus, elemental effect.
- Shop inventory is independent per dungeon level. Moving to the next level gives you a new shop.
- Shop state is cleared when the party leaves the dungeon or progresses to the next level.

---

## Related Pages

- [Weapons](weapons.md) -- Weapon stats and rarity tiers
- [Armor Sets](armor.md) -- Armor stats and set bonuses
- [Getting Started](getting-started.md) -- Coin economy basics
- [Dungeon Levels](dungeon-levels.md) -- Shop room placement
