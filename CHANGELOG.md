# Changelog

All notable changes to the AFKDummy (FakePlayerFarm) plugin are documented in this file.

## [1.0.3] - 2026-08-20

> **Note**: Version 1.0.2 contained several initial runtime bugs and was skipped for general distribution / Modrinth release. Its development archive remains available on GitHub. Version 1.0.3 is the definitive production-ready release.

### 🐛 Bug Fixes & Architecture Improvements
- **3D Floating In-World Nametag `[AFK] <name>` Fix**:
  - Broadcasts explicit `ClientboundSetPlayerTeamPacket` protocol packets across all active player connections, guaranteeing that the floating nametag above the dummy's head displays `[AFK] <name>` consistently across all scoreboards and client setups.
  - Implemented single source of truth for dummy display names with prefix deduplication (`[AFK] Steve` $\rightarrow$ `[AFK] Steve`).
  - Removed all unwanted trailing session ID characters and suffix artifacts.
- **Initial Spawn Placement Race Condition Fix**:
  - Pre-registers dummy sessions in active tracking maps prior to `PlayerList.placeNewPlayer()`.
  - Paper's `PlayerSpawnLocationEvent` intercepts the injection and places the fake player directly in the target chunk and coordinates on tick 0, preventing world spawn appearance.
- **Teleportation & Rotation Sync**:
  - Replaced unacknowledged packet teleports with authoritative NMS position and connection resets (`connection.resetPosition()`), fixing visual rubberbanding and snap-backs.
  - Synchronized head yaw (`yHeadRot`) and body yaw (`yBodyRot`) with Bukkit rotation queries and tracking packets.
- **Dual `config.json` & `config.yml` Support**:
  - Native loading and live hot-reloading (`/afkdummy reload`) for both `config.json` and `config.yml`.
  - Full configuration audit logging in `latest-debug.txt` tracking item currency, limits, and costs.
- **Duplicate Entity Detection & Diagnostics**:
  - Added `/afkdummy debug` command and automated entity duplicate checks across server worlds and player lists.

## [1.0.2] - 2026-08-19

### ✨ Added
- **Teleport / Relocate Option**:
  - Added "Teleport Dummy Here" button (`Material.ENDER_PEARL`) at Slot 14 in `/afkdummy` main menu.
  - Added in-game commands: `/afkdummy tp`, `/afkdummy move`, `/afkdummy relocate`, `/afkdummy here`.
  - Added tab completion support for `tp`, `move`, `skin`, and `name` subcommands.
  - Relocating a dummy preserves remaining paid time without charging additional diamonds/items.
  - Asynchronously updates stored coordinates in `dummies.json`.
- **Custom Dummy Name & Skin System**:
  - Added `/afkdummy skin <playerName>` to change dummy skin to any Minecraft player's skin.
  - Added `/afkdummy name <customName>` to customize visual display names and nametags.
  - Added Mojang Profile API username resolution with multi-level asynchronous caching in `SkinUtil`.
  - Added `customName` and `skinName` persistence in `dummies.json` across server restarts.
  - Updated GUI status panel to display custom names and active skin indicators.
- **Exhaustive Unit Test Suite**:
  - Added 1,650+ unit and parameterized tests covering utilities, entity logic, GUI interactions, event handling, configuration parsing, and persistence.

### 🐛 Fixed
- **Initial Spawn Point Bug**:
  - Pre-writes target coordinates (`Pos`, `Rotation`, `Dimension`, `bukkit.world`) to `playerdata/<uuid>.dat` prior to `placeNewPlayer()` so Paper directly spawns the dummy at the player's exact location rather than falling back to the world spawn chunk.
- **Missing Skin Outer Layers (3D Hat, Jacket, Sleeves, Pants, Cape)**:
  - Enabled `DATA_PLAYER_MODE_CUSTOMISATION = 127` (0x7F bitmask) in entity metadata and packet synchronization (`ClientboundSetEntityDataPacket`) so all 7 3D secondary skin layers render properly.
- **Visual Name Tag Formatting**:
  - Eliminated internal session identifiers (`AFK_xxxx`) from the visual nametag and tab list display, formatting cleanly as `[AFK] <PlayerName>` or custom name.
- **Immutable `PropertyMap` Crash**:
  - Resolved `UnsupportedOperationException` in `SkinUtil.applySkin` when modifying `PropertyMap` in AuthLib 9.0+ / Paper 26.2.
- **Sleep Interference**:
  - Set `handle.getBukkitEntity().setSleepingIgnored(true)` so active dummy entities are ignored during player sleep percentage calculations, allowing normal night skipping.

---

## [1.0.1] - 2026-08-18

### ✨ Added
- Multi-dummy per-player architecture (`max-dummies-per-player`).
- Deterministic UUID generation using session IDs.
- Comprehensive piston displacement defense.
- Zero-leak Netty channel release handler.

---

## [1.0.0] - 2026-08-17

### 🚀 Initial Release
- Core NMS `ServerPlayer` fake player injection system for PaperMC 1.21+.
- Exploit-proof chest GUI management interface.
- Item payment economy integration.
- Atomic JSON session persistence.
