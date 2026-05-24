# Manhunt

A simple, no-fluff manhunt mod for Fabric **1.16.1** dedicated servers. Hunters chase runners -- last runner standing
loses, or runners win by killing the Ender Dragon.

**Server-side only.** Does not require installation on the client.

---

## How It Works

Assign roles with `/hunter <player>` and `/runner <player>`, then start the game with `/manhunt start`. Hunters each
get a tracking compass that points at a runner in real time. Runners win by killing the Ender Dragon (by any means --
beds included). Hunters win by eliminating every runner.

When a runner dies they're moved to spectator and can watch freely until the game ends. The game only ends when *all*
runners are eliminated or the dragon goes down.

---

## Features

- Tracking compass -- points at the nearest runner, updates every tick
- Right-click compass to cycle between specific runners
- Compass restored automatically on hunter death
- Dead zone -- compass spins when you're close, no exact pinpointing
- Cross-dimension tracking -- points at last known position if the runner is in another dimension; spins if they've never been there
- Grace period -- hunters are slowed at game start for a configurable duration (`/manhunt setgrace <seconds>`)
- Multi-runner support -- runners are eliminated one by one, not instant game-over
- Dragon kill detection covers melee, beds, and command kills -- not just player-credited kills

---

## Commands

| Command | Description |
|---|---|
| `/hunter <player>` | Assign a hunter |
| `/runner <player>` | Assign a runner |
| `/manhunt start` | Start the game |
| `/manhunt stop` | End the game |
| `/manhunt status` | Show roles and current settings |
| `/manhunt setgrace <seconds>` | Grace period (0-300s) |
| `/manhunt setrange <blocks>` | Compass dead zone radius (0-10000) |
| `/manhunt compass` | Give yourself a fresh tracker compass |

All player arguments support tab autocomplete. Roles can be changed mid-game.

---

## Compatibility

- Minecraft **1.16.1**
- Fabric Loader 0.11.3+
- Requires Fabric API
