# Changelog

All notable changes to the AFKDummy (FakePlayerFarm) plugin are documented in this file.

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
