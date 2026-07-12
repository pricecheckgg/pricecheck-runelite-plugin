# PriceCheck — RuneLite plugin

Live OSRS flip margins from [PriceCheck Premium](https://premium.pricecheck.gg), in your client.

This is a **thin client**. It contains no margin logic and no data: it sends your
plugin key to `premium.pricecheck.gg/api/plugin` and shows what comes back. The
server re-checks your subscription on every request, so the plugin goes empty the
moment your subscription lapses — there is nothing in here to bypass.

**No secrets live in this repo.** Your plugin key is entered in the plugin config
at runtime, never committed. That is why the whole source is safe to publish
(including to the RuneLite Plugin Hub later) — this repo is deliberately separate
from the pricecheck.gg server code.

## Get a key
premium.pricecheck.gg → **Account → RuneLite plugin → Generate plugin key**.
Copy it (shown once), paste it into the plugin's **Plugin key** config field.

## Build (on a machine with JDK 11)
```
./gradlew shadowJar
```
The sideloadable JAR lands in `build/libs/pricecheck-<version>.jar`.

## Run it locally to test (developer mode)
1. Put `pricecheck-<version>.jar` in `~/.runelite/sideloaded-plugins/`
   (Windows: `%USERPROFILE%\.runelite\sideloaded-plugins\`).
2. Launch RuneLite with `--developer-mode` (a source/dev build, or the client jar
   with the flag). The plugin appears in the sidebar.
3. Paste your plugin key in the config; the panel fills with live flips.

> End users will NOT do the above — the one-click installer (built separately)
> automates the folder-drop + developer-mode launch on the official client. This
> section is for our own testing.

## Layout
- `PriceCheckPlugin` — lifecycle, 6s poll, sidebar button.
- `PriceCheckConfig` — plugin key (secret), panel toggle, min-EV filter.
- `PriceCheckApiClient` — the only network call; Bearer key → GET /api/plugin/flips; maps 401/403 to auth states.
- `FlipData` — matches the server's trimmed plugin shape.
- `PriceCheckPanel` — the sidebar list (name, buy→sell, profit, EV/hr; ✓ = confirmed).

## TODO (next)
- `src/main/resources/icon.png` (24×24 brand coin) — the nav button falls back to a blank icon until then.
- Grand Exchange in-world overlays (draw each item's margin on the GE slots) — needs on-device testing of the GE widget layout.
- Per-item lookup (`/api/plugin/item/:geId`, `/items?ids=`) when an item is examined/searched.

Not affiliated with Jagex.

## Dev launch on macOS + Jagex account (what actually works)
`run-dev.sh` runs RuneLite in developer mode from the client the Jagex Launcher
already downloaded (`~/.runelite/repository2`). Hard-won details baked in:
- `-ea` (developer mode requires assertions) and `-Xmx2g`.
- Classpath is jars only with **no trailing `:`** — a trailing empty entry makes
  the JVM scan `$HOME` (crawls ~/Library/Mail etc.), which OOMs / hangs load.
- No extra api jar: `--developer-mode` auto-loads DevTools, which wants a
  `VarbitID` class the launcher's stripped runtime-api omits; adding the full
  gameval jar fixes DevTools but is ~2000 classes to scan and drags load, so we
  let DevTools fail (harmless — we don't use it).
- Jagex login: run `RuneLite.app --insecure-write-credentials` once, log in, which
  writes `~/.runelite/credentials.properties`; the dev client then auto-logs in.
  Delete that file (or hit "End sessions" on runescape.com) when done — it bypasses your password.

Usage: `./gradlew shadowJar` → copy the jar into `~/.runelite/sideloaded-plugins/`
→ `./run-dev.sh`.
