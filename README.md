# Ultimate Normie Mode

A RuneLite plugin that enforces UIM-style restrictions on banking, trading and the Grand Exchange, with a built-in wealth leaderboard.

## Features

### Banking

* **Bank blocking** - Prevents all bank interactions (banks, deposit boxes, talk-to banker). Collect and use-on-banker are still allowed.
* **Widget safety net** - Force-closes the bank interface if opened by any means.
* **Loot chest restrictions** - Blocks bank variants on loot chests while allowing take/withdraw-to-inventory.
* **Looting bag** - Fully functional; not restricted.

### Trading

* **Trade validation** - Blocks trades where either side's offer deviates more than a configurable percentage (default 10%, capped at 5M GP) from the other.
* **Unpriced item guard** - Blocks trades containing items with no known market price.
* **Same-tick exploit guard** - Silently blocks accept attempts that occur in the same game tick as an offer change.
* **Trade balance overlay** - Displays a visual comparison of both sides' offer values on the first trade screen.

### Grand Exchange

* **GE validation** - Blocks Grand Exchange offers whose price deviates beyond the configured limits from market price.
* **Custom entry cooldown** - Prevents rapid confirm after manual price/quantity entry.

### Leaderboard

* **Wealth tracking** - Calculates total wealth from inventory, equipment, and looting bag (including untradeables via store/death value).
* **Automatic posting** - Posts wealth to the leaderboard on logout and world hop.
* **Side panel** - Displays your personal rank at the top, with the full leaderboard below (20 per page with prev/next navigation).
* **Manual refresh** - Refresh button to fetch the latest leaderboard data on demand.

### Cosmetic

* **Chat skull icon** - Prepends a skull icon to your chat messages.
* **Denial sound** - Plays a sound effect when an action is blocked.

