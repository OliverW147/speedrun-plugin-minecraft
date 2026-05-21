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

```powershell
cd D:\manhunt-build
.\gradlew.bat jar
```

Output jar: `build\libs\manhunt-1.0.0.jar`

To deploy to server:
```powershell
Copy-Item "build\libs\manhunt-1.0.0.jar" "D:\Minecraft Server\mods\manhunt-1.0.0.jar" -Force
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

Location: `D:\Minecraft Server\mods\`

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
| `/manhunt setgrace <seconds>` | Set grace period duration (0–300s, default 30) |
| `/manhunt setrange <blocks>` | Set compass dead zone radius (0–10000, default 50) |

All player name arguments support **tab autocomplete**.

---

## Features

- **Grace period**: Hunters are frozen at game start for a configurable duration. Countdown titles shown at 10, 5, 4, 3, 2, 1 seconds.
- **Tracking compass**: Given to each hunter on start. Uses lodestone NBT to point at the nearest runner in real-time (updates every second).
- **Compass restored on death**: If a hunter dies, their compass is automatically restored when they respawn.
- **Dead zone**: Within a configurable XZ radius of the runner, the compass goes blank and shows "Runner is nearby!" — prevents exact pinpointing.
- **Cross-dimension**: When runner is in a different dimension, compass spins randomly (no portal pointing) and shows "Runner is in another dimension!".
- **Win conditions**:
  - Runner dies → "Hunters win!"
  - Runner kills the Ender Dragon → "Runner wins!"
  - `/manhunt stop` → manual end
- **No coordinates, no distance** shown to hunters — action bar only shows proximity/dimension messages.

---

## Key Implementation Notes

- `Text.of()` does not exist in 1.16.1 — must use `new LiteralText(String)`.
- `ServerEntityCombatEvents` and `ServerPlayerEvents` don't exist in fabric-api 0.18 — death detection uses tick-based `player.isDead()` polling instead.
- `broadcastChatMessage` in 1.16.1 requires 3 args: `(Text, MessageType, UUID)`.
- Dragon kill detected via `minecraft:end/kill_dragon` advancement tracker.
- Compass tracking uses lodestone NBT (`LodestonePos`, `LodestoneDimension`, `LodestoneTracked=false`) — works in Nether and End.
