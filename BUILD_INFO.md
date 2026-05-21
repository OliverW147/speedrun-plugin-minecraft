# Manhunt Mod — Build & Project Info

## Overview
Custom Fabric mod for Minecraft **1.16.1** dedicated server.
Built from scratch because no compatible Fabric manhunt mod exists for 1.16.1.

---

## Build Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Java | 17+ (tested with Eclipse Adoptium JDK 21) | Java path: `C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot` |
| Gradle | **8.8** | Must be 8.x — Gradle 7.x fails with Java 21+ (class file version error) |
| fabric-loom | 1.6-SNAPSHOT | fabric-loom 0.6 is incompatible with Gradle 8 |

Gradle 8.8 distribution is stored at: `D:\manhunt-build\gradle-dist\gradle-8.8\`

---

## Building

There is **no** Gradle wrapper (`gradlew.bat`) in this project — use the bundled Gradle 8.8
distribution directly, and point `JAVA_HOME` at the Adoptium JDK.

```powershell
cd D:\manhunt-build
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
& "D:\manhunt-build\gradle-dist\gradle-8.8\bin\gradle.bat" clean build --offline
```

**Use `clean build`, not `jar`.** Two reasons:
- This is a Fabric/loom project — the deployable jar is produced by the `remapJar` task (which
  `build` runs). The plain `jar` task only makes the *unmapped* dev jar in `build\devlibs\` and does
  **not** produce the server-ready jar.
- Incremental builds can go stale: `gradle jar` may report "UP-TO-DATE" and keep old compiled
  classes even after source edits. `clean` forces a real recompile.

Output jar (the single file you deploy): `build\libs\manhunt-1.0.0.jar`
(`build\devlibs\manhunt-1.0.0-dev.jar` is an intermediate loom artifact — never deploy it.)

To deploy to server:
```powershell
Copy-Item "build\libs\manhunt-1.0.0.jar" "D:\server\mods\manhunt-1.0.0.jar" -Force
```

Then restart the Minecraft server.

---

## Dependencies (build.gradle)

```
minecraft:        com.mojang:minecraft:1.16.1
yarn mappings:    net.fabricmc:yarn:1.16.1+build.21:v2
fabric-loader:    net.fabricmc:fabric-loader:0.11.3
fabric-api:       net.fabricmc.fabric-api:fabric-api:0.18.0+build.387-1.16.1
```

The server already has `fabric-api-0.18.0+build.387-1.16.1.jar` installed — the mod depends on it at runtime.

---

## Server Mod Folder

Location: `D:\server\mods\`

Required mods:
- `fabric-api-0.18.0+build.387-1.16.1.jar` (already present)
- `manhunt-1.0.0.jar` (built and deployed here)

Note: `ManhuntPlus-1.5.0 (1).jar` is a **Spigot plugin** and will never load on Fabric — delete it.

---

## Commands

| Command | Description |
|---------|-------------|
| `/hunter <player>` | Assign player as hunter |
| `/runner <player>` | Assign player as runner |
| `/manhunt start` | Start the game (needs ≥1 hunter and ≥1 runner) |
| `/manhunt stop` | End the game |
| `/manhunt status` | Show current game state, roles, settings |
| `/manhunt setgrace <seconds>` | Set grace period duration (0–300s, default 0) |
| `/manhunt setrange <blocks>` | Set compass dead zone radius (0–10000, default 20) |
| `/manhunt compass` | Give **the sender** a fresh tracker compass (refused if the sender is a runner; must be run by a player) |

All player name arguments support **tab autocomplete**.
`/hunter` and `/runner` can be used **mid-game** — a new hunter is equipped with a compass immediately; a new runner is put back in play (survival, un-eliminated).

---

## Features

- **Grace period**: Hunters are frozen at game start for a configurable duration. Countdown titles shown at 10, 5, 4, 3, 2, 1 seconds.
- **Tracking compass**: Given to each hunter on start. Uses lodestone NBT to point at a runner in real-time (updates every second). By default it tracks the **nearest** runner.
- **Togglable target (multi-runner)**: Each hunter **right-clicks** their compass to cycle which runner it tracks: `nearest → runner₁ → runner₂ → … → nearest` (runners ordered alphabetically). The choice is **per hunter** — each hunter tracks independently. The compass renames itself to `Tracker: <name>` (or `<name> (nearest)`) and the action bar names the tracked runner. If the tracked runner leaves the game, that hunter's compass reverts to nearest automatically.
- **Compass restored on death**: If a hunter dies, their compass is automatically restored when they respawn.
- **Dead zone**: Within a configurable XZ radius of the runner, the compass spins and shows "<runner> is nearby!" — prevents exact pinpointing.
- **Cross-dimension**: If the tracked runner is in another dimension, the compass points at the runner's **last known position in the hunter's dimension** (if the runner has ever been there). If the tracked runner has **never** visited the hunter's current dimension, the compass **spins** and shows "<runner> is in another dimension!". Visited-dimension history is tracked per runner.
- **Multiple runners — elimination, not instant win**: When a runner dies (or disconnects), they are **eliminated**, not game-over. They are put into **spectator** (via a normal respawn — never `/kill`) and continuously kept in the **dimension of the lowest-index (first-assigned) alive runner**, with a free camera, until the game ends. The game continues until **all** runners are eliminated.
- **Win conditions** (manhunt is a team game — runners win/lose together):
  - **All** runners eliminated (dead/disconnected) → "Hunters win!"
  - The Ender Dragon is killed by **any** means → "Runners win!" (see dragon note below)
  - **All hunters disconnect** → "Runners win!"
  - `/manhunt stop` → manual end
  - On any end, eliminated runners are restored to **survival at their spawn point**.
- **Disconnect = death**: A runner who logs off mid-game is treated as eliminated.
- **Every-tick polling**: Death/elimination, dimension tracking, spectator locking, and compass updates run every tick (not every 20), so the right-click target switch is effectively instant.
- **No coordinates, no distance** shown to hunters — action bar only shows proximity/dimension messages.

---

## Key Implementation Notes

- `Text.of()` does not exist in 1.16.1 — must use `new LiteralText(String)`.
- `ServerEntityCombatEvents` and `ServerPlayerEvents` don't exist in fabric-api 0.18 — death detection uses tick-based `player.isDead()` polling instead.
- `broadcastChatMessage` in 1.16.1 requires 3 args: `(Text, MessageType, UUID)`.
- Dragon kill is detected via the **End's `EnderDragonFight`** (`ServerWorld.getEnderDragonFight()`), NOT the `kill_dragon` advancement. The advancement (`Free the End`) only fires when a *player is credited with the killing blow* — **bed/respawn-anchor explosion kills do not credit a player**, so advancement-based detection misses them. Instead we watch the fight: win when `hasPreviouslyKilled()` flips from the game-start baseline, or when a dragon seen alive this game disappears with the fight marked killed (covers re-kills after a crystal respawn). This works for melee, beds, command kills — anything.
- Compass tracking uses lodestone NBT (`LodestonePos`, `LodestoneDimension`, `LodestoneTracked=false`) — works in Nether and End.
- Right-click toggle uses `UseItemCallback` (`fabric-events-interaction-v0`). The 2-arg `TypedActionResult.success(T, boolean)` is **unmapped** in this yarn build — use `TypedActionResult.consume(stack)` to swallow the click instead.
- Eliminating a runner uses `PlayerManager.respawnPlayer(player, false)` to clear the death screen, then `setGameMode(SPECTATOR)` — deliberately **not** `/kill`. The returned (new) player entity must be used after respawn.
- `runners`/`hunters` are `LinkedHashSet` so "lowest-index alive runner" follows assignment order. Eliminated runners stay in `runners` but are tracked in a separate `eliminatedRunners` set.
