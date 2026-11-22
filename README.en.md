# Feeder Restock

English | [简体中文](README.md)

A Minecraft Java **1.21.11 / Fabric** client mod. It takes the **whole remaining shortfall** of the selected Litematica placement's material list from **containers registered with mapart-feeder**. You move the player yourself and decide when to restock; it runs **unobtrusively** and makes the printer stop a few ticks before a container is opened.

## Only mapart-feeder registered containers

This mod **never searches for chests** and no longer scans nearby blocks: the only allowed source is `config/mapartfeeder.json`, the container list owned by mapart-feeder.

- Mark a chest once with mapart-feeder's **K** key and both mods share that single list; this mod only **reads** the file and never writes it back.
- If mapart-feeder is missing, the file is absent, or the list is empty, the mod does **not** fall back to looking around: it only tells you that nothing is registered yet.
- A registered chest far away is never opened from a distance: restocking happens only after **you walk into interaction range**.
- Either half of a double chest counts as the same container. Selection follows mapart-feeder's survey order: known sources first, then unopened chests by distance, and chests known to hold nothing useful are skipped.

## Unobtrusive mode (on by default)

- **No chest screen pops up** — only that container screen is suppressed on the client; the server still opens the container and syncs its inventory normally.
- **The mouse is never released and the camera never moves.**
- **The hotbar selection never switches** — restocking always uses the current main hand. Vanilla container blocks (chests, trapped chests, barrels, shulker boxes in the world) consume the right-click and report success.
- **No action-bar spam** — only occasional hints while idle.

## Print pausing

Printing stops **a few ticks before the container opens** (`preOpenPauseTicks`, default 5 ticks ≈ 0.25 s), stays paused through the open and the transfer, the container is closed the moment the batch ends, and printing resumes about 0.3 s later. A hard cap (`visitPauseCapTicks`, default 40 ticks ≈ 2 s) guarantees printing always resumes on its own, without pressing anything and without changing your Litematica or printer settings.

The pause covers both printing paths: Litematica's **Easy Place** and the **litematica-printer** print module (its per-tick print is skipped directly, its switch is not touched).

## Features

- **F8** toggles it; rebind it under Options → Controls → Key Binds → "Auto restock (mapart-feeder)".
- **F7** opens a graphical settings screen (four tabs: Interaction / Taking / Timeouts / Feedback). Changes save immediately and there is a "reset to defaults" button; vanilla widgets only.
- The shortfall comes from the material list of the **currently selected and enabled** placement only.
- Supports chests/trapped chests, barrels, and shulker boxes placed in the world; a double chest counts once.
- Uses the player's real interaction distance and line-of-sight collision: no extra reach, no through-wall pickup, no walking, no player movement.
- Moves only the materials the list needs; items are matched by "item + components".
- Merges into non-full stacks first, then uses empty slots; never armour, crafting, or offhand slots.
- **Batch Shift-transfer**: vanilla `QUICK_MOVE` processes every needed source slot consecutively in the same tick; only a final shortfall smaller than one stack is split precisely.
- After each settlement it reports **what was taken and how many** in chat and on the action bar.
- **Closes right after taking, verifies after closing**: the snapshot request and the close leave in the same tick, so the chest is never held open for a round trip.
- **Paused rather than printing wrongly**: whenever the active screen handler is not the player inventory or an item sits on the cursor, Easy Place, the printer and this mod's own place/attack do nothing.
- **No repeated checks of the same container**: one record per continuous encounter, rearmed only after leaving its interaction range (1-block hysteresis), plus a capacity-based retry.
- **Container highlighting from the cache**: registered containers nearby get an outline — **green** when the cache says they still hold part of the current deficit (with what and how much), **yellow** when they were never surveyed, **grey** when they are known to hold nothing needed; the top-left corner lists the nearest ones with distances. Only registered chests are marked, within `highlightRange`.
- **Container contents are recorded only after a real open with a full server snapshot**; entries expire (about 5 minutes).
- Material-list semantics follow upstream: multiplier 1 uses `missing`, above 1 uses `total × multiplier`, minus one `available`; user-ignored entries are kept.
- Containers out of stock or unopenable go on cooldown instead of being opened and closed repeatedly.

