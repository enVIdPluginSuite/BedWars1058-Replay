# BedWars1058-Replay

A BedWars1058 add-on (Kotlin, Gradle) that records every match automatically and lets players watch it back in a private copy of the map, Hypixel-style, with hotbar controls to change speed, skip around and follow any player. Minecraft 1.8 up to whatever BedWars1058 itself supports (1.21.x at the time of writing).

**Bug-fix release: fixes the crash that stopped playback from starting (`Player is not allowed to fly`).**

* Group: `dev.etran` - package `dev.etran.bedreplay`
* Version: `1.0.1`
* Kotlin `2.2.21`, Java 8 bytecode, Gradle (Kotlin DSL), shaded + relocated Kotlin stdlib and HikariCP
* Required dependency: [`packetevents`](https://github.com/retrooper/packetevents) 2.x (free, actively maintained)
* Optional: `BedWars1058` (or the `BedWars2023` fork) - without one of them there is nothing to record; `AdvancedReplay` for export

## What changed in this version

* **Fixed:** starting a replay could fail with `IllegalArgumentException: Player is not allowed to fly (check #getAllowFlight())` and the viewer was never taken into the replay world.
  Entering a different world can reset flight (world / game-mode plugins), so flight is now re-enabled after the teleport, once more shortly afterwards, and re-checked every second while the replay runs.
* Nothing else changed - recording, storage and the format are identical to 1.0.0.

## Build

```
gradle build
```

Needs Gradle 8.x and JDK 17 or newer (the plugin itself targets Java 8). The server-ready jar is
`build/libs/BedWars1058-Replay-1.0.1.jar` (the dependency-free shadow jar, not a plain one).

## Install

1. Install PacketEvents and BedWars1058 on the server.
2. Drop `BedWars1058-Replay-1.0.1.jar` into `plugins/` and **restart** (avoid hot-loading with plugin managers).
3. Play a match - it is recorded automatically. `/replay` afterwards.

## Commands

| Command | What it does |
|---|---|
| `/replay` | Opens the replay menu (aliases: `/replays`, `/bedreplay`) |
| `/replay play <id>` | Starts a replay by id |
| `/replay stop` | Leaves the replay you are watching |
| `/replay reload` | Reloads `config.yml` (admin; storage changes need a restart) |
| `/replay export <id> [world]` | Exports a replay to AdvancedReplay (admin, needs AdvancedReplay) |

## Permissions

| Node | Default | Purpose |
|---|---|---|
| `bedreplay.use` | true | Open the replay menu and watch your own matches |
| `bedreplay.admin` | op | See and manage every replay, `reload`, `export`, bypass viewer command lock |

## The menus and controls

* **Replay list** (`/replay`) - one entry per match you played in (admins can switch to every match). Shows date, length, players, winner and time until deletion.
  Click = watch. Right-click = keep forever / extend / delete (if allowed by the config).
* **Timeline** - match events (kills, bed breaks, eliminations, game end); click to jump a few seconds before.
* **Inventory views** - a player's inventory, armor and ender chest at the current moment; chests and furnaces that were opened during the match, as they were at that time.
* **Hotbar** - 1 slower, 2 back 10s, 3 pause / play, 4 forward 10s, 5 faster, 6 skip to time (left-click: type it, right-click: pick a moment),
  7 teleport to player (left-click = teleport, right-click = first-person view, shift-click = their inventory), 8 match events, 9 leave replay.

## Configuration

Everything is in `plugins/BedWars1058-Replay/config.yml` (colour codes with `&`).

* `storage` - `FILE` (default, `plugins/BedWars1058-Replay/replays`) or `MYSQL`.
* `recording` - master switch, minimum length, post-game seconds, flush interval, and toggles for chat, blocks, entities, sounds, titles, scoreboards and inventories.
* `retention` - `default-days` (0 = never delete), keep-forever / extend rules, who may delete.
* `playback` - concurrent replay limit, speeds, skip seconds, sounds, invisible-player display, return world.
* `menu` and `messages` - every title and message is configurable.

## How one jar covers 1.8 - latest

The plugin compiles against the **1.8.8** Spigot API only. Everything that does not exist there is reached by reflection or by name lookup,
so no removed class or constant is ever linked:

* Packet capture and the packet-only replay entities (players, mobs, items, camera) go through PacketEvents, which handles the protocol differences.
* Materials and block states are resolved by name / PacketEvents state strings, so old data values and new block states both work.
* Newer Bukkit APIs (profiles, scoreboards, attributes) are called reflectively and degrade gracefully when missing.
* `api-version: 1.13` stops modern servers from treating the plugin as legacy; older servers ignore it.

## Known limitations

* Not every server version and plugin combination has been tested - report problems with the console log attached.
* Sidebars from other plugins are only recorded if they use the standard scoreboard packets.
* Baby, slime size and sheep colour are saved but not yet drawn in playback.
* The AdvancedReplay export is a subset (no sounds, titles, scoreboards, name tags, inventories, per-player chat).

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.
