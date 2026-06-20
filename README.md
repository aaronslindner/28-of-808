# Ultimate Normie Mode

A RuneLite plugin that enforces strict UIM-style restrictions on banking, trading and the Grand Exchange, layered with a roguelike **Upgrade Mode** meta-progression.

By default the plugin is fully strict: no banking, GE access blocked, and player trades blocked. Upgrades are purchased per-life by incinerating coins, and **all upgrades are wiped on death**.

## How Upgrade Mode works

1. Open the **UNM Upgrades** sidebar panel and click **[Set]** on an available upgrade.
2. **Enable the Bank Incinerator** in the bank's settings (it's not on by default).
3. Walk to a bank, open it, deposit fresh coins, then drag them onto the **incinerator**. Each coin destroyed counts toward your selected goal.
4. **Only coins deposited during the current bank session count.** Coins already in the bank when you open it are *ineligible*.
5. **Closing the bank with eligible coins still inside makes them ineligible on reopen** (so you can't bank coins between sessions; you must incinerate fully before closing).
6. **Dying wipes all progress and active upgrades.** Start over from strict.

## Upgrade tree

```
[Bank Unlock              1M GP]   (standalone)

[GE Use                 100K GP]
   └─[GE ±25%           500K GP]
       └─[GE Removal     1M GP]

[Trade Use                1K GP]
   └─[Trade ±25%          5K GP]
       └─[Trade Removal  10K GP]
```

A child upgrade can only be purchased while its parent is **active this life**. Each tier strictly improves on the previous: GE/Trade `Use` unlocks access at ±10%, the `±25%` tier loosens to ±25%, and `Removal` removes the price/value enforcement entirely.

### Consumable passes

These single-use passes don't grant a permanent unlock; each purchase grants one action charge. Cost starts at 1K GP and rises by 1K GP per purchase this life.

- **Deposit Pass** — one non-coin deposit into the UNM bank.
- **Withdrawal Pass** — one non-coin withdrawal from the UNM bank.
- **GE Pass** — opens one temporary Grand Exchange session; access is revoked when you close the GE.

## Features

### Banking

- All banking interactions blocked unless the **Bank Unlock** upgrade is active, a goal is selected, or a one-shot Deposit/Withdrawal Pass is pending.
- While saving, the bank widget is allowed to open for incineration only — only coins deposited during the current session count toward the goal.
- Non-coin deposits and withdrawals are allowed one-at-a-time via **Deposit Pass** / **Withdrawal Pass**.
- Clearing the goal while the bank is open force-closes the bank.
- Looting bag remains fully functional regardless of upgrades.

### UNM Bank

- Items deposited using a **Deposit Pass** are stored in the **UNM Bank** and can be withdrawn using a **Withdrawal Pass**.
- The UNM Bank is **not** the normal bank; it is a separate stash tied to your current life.
- Items in the UNM Bank are moved to **Purgatory** on death.

### Purgatory

- When you die, items in the UNM Bank move to **Purgatory**.
- The next life can unlock Purgatory by incinerating the amount spent on deposit/withdrawal passes in the prior life (minimum 1 gp).
- If you die again while Purgatory has items, those items are lost forever.

### Trading

- Trade requests blocked entirely unless **Trade Use** is active.
- ±10% value tolerance by default; loosens to ±25% with **Trade ±25%**; fully removed with **Trade Removal**.
- Same-tick exploit guard, item-change cooldown, and trade-balance overlay on the first screen.
- Unpriced item guard refuses trades containing items with no known market price.

### Grand Exchange

- GE access blocked entirely unless **GE Use** is active or a **GE Pass** is used.
- ±10% price tolerance by default; loosens to ±25% with **GE ±25%**; fully removed with **GE Removal**.
- Custom-entry cooldown prevents rapid confirm after manual price/quantity entry.
- GE **"Collect to bank"** is blocked — coins/items must be collected to inventory so the incinerator can track them.

### Cosmetic

- **Chat skull icon** prepended to your in-game messages, scaled by unlocked upgrade count:
  - 0: white
  - 1: yellow
  - 2: orange
  - 3: red
  - 4: green
  - 5: blue
  - 6: black
  - 7: black horned
- **Denial sound** (sound 2277) plays when an action is blocked.

## Persistence & data

- All upgrade state is stored locally per RuneScape profile via the RuneLite config (`unmupgrade` group). Nothing is sent off-device.
- No leaderboard, no telemetry, no API keys, no third-party servers.
- Death triggers a hard wipe of progress AND active upgrades for the dying character only.

## Building from source

```
gradlew run
```

Compiles, downloads the latest RuneLite client, registers the plugin via `ExternalPluginManager.loadBuiltin`, and launches the dev client.
