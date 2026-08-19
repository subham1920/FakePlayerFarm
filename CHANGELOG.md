# Changelog

All notable changes to the AFKDummy (FakePlayerFarm) plugin are documented in this file.

## [1.0.2] - 2026-08-19

### ✨ Added
- **Teleport / Relocate Feature**:
  - Added "Teleport Dummy Here" button (`Material.ENDER_PEARL`) at Slot 14 in `/afkdummy` main menu.
  - Added in-game commands: `/afkdummy tp`, `/afkdummy move`, `/afkdummy relocate`, `/afkdummy here`.
  - Added tab completion support for `tp` and `move` subcommands.
  - Relocating a dummy preserves remaining paid time without charging additional diamonds/items.
  - Asynchronously updates stored coordinates in `dummies.json`.
- **Exhaustive Unit Test Suite**:
  - Added 1,645 unit and parameterized tests covering utilities, entity logic, GUI interactions, event handling, configuration parsing, and persistence.

### 🐛 Fixed
- **Immutable `PropertyMap` Crash**:
  - Resolved `UnsupportedOperationException` in `SkinUtil.applySkin` when modifying `PropertyMap` in AuthLib 9.0+ / Paper 26.2.
- **Spawn Coordinates Overwrite**:
  - Resolved issue where `DummyPlayer.spawn()` loaded stale coordinates from old `playerdata/<uuid>.dat` files instead of the target spawn location.
  - Added NMS `teleportTo()` post-spawn invocation for immediate client sync.
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
