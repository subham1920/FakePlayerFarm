# ✦ AFK Dummy (FakePlayerFarm) ✦

![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21%2B-brightgreen.svg)
![Platform](https://img.shields.io/badge/Platform-PaperMC%2026.2-blue.svg)
![Java](https://img.shields.io/badge/Java-25-orange.svg)
![Build](https://img.shields.io/badge/Tests-1645%20Passing-success.svg)
![Version](https://img.shields.io/badge/Version-v1.0.3-purple.svg)

A high-performance PaperMC plugin that spawns **true NMS `ServerPlayer` fake player entities**. Unlike conventional chunk-loaders (`Chunk#setForceLoaded`), AFK dummies fully participate in the server's simulation loop, triggering **crop growth (random block ticks)**, **farm mechanics**, and **natural mob spawning**.

---

## ✨ Features

- **🌾 True Farm & Crop Loading**: Uses genuine NMS `ServerPlayer` instances with player chunk tickets, triggering random block ticks (sugarcane, bamboo, wheat, cactus, etc.) and mob spawn caps.
- **📍 Exact Initial Spawn Placement (`v1.0.3 Fix`)**: Pre-writes target NBT playerdata before injection, preventing dummies from spawning at the world spawn chunk.
- **🎩 Full 3D Skin Outer Layers (`v1.0.3 Fix`)**: Enables all 7 player model customization layers (hat, jacket, sleeves, pants, cape) with metadata packet syncing.
- **🎨 Custom Skin & Name Customization (`v1.0.3 New`)**: Set your dummy's skin to any Minecraft player with `/afkdummy skin <player>` and customize its nametag with `/afkdummy name <text>`.
- **✨ Teleport / Relocate Option**: Misplaced your dummy? Use the **Teleport Dummy Here** option in the GUI or `/afkdummy tp` to move your dummy to your current location without losing paid duration or diamonds!
- **💤 Sleep Ignored Integration**: Dummies have `setSleepingIgnored(true)` enabled, so real players can sleep through the night without dummy interference.
- **💰 Customizable Economy & Costs**: Charge per-hour dummy rental using any in-game item (Diamonds, Emeralds, Gold, Netherite, etc.).
- **👥 Multi-Dummy Management**: Support for multiple active dummies per player, governed by configurable per-player and server-wide limits.
- **🔒 Exploit-Proof GUI & Protection**: Custom chest GUI menus with drag, shift-click, and hopper theft protection. Dummies are invulnerable, immune to knockback, damage, piston pushing, portals, and vehicle mounting.
- **💾 Atomic JSON Persistence**: Dummy positions, remaining time, custom names, and skins persist across server restarts and crashes via `dummies.json`.
- **🧪 1,650+ Comprehensive Unit Tests**: Validated with over 1,650 unit and parameterized tests.

---

## 🎮 Commands & Permissions

### Commands

| Command | Aliases | Description |
| :--- | :--- | :--- |
| `/afkdummy` | `/dummy` | Opens the interactive main management GUI |
| `/afkdummy tp` | `/afkdummy move`, `relocate`, `here` | Teleports your active dummy to your current position |
| `/afkdummy skin <player>` | — | Sets your dummy's skin to any Minecraft player's skin |
| `/afkdummy name <text>` | — | Sets a custom visual display name / nametag for your dummy |
| `/afkdummy list` | — | Lists all active dummy sessions *(Admin)* |
| `/afkdummy reload` | — | Reloads `config.yml` and restarts cleanup tasks *(Admin)* |
| `/afkdummy despawnall` | — | Force-despawns all active dummies server-wide *(Admin)* |
| `/afkdummy help` | — | Displays admin help information |

### Permissions

| Permission | Default | Description |
| :--- | :--- | :--- |
| `afkdummy.use` | `true` | Allows players to use `/afkdummy` and access the GUI |
| `afkdummy.admin` | `op` | Grants access to admin commands (`reload`, `list`, `despawnall`) |

---

## ⚙️ Configuration (`config.yml`)

```yaml
# ==============================================================================
#                       AFK DUMMY CONFIGURATION
# ==============================================================================

settings:
  # Item used for payment when spawning a dummy
  # Supports any standard Minecraft material (e.g. DIAMOND, EMERALD, GOLD_INGOT)
  payment-item: "DIAMOND"

  # Cost in payment items per 1 hour of dummy lifetime
  cost-per-hour: 5

  # Maximum number of active dummies a single player can have simultaneously
  max-dummies-per-player: 1

  # Server-wide maximum cap for active dummies
  max-server-wide-dummies: 20

  # Frequency (in seconds) to check and despawn expired dummies
  cleanup-interval-seconds: 30

  # Delay (in server ticks) before respawning dummies on server startup
  respawn-delay-ticks: 40
```

---

## 🏗️ Building from Source

### Prerequisites
- JDK 25
- Gradle 9.6.1 (Wrapper included)

```bash
# Clone the repository
git clone https://github.com/subham1920/FakePlayerFarm.git
cd FakePlayerFarm

# Run tests
./gradlew test

# Build production shadow jar
./gradlew shadowJar
```

The output jar will be located at `build/libs/AFKDummy-1.0.2.jar`.

---

## 📄 License

This project is licensed under the MIT License.
