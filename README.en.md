# Rail Restock · Minecart Auto-Restock

[简体中文](README.md) | English

A client-side **Minecraft Java 1.21.11 / Fabric** mod. It fills the **entire remaining shortfall** of the selected Litematica placement's material list from nearby containers that can be opened normally. It keeps scanning while the minecart is moving, and runs in **unobtrusive mode** by default.

## Unobtrusive mode (on by default since 0.2.0)

While restocking:

- **No chest screen pops up** — only that container screen is suppressed on the client; the server still opens the container and syncs its inventory normally.
- **The mouse is never released and the camera never moves** — because the screen never actually opens, the mouse stays captured by the game.
- **The hotbar selection never switches** — restocking always uses the current main hand. Vanilla container blocks (chests, trapped chests, barrels, and shulker boxes placed in the world) consume the right-click and report success, so the block in your hand is never placed; no need to find an empty hand or switch slots.
- **No action-bar spam** — only occasional hints while idle; the HUD stays clean during transfers.

The trade-off: Easy Place is still **paused for a very short moment** — only the few ticks that actually move items (`printPauseTicks`, default 6 ≈ 0.3 s), no longer the open and verification waits. The pause is a deadline rather than a visit-wide lock: even if the server synchronization is slow or verification times out, printing resumes on its own without pressing anything again, and none of your Litematica settings are changed.

### Two preconditions to know

1. **Do not sneak while restocking** (sneaking cancels block interaction and uses the held item instead). If sneaking or item use is detected, that restock pass is skipped instead of taking the risk.
2. **You can open your inventory normally while restocking.** Opening the inventory, chat, or similar screens **pauses** restocking (and safely closes the container it opened); closing the screen resumes it immediately, with no need to press F8 again.

## Features

- **F8** toggles it; rebind it under Options → Controls → Key Binds → "Minecart Auto-Restock".
- The shortfall comes from the material list of the **currently selected and enabled** placement only; other placements are not merged in.
- Supports chests/trapped chests, barrels, and shulker boxes placed in the world; a double chest counts once.
- Uses the player's real interaction distance and line-of-sight collision: no extra reach, no through-wall pickup, no walking, no minecart control.
- Moves only the materials the list needs; items are matched by "item + components", not merged by name alone.
- Merges into non-full stacks of the same item first, then uses empty slots; never uses armor, crafting, or offhand slots as destinations.
- **Batch Shift-transfer from one container**: uses vanilla `QUICK_MOVE` and processes all needed source slots consecutively in the same tick; only a final shortfall smaller than one stack is split precisely, so it finishes back-to-back instead of waiting per item.
- When the inventory has only partial capacity, quick transfer is still used if the Shift amount does not exceed the shortfall; it never grabs a whole stack just to be fast.
- After each settlement it reports **what was taken and how many** in chat and on the action bar, e.g. `已取 圆石×64、橡木木板×32（共 96 个）`; the action-bar summary stays for about 5 seconds.
- On settlement it requests the server's full inventory **within the current container session**, cross-checks the inventory increase against the container decrease, and closes only once after confirmation — **no reopen-and-verify**; client-side predictions do not count as success.
- **No repeated checks of the same container while standing still**: both empty and fully-taken containers keep their visit record, and the cooldown expiring does not reopen them. Checking is allowed again only after leaving the container's interaction range (with a 1-block buffer); both halves of a double chest share one record. A container skipped for lack of capacity is retried only after inventory space actually increased and more items can be taken, which supports printing in place and continuing after making room. Toggling F8 off and on re-checks nearby containers immediately; switching placements or the counting scope also resets the records.
- **Shares the raw-material containers registered by mapart-feeder**: chests marked with **K** in `config/mapartfeeder.json` are used directly, with no separate registration here; that file is only read, never written.
- **Follows mapart-feeder's chest-survey order**: chests already known to hold the missing material come first (scored by `useful × 64 ÷ distance`), never-opened chests are surveyed nearest-first, and chests known to hold nothing useful are no longer reopened.
- **Container contents are recorded only after a real open with a full server snapshot**, never guessed at a distance; entries expire automatically (about 5 minutes) and toggling F8 off and on clears them immediately.
- Without mapart-feeder installed it falls back to the previous nearby scan, with no errors.
- Material-list semantics follow upstream: multiplier 1 uses `missing`, greater than 1 uses `total × multiplier` minus one `available`; user-ignored entries are kept.
- Containers that are out of stock or cannot be opened go on cooldown instead of being opened and closed repeatedly.

