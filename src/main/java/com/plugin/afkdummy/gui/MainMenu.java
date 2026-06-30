package com.plugin.afkdummy.gui;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.entity.DummySession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The main menu GUI for the AFK Dummy plugin.
 * <p>
 * Displays three primary options:
 * <ul>
 *   <li><b>Slot 11</b> — Spawn AFK Dummy: Opens the time selection menu</li>
 *   <li><b>Slot 13</b> — Current Status: Shows active session info or inactive status</li>
 *   <li><b>Slot 15</b> — Force Despawn: Immediately removes the active dummy</li>
 * </ul>
 * </p>
 */
public class MainMenu extends MenuFramework {

    private final AFKDummyPlugin plugin;
    private final Player viewer;

    /**
     * Creates and populates the main menu for a specific player.
     *
     * @param plugin the owning plugin instance
     * @param viewer the player viewing this menu
     */
    public MainMenu(AFKDummyPlugin plugin, Player viewer) {
        super("§8§l✦ §5§lAFK Dummy §8§l✦", 27);
        this.plugin = plugin;
        this.viewer = viewer;

        buildMenu();
    }

    /**
     * Populates all menu items based on the current state.
     */
    private void buildMenu() {
        ConfigManager config = plugin.getConfigManager();
        DummyManager dummyManager = plugin.getDummyManager();
        boolean hasActive = dummyManager.hasActiveDummy(viewer.getUniqueId());

        // ====================================================================
        // Slot 11 — Spawn AFK Dummy
        // ====================================================================
        if (hasActive) {
            // Already has a dummy — show disabled option
            setItem(11, createItem(Material.GRAY_DYE,
                    "§c§lSpawn AFK Dummy",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§c You already have an active",
                    "§c dummy. Despawn it first",
                    "§c to spawn a new one.",
                    "§7§m━━━━━━━━━━━━━━━━━━━━"
            ));
        } else {
            setItem(11, createItem(Material.NETHER_STAR,
                    "§a§lSpawn AFK Dummy",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§7 Spawn an AFK dummy player",
                    "§7 at your current location.",
                    "",
                    "§7 The dummy will keep chunks",
                    "§7 loaded and farms active.",
                    "",
                    "§7 Cost: §f" + config.getCostPerHour() + " "
                            + config.getPaymentItemDisplayName() + "§7/hour",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§e§l▶ Click to select duration"
            ), event -> {
                Player player = (Player) event.getWhoClicked();
                if (!dummyManager.hasActiveDummy(player.getUniqueId())) {
                    new TimeSelectionMenu(plugin, player).open(player);
                }
            });
        }

        // ====================================================================
        // Slot 13 — Status Display
        // ====================================================================
        Optional<DummySession> sessionOpt = dummyManager.getSession(viewer.getUniqueId());
        if (sessionOpt.isPresent()) {
            DummySession session = sessionOpt.get();
            Location loc = session.getLocation();

            List<String> lore = new ArrayList<>();
            lore.add("§7§m━━━━━━━━━━━━━━━━━━━━");
            lore.add("§7 Status: §a§l● ACTIVE");
            lore.add("");

            if (loc != null && loc.getWorld() != null) {
                lore.add("§7 World: §f" + loc.getWorld().getName());
                lore.add("§7 Position: §f" + String.format("%.1f, %.1f, %.1f",
                        loc.getX(), loc.getY(), loc.getZ()));
            } else {
                lore.add("§7 Location: §cUnknown");
            }

            lore.add("");
            lore.add("§7 Time Remaining:");
            lore.add("§b §l⏰ " + session.getFormattedTimeRemaining());
            lore.add("§7§m━━━━━━━━━━━━━━━━━━━━");

            setItem(13, createItem(Material.CLOCK,
                    "§b§lDummy Status",
                    lore.toArray(new String[0])
            ));
        } else {
            setItem(13, createItem(Material.GRAY_STAINED_GLASS_PANE,
                    "§7§lDummy Status",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§7 Status: §c§l● INACTIVE",
                    "",
                    "§7 You don't have an active",
                    "§7 AFK dummy at the moment.",
                    "",
                    "§7 Use the §aSpawn §7option",
                    "§7 to place one.",
                    "§7§m━━━━━━━━━━━━━━━━━━━━"
            ));
        }

        // ====================================================================
        // Slot 15 — Force Despawn
        // ====================================================================
        if (hasActive) {
            setItem(15, createItem(Material.BARRIER,
                    "§c§lForce Despawn",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§7 Immediately remove your",
                    "§7 active AFK dummy.",
                    "",
                    "§c§l ⚠ WARNING:",
                    "§c No refunds will be issued",
                    "§c for early termination.",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§c§l▶ Click to despawn"
            ), event -> {
                Player player = (Player) event.getWhoClicked();
                player.closeInventory();

                if (dummyManager.despawnDummy(player.getUniqueId())) {
                    player.sendMessage("§a§l✓ §aYour AFK dummy has been despawned successfully.");
                } else {
                    player.sendMessage("§c§l✕ §cNo active dummy found to despawn.");
                }
            });
        } else {
            setItem(15, createItem(Material.GRAY_DYE,
                    "§7§lForce Despawn",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§7 No active dummy to despawn.",
                    "§7§m━━━━━━━━━━━━━━━━━━━━"
            ));
        }

        // ====================================================================
        // Info Item — Bottom center
        // ====================================================================
        setItem(22, createItem(Material.BOOK,
                "§e§lInformation",
                "§7§m━━━━━━━━━━━━━━━━━━━━",
                "§7 AFK Dummies are fake players",
                "§7 that keep chunks loaded and",
                "§7 farms running while you're away.",
                "",
                "§7 Active Dummies: §f" + dummyManager.getActiveCount()
                        + "§7/" + config.getMaxServerWideDummies(),
                "§7§m━━━━━━━━━━━━━━━━━━━━"
        ));

        // Fill remaining slots with glass panes
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }
}
