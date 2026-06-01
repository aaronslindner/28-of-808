# Ultimate Normie Mode

A RuneLite plugin that enforces strict UIM-style restrictions on banking, trading and the Grand Exchange, layered with a roguelike **Upgrade Mode** meta-progression.

By default the plugin is fully strict: no banking, GE submissions clamped to ±10% of market price, and player trades clamped to ±10% of partner value. Upgrades are purchased per-life by incinerating coins, and **all upgrades are wiped on death**.

## How Upgrade Mode works

1. Open the **UNM Upgrades** sidebar panel and click **[Set]** on an available upgrade.
2. **Enable the Bank Incinerator** in the bank's settings (it's not on by default).
3. Walk to a bank, open it, deposit fresh coins, then drag them onto the **incinerator**. Each coin destroyed counts toward your selected goal.
4. **Only coins deposited during the current bank session count.** Coins already in the bank when you open it are *ineligible* — convert them to platinum tokens at a GE clerk if you want to keep them off the bank.
5. **Closing the bank with eligible coins still inside makes them ineligible on reopen** (so you can't bank coins between sessions; you must incinerate fully before closing).
6. **Dying wipes all progress and active upgrades.** Start over from strict.

## Upgrade tree

```
[Banking Unlock           1B GP]   (standalone)

[GE Use                   1M GP]
   └─[GE ±25%              5M GP]
       └─[GE Removal      50M GP]

[Trade Use                1M GP]
   └─[Trade ±25%           1M GP]
       └─[Trade Removal   10M GP]
```

A child upgrade can only be purchased while its parent is **active this life**. Each tier strictly improves on the previous: GE/Trade `Use` unlocks access at ±10%, the `±25%` tier loosens to ±25%, and `Removal` removes the price/value enforcement entirely.

## Features

### Banking

- All banking interactions blocked unless the **Banking Unlock** upgrade is active OR a goal is selected.
- While saving, the bank widget is allowed to open for incineration only — non-coin operations are blocked, and only coins deposited during the current session count toward the goal.
- Clearing the goal while the bank is open force-closes the bank.
- Loot chests, deposit boxes, and CoX private storage units follow the same rule.
- Looting bag remains fully functional regardless of upgrades.

### Trading

- Trade requests blocked entirely unless **Trade Use** is active.
- ±10% value tolerance by default; loosens to ±25% with **Trade ±25%**; fully removed with **Trade Removal**.
- Same-tick exploit guard, item-change cooldown, and trade-balance overlay on the first screen.
- Unpriced item guard refuses trades containing items with no known market price.

### Grand Exchange

- GE access blocked entirely unless **GE Use** is active. Collect, Exchange, History on bankers/clerks/booths blocked too.
- ±10% price tolerance by default; loosens to ±25% with **GE ±25%**; fully removed with **GE Removal**.
- Custom-entry cooldown prevents rapid confirm after manual price/quantity entry.

### Cosmetic

- **Chat skull icon** prepended to your in-game messages, scaled by active upgrade count:
  - 0 active: white base skull
  - 1–2 active: red colored skull
  - 3–4 active: red horned skull
  - 5+ active: gilded horned skull
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
