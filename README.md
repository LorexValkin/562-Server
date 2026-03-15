# 562 Dev Server
**Chase Foster - Educational Game Design Project**

A clean, from-scratch 562 game server built to pair with the cleaned-up 562 client.
No dependencies on any existing RSPS server code. Zero external libraries — just JDK.

## Quick Start

```batch
REM 1. Build the server
build.bat

REM 2. Start the server
run.bat

REM 3. In another terminal, start the client
cd ..\562_clean
run.bat
```

## Architecture

```
562_server/
├── src/com/rs562/server/
│   ├── Server.java           Main entry - accepts connections
│   ├── Constants.java         Server configuration
│   ├── engine/
│   │   └── GameEngine.java    600ms tick loop
│   ├── model/
│   │   ├── Position.java      World coordinates + region math
│   │   ├── World.java         Player registry + tick dispatch
│   │   └── player/
│   │       └── Player.java    Player entity + appearance
│   └── net/
│       ├── Buffer.java        Binary packet read/write
│       └── Session.java       Login protocol + packet I/O
├── data/saves/                Player save files
├── build.bat
├── run.bat
└── README.md
```

## Design Principles

- **Zero dependencies** — runs on any JDK 8+, nothing to install
- **One thread per connection** — simple, debuggable, perfect for dev
- **Clean separation** — model/net/engine are independent layers
- **Easy to expand** — add packet handlers, skills, NPCs by extending existing patterns
- **Matches YOUR client** — protocol decoded directly from your 562 client source

## Server Commands (in-game)

- `::tele x y [z]` — Teleport to coordinates
- `::pos` — Show current position and region info
- `::commands` — List available commands

## What Works Now

- Login handshake (matches your client's protocol with RSA/ISAAC disabled)
- Player spawns at Lumbridge (3222, 3218)
- Map region loading
- Player update (appearance rendering)
- Game frame interface loading
- Skill levels sent to client
- Chat messages
- Command handling
- Game tick engine (600ms)

## What Needs Building Next

These are listed in priority order for getting a playable experience:

1. **Packet opcode verification** — The exact opcodes (map=162, player=42,
   etc.) may need tweaking to match your specific client build. If the client
   shows "Connection lost" after login, the opcodes are the first thing to check.
2. **Walking** — Parse the walk packet and implement pathfinding
3. **NPC spawning** — Load NPC spawn definitions, send NPC update packets
4. **Item system** — Inventory, equipment, ground items
5. **Object interaction** — Click handlers for game objects
6. **Combat** — NPC and player combat formulas
7. **Skills** — XP gain, level-up, skill-specific actions
8. **Cache reading** — Server-side cache reader for map data, item/npc defs

## Expanding the Server

**Adding a new command:**
In `Session.java`, add a case to `processCommand()`.

**Adding a new incoming packet handler:**
1. Add the opcode + size to `PACKET_SIZES[]` in `Session.java`
2. Add a case to `handlePacket()` switch

**Adding a new outgoing packet:**
Add a `send___()` method to `Session.java` following the existing pattern.

## Configuration

Edit `Constants.java`:
- `PORT` — Server port (default: 43594, must match client)
- `SPAWN_X/Y/Z` — Default spawn location
- `TICK_RATE` — Game tick speed (600ms = standard)
- `MAX_PLAYERS` — Player capacity