## Installation

1. Back up your world and test in a throwaway environment first.
2. Keep your existing Fabric API, Litematica and MaLiLib; the printer is optional. **mapart-feeder is required**, otherwise there is no container to take from.
3. Put `feeder-restock-<version>.jar` into the instance's `mods` folder (not the `-sources.jar`).
4. In game, mark raw-material chests with mapart-feeder's **K**, select and enable your placement, enable Easy Place as usual, then press **F8**.

Auto restock is off by default after start-up, disconnect, or a dimension change.

> **Upgrading from older versions**: since 0.6.0 the mod id, package and config file were renamed. Quit the game, remove the old `rail-restock-*.jar` from `mods`, then add the new JAR. The old `config/railrestock.json` is no longer read; set your options again with F7 (defaults now match the new behaviour).

## Settings screen (F7)

Press **F7** in game. Every option uses a vanilla widget, applies on click and is written to `config/feederrestock.json` immediately; "reset to defaults" restores every value.

| Tab | Contents |
| --- | --- |
| Interaction | Unobtrusive opening, pause before opening, pause after taking, restock pause cap, wait after takeover |
| Taking | Scan interval, batch interval, container cache lifetime, revisit after taking, capacity retry, empty cooldown |
| Timeouts | Open timeout, sync timeout, demand refresh interval |
| Feedback | Highlight registered containers, highlight range, automatic recount, chat notifications, action bar status |

- No ModMenu or Cloth Config dependency.
- Opening the screen **pauses** an active restock (and safely closes the container it opened); closing it resumes normally.
- Number options are sliders limited to the configuration's legal range.

## Configuration

`config/feederrestock.json` is created on first start. Most options can be changed in game with **F7**; edit the file only while the game is closed.

| Key | Default | Meaning |
| --- | ---: | --- |
| `unobtrusiveInteraction` | `true` | Hide the automatically opened container screen |
| `autoRecountMaterialList` | `false` | Recount the building material list automatically; off by default |
| `scanIntervalTicks` | 5 | Idle interval for checking whether registered containers are reachable |
| `clickIntervalTicks` | 2 | Delay between click batches when the per-tick packet budget is exceeded |
| `openTimeoutTicks` | 40 | Timeout for opening and the full inventory sync |
| `syncTimeoutTicks` | 60 | Timeout for the pre-count and the settlement confirmation |
| `emptyContainerCooldownTicks` | 200 | Minimum retry delay for an empty or unopenable container |
| `successCooldownTicks` | 20 | Retry delay after a capacity-limited visit |
| `containerRevisitTicks` | 600 | Minimum revisit delay after taking everything (30 s) |
| `recountIntervalTicks` | 200 | Idle demand-refresh interval |
| `manualPauseTicks` | 60 | Wait after a manual takeover |
| `preOpenPauseTicks` | 5 | Ticks to pause printing before opening (0 disables the head start) |
| `printPauseTicks` | 6 | Extra ticks of pause after the last slot action (≈0.3 s) |
| `visitPauseCapTicks` | 40 | Maximum ticks one restock may keep printing paused (≈2 s) |
| `knowledgeExpireTicks` | 6000 | How long surveyed container contents stay trusted (≈5 min) |
| `highlightContainers` | true | Mark registered containers from the cache: green = has material, yellow = unsurveyed, grey = nothing needed |
| `highlightRange` | 48 | Maximum highlight distance in blocks (8–128) |
| `chatNotifications` | true | Chat notifications |
| `actionbarStatus` | true | Action-bar status while idle |

20 ticks is about one second on a normal server.

### About the automatic recount