## Installation

1. Back up your world and test in a test environment first.
2. Keep your existing Fabric API, Litematica, and MaLiLib as they are; Printer is not required.
3. Put `rail-restock-<version>.jar` into the instance's `mods` folder (do not use the `-sources.jar`).
4. Launch the game, select and enable the placement you want to print, enable Easy Place as usual, then press **F8**.

After launch, disconnect, or a dimension change, auto-restock is off by default and its state is not remembered. When upgrading, quit the game and remove the old JAR first; do not run two versions side by side.

## Configuration

`config/railrestock.json` is generated on first launch; edit it after quitting the game and restart for changes to take effect.

| Field | Default | Description |
| --- | ---: | --- |
| `unobtrusiveInteraction` | `true` | Unobtrusive mode: hides the container screen opened automatically. Set to `false` to show the chest screen |
| `autoRecountMaterialList` | `false` | Automatically recompute the build's missing list. Off by default, so the mod never triggers a recount by itself (and therefore there is no Litematica "task added" message or HUD statistics) |
| `scanIntervalTicks` | 5 | Idle scan interval |
| `clickIntervalTicks` | 2 | Interval before the next batch when an extreme split exceeds the 128-packet budget of a single tick; normal Shift batches do not wait per slot |
| `openTimeoutTicks` | 40 | Timeout for opening a container and receiving its full inventory |
| `syncTimeoutTicks` | 60 | Timeout for pre-count and confirmation |
| `emptyContainerCooldownTicks` | 200 | Minimum retry interval when an empty/unopenable container comes into range again; expiry itself does not reset the visit record |
| `successCooldownTicks` | 20 | Minimum retry interval when capacity was insufficient; inventory space must still actually increase |
| `containerRevisitTicks` | 600 | Minimum re-check interval when passing a fully-taken container again (30 s by default); standing still does not re-check on expiry |
| `recountIntervalTicks` | 200 | Update build demand while idle |
| `manualPauseTicks` | 60 | Wait after manual takeover |
| `printPauseTicks` | 6 | How long Easy Place stays paused after the last slot action (≈0.3 s); it always expires on its own |
| `includeChests` / `includeBarrels` / `includeShulkerBoxes` | true | Which containers to scan |
| `shareFeederContainers` | `true` | Read the containers registered by mapart-feeder (read-only `config/mapartfeeder.json`) and use its survey order |
| `onlyMarkedContainers` | `false` | Open only chests registered in mapart-feeder; unmarked containers are never touched |
| `knowledgeExpireTicks` | 6000 | How long a surveyed container's contents stay trusted (≈5 min); expired entries are surveyed again so a refilled chest is not skipped forever |
| `chatNotifications` | true | Chat notifications |
| `actionbarStatus` | true | Action-bar status while idle |

20 ticks is about 1 second on a normal server.

### About automatic recounting

`autoRecountMaterialList` controls **whether this mod triggers Litematica's material statistics automatically**. Every recount makes Litematica print a "task added" line in chat and show progress on the HUD — that is Litematica's own message, not this mod's, so the only way to remove it is to not trigger recounts.

