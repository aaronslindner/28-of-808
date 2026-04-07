# Ultimate Pker

A RuneLite plugin that enforces restrictions on banking, trading and the Grand Exchange.

## Features

- **Bank blocking** - Prevents all bank interactions.
- **Trade validation** - Blocks trades where either side's offer deviates more than a configurable percentage (default 10%, capped at 5M GP) from the other.
- **GE validation** - Blocks Grand Exchange offers whose price deviates beyond the same limits from market price.
- **Same-tick exploit guard** - Silently blocks submit/accept attempts that occur in the same game tick as an offer change.
- **Unpriced item guard** - Blocks trades containing items with no known market price.
- **Trade balance overlay** - Displays a visual comparison of both sides' offer values on the first trade screen.
- **Chat skull icon** - Prepends a skull icon to your chat messages.
