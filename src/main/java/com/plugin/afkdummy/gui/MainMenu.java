package com.plugin.afkdummy.gui;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.entity.DummySession;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The main menu GUI for the AFK Dummy plugin.
 * <p>
 * Supports multi-dummy management and displays per-player limit progression:
 * <ul>
 *   <li><b>Slot 11</b> — Spawn AFK Dummy: Opens duration selection if under limit</li>
 *   <li><b>Slot 13</b> — Current Status: Displays all active dummy locations & remaining times</li>
 *   <li><b>Slot 15</b> — Force Despawn: Safely despawns active dummies</li>
 *   <li><b>Slot 22</b> — Information: Displays server & player dummy statistics</li>
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

        int currentCount = dummyManager.getActiveCountByOwner(viewer.getUniqueId());
        int maxAllowed = config.getMaxDummiesPerPlayer();
        boolean canSpawn = currentCount < maxAllowed;
        List<DummySession> sessions = dummyManager.getSessionsByOwner(viewer.getUniqueId());

        // ====================================================================
        // Status & Action Slots
        // ====================================================================
        if (currentCount > 0) {
            // Slot 10 — Spawn AFK Dummy
            if (canSpawn) {
                setItem(10, createItem(Material.NETHER_STAR,
                        "§a§lSpawn AFK Dummy",
                        "§7§m━━━━━━━━━━━━━━━━━━━━",
                        "§7 Spawn another AFK dummy",
                        "§7 at your current location.",
                        "",
                        "§7 Active Dummies: §f" + currentCount + "§7/§f" + maxAllowed,
                        "§7 Cost: §f" + config.getCostPerHour() + " "
                                + config.getPaymentItemDisplayName() + "§7/hour",
                        "§7§m━━━━━━━━━━━━━━━━━━━━",
                        "§e§l▶ Click to select duration"
                ), event -> {
                    Player player = (Player) event.getWhoClicked();
                    if (dummyManager.canSpawnMore(player.getUniqueId())) {
                        new TimeSelectionMenu(plugin, player).open(player);
                    } else {
                        player.sendMessage("§c§l✕ §cYou have reached your maximum dummy limit ("
                                + dummyManager.getActiveCountByOwner(player.getUniqueId()) + "/"
                                + config.getMaxDummiesPerPlayer() + ")!");
                        player.closeInventory();
                    }
                });
            } else {
                setItem(10, createItem(Material.GRAY_DYE,
                        "§c§lLimit Reached",
                        "§7§m━━━━━━━━━━━━━━━━━━━━",
                        "§c You have reached your max",
                        "§c dummy limit: §f" + currentCount + "§7/§f" + maxAllowed,
                        "",
                        "§c Despawn an existing dummy",
                        "§c to spawn a new one.",
                        "§7§m━━━━━━━━━━━━━━━━━━━━"
                ));
            }

            // Slot 12 — Status Display
            List<String> lore = new ArrayList<>();
            lore.add("§7§m━━━━━━━━━━━━━━━━━━━━");
            lore.add("§7 Active Dummies: §a§l" + currentCount + "§7/§f" + maxAllowed);
            lore.add("");

            int index = 1;
            for (DummySession session : sessions) {
                Location loc = session.getLocation();
                String worldName = loc != null && loc.getWorld() != null ? loc.getWorld().getName() : "Unknown";
                String posStr = loc != null ? String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ()) : "?";

                String header = session.getCustomName() != null && !session.getCustomName().isEmpty()
                        ? "§e§lDummy #" + index + " (§f" + session.getCustomName() + "§e):"
                        : "§e§lDummy #" + index + ":";
                lore.add(header);
                lore.add("§7 • Location: §f" + worldName + " (" + posStr + ")");
                if (session.getSkinName() != null && !session.getSkinName().isEmpty()) {
                    lore.add("§7 • Skin: §f" + session.getSkinName());
                }
                lore.add("§7 • Time Left: §b⏰ " + session.getFormattedTimeRemaining());
                if (index < sessions.size()) {
                    lore.add("");
                }
                index++;
            }
            lore.add("");
            lore.add("§7 Use §e/afkdummy skin <player> §7or");
            lore.add("§7 §e/afkdummy name <text> §7to customize!");
            lore.add("§7§m━━━━━━━━━━━━━━━━━━━━");

            setItem(12, createItem(Material.CLOCK,
                    "§b§lDummy Status",
                    lore.toArray(new String[0])
            ));

            // Slot 14 — Teleport Dummy Here
            setItem(14, createItem(Material.ENDER_PEARL,
                    "§d§lTeleport Dummy Here",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§7 Relocate your active AFK",
                    "§7 dummy to your current location.",
                    "",
                    "§a✓ Keeps remaining time active!",
                    "§a✓ No diamonds/cost consumed.",
                    "",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§e§l▶ Click to teleport here"
            ), event -> {
                Player player = (Player) event.getWhoClicked();
                player.closeInventory();

                if (dummyManager.teleportNearestForOwner(player)) {
                    player.sendMessage("§a§l✓ §aTeleported your AFK dummy to your current location!");
                    try {
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    } catch (Throwable ignored) {}
                } else {
                    player.sendMessage("§c§l✕ §cNo active dummy found to teleport.");
                }
            });

            // Slot 16 — Force Despawn
            String despawnLabel = currentCount > 1
                    ? "§c§lForce Despawn (Nearest/All)"
                    : "§c§lForce Despawn";

            List<String> despawnLore = new ArrayList<>();
            despawnLore.add("§7§m━━━━━━━━━━━━━━━━━━━━");
            despawnLore.add("§7 Immediately remove your active");
            despawnLore.add("§7 dummy player(s).");
            despawnLore.add("");
            if (currentCount > 1) {
                despawnLore.add("§e Left-Click: §fDespawn Nearest");
                despawnLore.add("§c Shift-Click: §fDespawn ALL (" + currentCount + ")");
            } else {
                despawnLore.add("§e Click: §fDespawn Active Dummy");
            }
            despawnLore.add("");
            despawnLore.add("§c§l ⚠ WARNING:");
            despawnLore.add("§c No refunds will be issued.");
            despawnLore.add("§7§m━━━━━━━━━━━━━━━━━━━━");

            setItem(16, createItem(Material.BARRIER,
                    despawnLabel,
                    despawnLore.toArray(new String[0])
            ), event -> {
                Player player = (Player) event.getWhoClicked();
                player.closeInventory();

                if (event.isShiftClick() && currentCount > 1) {
                    int removed = dummyManager.despawnAllForOwner(player.getUniqueId());
                    player.sendMessage("§a§l✓ §aDespawned all " + removed + " active dummies.");
                } else {
                    if (dummyManager.despawnNearest(player)) {
                        player.sendMessage("§a§l✓ §aYour AFK dummy has been despawned successfully.");
                    } else {
                        player.sendMessage("§c§l✕ §cNo active dummy found to despawn.");
                    }
                }
            });
        } else {
            // Inactive state: Slot 11 Spawn, Slot 13 Status, Slot 15 Despawn (disabled)
            setItem(11, createItem(Material.NETHER_STAR,
                    "§a§lSpawn AFK Dummy",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§7 Spawn an AFK dummy player",
                    "§7 at your current location.",
                    "",
                    "§7 The dummy will keep chunks",
                    "§7 loaded and farms active.",
                    "",
                    "§7 Active Dummies: §f0§7/§f" + maxAllowed,
                    "§7 Cost: §f" + config.getCostPerHour() + " "
                            + config.getPaymentItemDisplayName() + "§7/hour",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§e§l▶ Click to select duration"
            ), event -> {
                Player player = (Player) event.getWhoClicked();
                if (dummyManager.canSpawnMore(player.getUniqueId())) {
                    new TimeSelectionMenu(plugin, player).open(player);
                } else {
                    player.sendMessage("§c§l✕ §cYou have reached your maximum dummy limit!");
                    player.closeInventory();
                }
            });

            setItem(13, createItem(Material.GRAY_STAINED_GLASS_PANE,
                    "§7§lDummy Status",
                    "§7§m━━━━━━━━━━━━━━━━━━━━",
                    "§7 Status: §c§l● INACTIVE (0/" + maxAllowed + ")",
                    "",
                    "§7 You don't have any active",
                    "§7 AFK dummies at the moment.",
                    "",
                    "§7 Use the §aSpawn §7option",
                    "§7 to place one.",
                    "§7§m━━━━━━━━━━━━━━━━━━━━"
            ));

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
                "§7 Your Dummies: §f" + currentCount + "§7/§f" + maxAllowed,
                "§7 Server Dummies: §f" + dummyManager.getActiveCount()
                        + "§7/" + config.getMaxServerWideDummies(),
                "§7§m━━━━━━━━━━━━━━━━━━━━"
        ));

        // Fill remaining slots with glass panes
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }
}