`autoRecountMaterialList` decides whether this mod triggers Litematica's material count at all. Each count makes Litematica print its own "scheduled task added" line and run a HUD progress bar.

- **Off (default)**: the mod never schedules a count and only uses the last completed one; after the build progressed the shortfall can be a bit too large until you refresh the list in Litematica yourself.
- **On**: the deficit is recounted before each restock and periodically while idle, at the cost of those Litematica messages.

The first read of each placement still triggers Litematica's own lazy count. After that, keeping the option off stops further counts.

Either way, a successful restock updates the available counts silently, which is what prevents the same material from being taken twice.

## Registration and selection

- Only the `containers` array of `config/mapartfeeder.json` (dimension + coordinates) is used. A chest registered with K in game is picked up within about a second, without restarting.
- **Order**: containers known to hold the missing material (scored by `useful × 64 ÷ (distance + 1)`), then never-opened containers nearest-first, and containers known to hold nothing useful are skipped.
- **Contents are cached** only after a real open with a full server snapshot or after the settlement sync; taking items subtracts from the cache.
- **Expiry**: entries expire after `knowledgeExpireTicks` (≈5 minutes); toggling F8 off and on, switching placements or the counting scope clears them immediately.
- The file is read-only for this mod; if it is being rewritten, the last successfully parsed list stays in effect.

## Known limits

- Container contents cannot be known before opening. Moving very fast (elytra, sprint-jumping) may be too quick for a full open and sync.
- Before splitting a cursor stack, the inventory must hold **the entire stack that might be picked up**. With only a few free slots it may hold back the last few items.
- The settlement request relies on 1.21.11's stale-revision correction. If a server plugin rejects it, the visit ends after `syncTimeoutTicks` without reopening or replaying anything.
- A chest that was surveyed as empty and then refilled cannot be noticed from a distance; the cache expires after about 5 minutes, or press F8 twice to re-check immediately.
- If the server force-closes the container or another plugin rewrites the inventory, vanilla behaviour can still affect the final placement.
- Non-vanilla container blocks are only attempted with an empty main hand, so nothing can be misplaced.
- Hiding the screen is **client-side only**: the server sees a normal container open. Use it only in worlds or on servers where automatic restocking is allowed.

## Repository layout

```
feeder-restock/
├── src/main/java/dev/feederrestock/     mod sources
│   ├── client/        settings screen
│   ├── config/        configuration
│   ├── integration/   reflection adapters for Litematica / printer / mapart-feeder
│   ├── logic/         pure logic with unit tests
│   ├── mixin/         injection points
│   └── runtime/       restock state machine and interactions
├── src/main/resources/                 metadata, mixin config, language files
├── src/test/java/                      unit tests and bytecode guards
├── tools/                              build environment scripts (see tools/README.md)
├── dist/                               installable releases (see dist/README.md)
├── build.gradle / settings.gradle / gradle.properties
├── build.cmd                           Windows build entry point
├── README.md / README.en.md / CHANGELOG.md / GAME_TEST_CHECKLIST.md / LICENSE
└── .gitignore
```

`build/`, `.gradle/` and `logs/` are generated; deleting them changes nothing. The Gradle distribution and dependency cache live in the parent session folder (`.gradle-dist/`, `.gradle-user-home/`).

## Building

```bat
build.cmd build
```

`tools\prepare.cmd` prepares the isolated Gradle distribution on first use. The jar is written to `build/libs/feeder-restock-<version>.jar`.

## Notes

- The material list is read through Litematica's public getters via reflection, not through version-specific `toNbt`/`toJson` serialisers, and it does not occupy Litematica's single completion listener.
- This repository does not include Litematica, MaLiLib, Fabric API or mapart-feeder, and never modifies their files.

## License

**MIT**, see [LICENSE](LICENSE).

```
Copyright (c) 2026 CNGM222
https://github.com/CNGM222/feeder-restock
```
