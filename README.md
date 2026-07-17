# PriceCheck — RuneScape flip tracker + flip finder

The [PriceCheck](https://flipping.pricecheck.gg) plugin for Old School RuneScape
flipping. The flip log is free for everyone; the ranked flip board is part of
PriceCheck's paid tiers.

## Free: the flip log

Works with no account and no key. Everything runs locally in your client:

- Every GE fill logged the moment it lands, exact to the coin, GE tax included.
- Buys matched into flips (FIFO with exact cost), open positions with cost
  basis and hold time.
- Session profit with honest active-time gp/hr, daily and weekly totals, win
  rate, tax paid. Margin checks are tagged and kept out of the win rate.
- Right-click any flip or position to delete it.

Optional, off by default: **Sync flip log** backs the log up to your PriceCheck
account, shows it at [flipping.pricecheck.gg/portfolio](https://flipping.pricecheck.gg/portfolio),
and keeps the log consistent across multiple computers. Keys are free (Discord
login).

## Paid: the flip board

With a Trader Pro key the sidebar adds the live flip board (post-tax margins
ranked by expected gp/hr), an offer advisor for your open GE slots,
click-to-fill prices in the GE, your flips inside the GE item search, and a
bank-aware slot planner. All numbers come from PriceCheck's servers; the
plugin renders them.

## Data disclosure

The plugin makes no network requests until you enter a plugin key. With a key,
requests to PriceCheck's servers necessarily include your IP address. Optional
features, each off by default and individually toggled, send exactly what
their config warning states: the flip-log sync sends your GE trades and open
positions with an anonymous per-account identifier; "Contribute market data"
sends your own GE offer fill events; the planner's capital auto-detect sends
your liquid gp total. Your RSN, game credentials, and chat are never read or
sent. PriceCheck's servers are not controlled or verified by the RuneLite
Developers.

## Build

```
./gradlew build
```

Java 11. The sideloadable jar lands in `build/libs/`.

## Test locally (developer mode)

1. Copy `build/libs/pricecheck-<version>.jar` into `~/.runelite/sideloaded-plugins/`
   (Windows: `%USERPROFILE%\.runelite\sideloaded-plugins\`).
2. Launch RuneLite with `--developer-mode`.
3. The PriceCheck panel appears in the sidebar; the flip log works immediately.

## Layout

- `FlipLogEngine` — the local ledger: exact fills from offer-delta math, FIFO
  flip matching, atomic persistence per game account,
  multi-machine adoption.
- `PriceCheckPlugin` — lifecycle, event intake, pollers.
- `PriceCheckPanel` — sidebar (Flips, Log, Plan, Settings tabs).
- `PriceCheckApiClient` — all network calls; a Bearer key and JSON in/out.
- `GeChatboxHelper` — click-to-fill price lines and GE search suggestions.
- `TelemetryCollector` — the opt-in market-data contribution queue.
- `GeTax` — GE tax exactly as the game applies it (2% floored, 5m cap).

Not affiliated with Jagex Ltd. RuneScape and Old School RuneScape are
trademarks of Jagex Ltd.