- **Off (default)**: the mod no longer schedules any recount task, so apart from the brief restock hints it is almost unnoticeable. It computes the shortfall from **the latest existing recount result**; after build progress changes the shortfall will be too large, possibly taking a few extra materials, and you need to refresh the list manually in Litematica to correct it.
- **On**: recomputes the build's missing amount before each restock and periodically while idle, for the most accurate shortfall. The cost is the messages and HUD statistics above.

Also, the **first** time each placement is read by this mod, Litematica's own lazy loading still runs one recount (possibly one message); afterwards, as long as the option is off, it will not trigger again.

Regardless of the setting, after a successful restock the mod **silently** updates the available inventory counts (this step triggers no Litematica message and is key to avoiding duplicate transfers).

## Sharing containers with mapart-feeder

Both mods read the same registry: a chest marked with **K** in mapart-feeder is written to `config/mapartfeeder.json`, and this mod reads its `containers` array (dimension + coordinates), so **one registration serves both**.

- **Read-only**: this mod never edits or rewrites that file; mapart-feeder remains its only owner. A chest you mark in-game is picked up within about a second, with no restart.
- **Chest order** (mapart-feeder's survey logic):
  1. containers known to hold the missing materials, by `useful × 64 ÷ (distance + 1)`, highest first;
  2. containers never opened, nearest first, so their contents get surveyed;
  3. containers known to hold nothing useful are skipped entirely.
- **Contents are cached** only after a real open with a full server snapshot, or after the settlement sync; taking items subtracts from the cache, so the same chest is not treated as full again.
- **Freshness**: entries expire after 5 minutes (`knowledgeExpireTicks`) and are surveyed again; toggling F8 or switching the placement clears them immediately.
- **Registered only**: set `onlyMarkedContainers` to `true` to open registered chests exclusively and never touch anyone else's containers.
- Without mapart-feeder, or without the file, it falls back to the plain nearby scan without errors.

Restocking still happens **only within real interaction range**: a registered chest far away is never opened remotely; it is used when the minecart passes it.

## Known limits

- The contents of an unopened container cannot be known in advance; it must really be opened and wait for the server to sync. If the minecart is too fast this may not finish in time, so slow down or stop during the restocking segment.
- Before splitting a cursor stack, the inventory must be able to hold **the entire cursor stack that might be picked up**, leaving room to put it back if the container closes unexpectedly. With only a few free slots, it may therefore hold back the last few items.
- The full-sync request for the current session relies on vanilla 1.21.11's stale-inventory version correction. If a server plugin rejects the request, it ends after `syncTimeoutTicks` without reopening, replaying, or counting the prediction as success.
- If a checked empty chest is refilled externally, the client cannot know remotely; the cache expires after about 5 minutes and is surveyed again, or toggle F8 off and on to re-check immediately.
- If the server force-closes the container or another plugin rewrites the inventory, vanilla behavior can still affect the final placement. After an interruption the mod does not treat predictions as success.
- Non-vanilla container blocks (modded chests, etc.) are only attempted when the main hand is empty, to avoid placing items by mistake.
- Hiding the screen is **client-side only**: the server just sees a normal container opening. Use it only in worlds or on servers that allow automatic restocking.

## Building

On Windows:

```bat
build.cmd build
```

The first run needs `tools\prepare.cmd` to prepare the isolated Gradle distribution. The build keeps Gradle caches, the Java user directory, and temporary directories in the parent (session) directory of the project, so it does not pollute system directories.

Output: `build/libs/rail-restock-0.3.1.jar`.

## Notes

- The material list is read via reflection on Litematica's public getters, **without relying on version-specific persistence APIs** such as `toNbt`/`toJson`, and without occupying Litematica's single-slot completion listener.
- This project does not bundle Litematica / MaLiLib / Fabric API, nor modify their files.

## License

This project is licensed under the **MIT License**; see [LICENSE](LICENSE) for the full terms.

You may freely use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, provided that the copyright notice and this permission notice are retained in copies or substantial portions of the Software.

```
Copyright (c) 2026 CNGM222
https://github.com/CNGM222/rail-restock
```
