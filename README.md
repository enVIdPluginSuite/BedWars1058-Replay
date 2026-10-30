# BedWars1058-Replay

A BedWars1058 add-on (Kotlin, Gradle) that records every match automatically and lets players watch it back in a private copy of the map, Hypixel-style, with hotbar controls to change speed, skip around and follow any player. Minecraft 1.8 up to whatever BedWars1058 itself supports (1.21.x at the time of writing).

**Invisible players keep their name and health on screen, and spectating shows the held item again with a locked look so the hand cannot sway.**

* Group: `dev.etran` - package `dev.etran.bedreplay`
* Version: `1.3.1`
* Kotlin `2.2.21`, Java 8 bytecode, Gradle (Kotlin DSL), shaded + relocated Kotlin stdlib and HikariCP
* Required dependency: [`packetevents`](https://github.com/retrooper/packetevents) 2.x (free, actively maintained)
* Optional: `BedWars1058` (or the `BedWars2023` fork) - without one of them there is nothing to record; `AdvancedReplay` for export

## What changed in this version

Includes everything in 1.3.0.

**Fixed**
* **Invisible players now show their name tag and health** - modern clients hide the name tag of an invisible player even when the team says "always show", so the name (with team color and heart row) and the health number now float above an invisible player on invisible armor stands. The body stays invisible, the armor stays visible, and punching the floating text still spectates that player.
* **Spectating shows the held item again** - the client is no longer switched to spectator mode by default, so your hand shows what the followed player holds. To stop the hand from moving on its own, your look is locked to the followed player's every tick: the hand only sways when *they* look around. Your own body stays invisible and hidden from other viewers, as in 1.3.0.
* `playback.spectate-client-mode` is now **opt-in** (`false` by default). Turn it on for real client spectator mode: no hand or hotbar at all, with name, health and held item in the action bar.

## Build

```
./gradlew build        (Windows: gradlew.bat build)
```

The included Gradle wrapper pins Gradle 8.14.3, which needs a JDK between 17 and 24 (the plugin itself targets Java 8).
IntelliJ picks the wrapper up automatically. The server-ready jar is
`build/libs/BedWars1058-Replay-1.3.1.jar` (the dependency-free shadow jar, not a plain one).

## Install

1. Install PacketEvents and BedWars1058 on the server.
2. Drop `BedWars1058-Replay-1.3.1.jar` into `plugins/` and **restart** (avoid hot-loading with plugin managers).
3. Play a match - it is recorded automatically. `/replay` afterwards.

## Commands

| Command | What it does |
|---|---|
| `/replay` | Opens the replay menu (aliases: `/replays`, `/bedreplay`) |
| `/replay play <id>` | Starts a replay by id |
| `/replay stop` | Leaves the replay you are watching |
| `/replay reload` | Reloads `config.yml` (admin; storage changes need a restart) |
| `/replay export <id> [world]` | Exports a replay to AdvancedReplay (admin, needs AdvancedReplay) |
| `/replay inv` | While spectating: opens the followed player's inventory |
| `/replay save` | Guests: saves the replay you are watching, if the host allows it |
| `/replaysjoin [player]` | Opens the active-replays menu, or joins that player's replay |
| `/replaytransfer <player>` | Host only: hands the host role to another viewer |

## Permissions

| Node | Default | Purpose |
|---|---|---|
| `bedreplay.use` | true | Open the replay menu and watch your own matches (and join shared replays) |
| `bedreplay.admin` | op | See and manage every replay, `reload`, `export`, bypass viewer command lock |

## The menus and controls

* **Replay list** (`/replay`) - one entry per match you played in (admins can switch to every match). Shows date, length, players, winner and time until deletion.
  Click = watch. Right-click = keep forever / extend / delete (if allowed by the config).
  Replays saved to your list by the host's permission show up here too. Bottom row: **Sharing settings** and **Active replays**.
* **Timeline** - match events (kills, bed breaks, eliminations, game end); click to jump a few seconds before.
* **Inventory views** - a player's inventory, armor and ender chest at the current moment; chests and furnaces that were opened during the match, as they were at that time.
* **Sharing menu** - allow joining, whitelist only, guests may save, whitelist editor, transfer host.
* **Active replays** - replays other people are watching that you are allowed to join (host, map, viewers, time).
* **Hotbar** (host) - 1 slower, 2 back 10s, 3 pause / play, 4 forward 10s, 5 faster, 6 skip to time (left-click: type it, right-click: pick a moment), 9 **Replay menu**.
  Guests only get slot 9.
* **Replay menu** - teleport to a player (left-click = teleport, right-click = first-person view, shift-click = their inventory) or to another viewer, match events, watch yourself, stop following, sharing settings (host), save this replay (guests, if allowed), leave.
* **Punch to spectate** - with an empty slot selected, punch any replayed entity to view through its eyes; sneak to stop. While spectating, your body is invisible to everyone, your hand shows what the player holds, and right-click or `/replay inv` opens their inventory.

## Configuration

Everything is in `plugins/BedWars1058-Replay/config.yml` (colour codes with `&`).

* `storage` - `FILE` (default, `plugins/BedWars1058-Replay/replays`) or `MYSQL`.
* `recording` - master switch, minimum length, post-game seconds, flush interval, and toggles for chat, blocks, entities, sounds, titles, scoreboards and inventories.
* `retention` - `default-days` (0 = never delete), keep-forever / extend rules, who may delete.
* `playback` - concurrent replay limit, speeds, skip seconds, sounds, invisible-player display, return world.
* `sharing` - starting defaults for allow-join / whitelist-only / guest-save, lobby announcement, max viewers per replay.
* `playback.health-display` - `OFF`, `NUMBER`, `HEARTS` or `BOTH`; `playback.spectate-client-mode` - opt-in real client spectator mode while spectating (no hand, info in the action bar).
* `menu` and `messages` - every title and message is configurable.

## How one jar covers 1.8 - latest

The plugin compiles against the **1.8.8** Spigot API only. Everything that does not exist there is reached by reflection or by name lookup,
so no removed class or constant is ever linked:

* Packet capture and the packet-only replay entities (players, mobs, items, camera) go through PacketEvents, which handles the protocol differences.
* Materials and block states are resolved by name / PacketEvents state strings, so old data values and new block states both work.
* Newer Bukkit APIs (profiles, scoreboards, attributes) are called reflectively and degrade gracefully when missing.
* `api-version: 1.13` stops modern servers from treating the plugin as legacy; older servers ignore it.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.
