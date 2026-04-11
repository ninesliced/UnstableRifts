---
title: Rarity, Modifiers & Effects
order: 2
published: true
---

# Rarity, Modifiers & Effects

Every weapon in Unstable Rifts is generated at runtime with a rarity tier, optional elemental effect, and a set of random stat modifiers. No two drops are guaranteed to be identical.

---

## Rarity Tiers

Rarity is rolled from a weighted table each time a weapon spawns. Higher tiers are rarer but carry better effects and more modifier slots.

| Tier | Spawn Chance | Modifier Slots | Effect Chance | Effect DoT Duration | Glow |
|------|:-----------:|:--------------:|:-------------:|:-------------------:|------|
| <span class="rarity-basic">Basic</span> | 45% | 0 | 0% | — | None |
| <span class="rarity-uncommon">Uncommon</span> | 25% | 1 | 5% | 0.55 s | Green |
| <span class="rarity-rare">Rare</span> | 15% | 2 | 10% | 0.60 s | Blue |
| <span class="rarity-epic">Epic</span> | 8% | 3 | 20% | 0.70 s | Purple |
| <span class="rarity-legendary">Legendary</span> | 5% | 4 | 50% | 0.85 s | Gold |
| <span class="rarity-unique">Unique</span> | 2% | 5 | 100% | 1.00 s | Red |

**Effect DoT Duration** is the base 0.5 s plus a rarity bonus (Uncommon +0.05 s, Rare +0.1 s, Epic +0.2 s, Legendary +0.35 s, Unique +0.5 s).

> [!NOTE]
> BASIC weapons always drop without an effect or any modifiers. UNIQUE weapons always carry a damage effect.

---

## Elemental Effects

When the effect roll succeeds, one of five effects is chosen at random (equal 20% weight each). Weapons with a *locked* effect (e.g. all Muskets) always apply that effect regardless of rarity.

| Effect | Trail | Entity Status | Description |
|--------|-------|--------------|-------------|
| <span class="effect-fire">FIRE</span> | Orange | `Flame_Staff_Burn` | Sets the target ablaze, dealing damage over time. |
| <span class="effect-ice">ICE</span> | Blue | `Slow` | Chills the target, reducing movement speed. |
| <span class="effect-electricity">ELECTRICITY</span> | Yellow | `Stun` | Stuns the target briefly on each hit. |
| <span class="effect-void">VOID</span> | Purple | `UnstableRifts_Void_Portal_DOT` | Inflicts void corruption, dealing periodic void damage. |
| <span class="effect-acid">ACID</span> | Green | `UnstableRifts_Poison` | Poisons the target, dealing damage over time. |

The DoT duration shown in the rarity table above applies to all five effects equally — higher rarity weapons inflict longer-lasting status effects.

---

## Weapon Modifiers

Modifiers are bonus stats rolled onto a weapon when it has available modifier slots (Uncommon and above). Each slot rolls an independent modifier type from the eligible pool for the weapon's category.

| Modifier | Display Name | Applies To | Rolled Bonus |
|----------|-------------|-----------|:------------:|
| `MAX_BULLETS` | Max Ammo | Laser, Bullet, Summoning | +10 – 30% |
| `ATTACK_SPEED` | Speed | All categories | +20% (fixed) |
| `ADDITIONAL_BULLETS` | Pellets | Laser only | +1 – 2 pellets |
| `WEAPON_DAMAGE` | Damage | Laser, Bullet, Melee | +10 – 30% |
| `PRECISION` | Precision | Laser, Bullet | +30 – 50% |
| `KNOCKBACK` | Knockback | Laser, Bullet, Melee | +10 – 20% |
| `MAX_RANGE` | Range | Laser, Bullet | +30 – 50% |
| `MOB_HEALTH` | Mob HP | Summoning only | +20 – 50% |
| `MOB_DAMAGE` | Mob Dmg | Summoning only | +20 – 50% |
| `MOB_LIFETIME` | Mob Life | Summoning only | +20 – 50% |

### Modifier Eligibility Rules

Certain modifiers are automatically excluded based on the weapon's base stats:

- **Precision** — excluded on weapons with spread ≤ 0.05 (lasers or pinpoint weapons gain nothing from it).
- **Pellets** — excluded on single-shot weapons (base pellets ≤ 1).
- **Knockback** — excluded on weapons with no base knockback.

This means modifier pools vary per weapon. A weapon with no spread, 1 pellet, and no knockback loses three potential modifier types, making remaining modifiers more likely to be rolled again if a weapon has multiple slots.

---

## Rolling Summary

1. **Rarity** is drawn from the weighted table (BASIC 45 %, UNCOMMON 25 %, …). A minimum rarity floor per weapon definition prevents a strong weapon from rolling too low.
2. **Effect** — if the weapon has a locked effect it is always applied; otherwise the rarity's effect chance determines whether a random effect is rolled.
3. **Modifiers** — one modifier is drawn per available slot from the eligible pool for that weapon category, with the value rolled uniformly in the listed range and rounded to 2 decimal places.
