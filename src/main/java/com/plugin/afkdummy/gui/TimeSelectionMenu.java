package com.plugin.afkdummy.gui;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.entity.DummySession;
import com.plugin.afkdummy.util.DebugLogger;
import com.plugin.afkdummy.util.ItemCostUtil;
import com.plugin.afkdummy.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Time selection menu for choosing the duration of an AFK dummy rental.
 * <p>
 * Offers discrete time tiers (1, 3, 12, and 24 hours) with dynamically
 * calculated costs based on the plugin configuration.
 * </p>
 */
public class TimeSelectionMenu extends MenuFramework {

    private final AFKDummyPlugin plugin;
    private final Player viewer;

    /**
     * Creates the time selection menu for a player.
     *
     * @param plugin the owning plugin instance
     * @param viewer the player viewing this menu
     */
    public TimeSelectionMenu(AFKDummyPlugin plugin, Player viewer) {
        super("§8§l✦ §d§lSelect Duration §8§l✦", 27);
        this.plugin = plugin;
        this.viewer = viewer;

        buildMenu();
    }

    /**
     * Populates the menu with time tier options and a back button.
     */
    private void buildMenu() {
        ConfigManager config = plugin.getConfigManager();
        String itemName = config.getPaymentItemDisplayName();

        // ====================================================================
        // Time Tier Options
        // ====================================================================

        // Slot 10 — 1 Hour
        createTimeOption(10, 1, Material.LIME_DYE,
                "§a§l1 Hour", itemName, config);

        // Slot 12 — 3 Hours
        createTimeOption(12, 3, Material.YELLOW_DYE,
                "§e§l3 Hours", itemName, config);

        // Slot 14 — 12 Hours
        createTimeOption(14, 12, Material.ORANGE_DYE,
                "§6§l12 Hours", itemName, config);

        // Slot 16 — 24 Hours
        createTimeOption(16, 24, Material.RED_DYE,
                "§c§l24 Hours", itemName, config);

        // ====================================================================
        // Back Button
        // ====================================================================
        setItem(22, createItem(Material.ARROW,
                "§7§l◀ Back",
                "§7§m━━━━━━━━━━━━━━━━━━━━",
                "§7 Return to the main menu.",
                "§7§m━━━━━━━━━━━━━━━━━━━━"
        ), event -> {
            Player player = (Player) event.getWhoClicked();
            new MainMenu(plugin, player).open(player);
        });

        // Fill remaining slots
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);
    }

    /**
     * Creates a time tier option item with dynamic cost display and purchase handler.
     */
    private void createTimeOption(int slot, int hours, Material material,
                                  String title, String itemName, ConfigManager config) {
        int cost = config.calculateCost(hours);
        Material paymentMat = config.getPaymentItem();

        int playerHas = ItemCostUtil.countItems(viewer, paymentMat);
        boolean canAfford = playerHas >= cost;
        String affordIndicator = canAfford ? "§a ✓ You can afford this" : "§c ✕ Insufficient items";

        setItem(slot, createItem(material, Math.min(hours, 64), title,
                "§7§m━━━━━━━━━━━━━━━━━━━━",
                "§7 Duration: §f" + hours + (hours == 1 ? " hour" : " hours"),
                "",
                "§7 Cost: §b" + cost + " " + itemName,
                "§7 You have: §f" + playerHas + " " + itemName,
                "",
                affordIndicator,
                "§7§m━━━━━━━━━━━━━━━━━━━━",
                canAfford ? "§e§l▶ Click to purchase" : "§7§o Click to attempt purchase"
        ), event -> {
            Player player = (Player) event.getWhoClicked();
            handlePurchase(player, hours, cost);
        });
    }

    /**
     * Handles the purchase flow when a player selects a time tier.
     */
    private void handlePurchase(Player player, int hours, int cost) {
        player.closeInventory();

        DummyManager dummyManager = plugin.getDummyManager();
        ConfigManager config = plugin.getConfigManager();

        // Re-verify: per-player limit
        int currentCount = dummyManager.getActiveCountByOwner(player.getUniqueId());
        int maxAllowed = config.getMaxDummiesPerPlayer();
        if (currentCount >= maxAllowed) {
            DebugLogger.log(String.format("COMMAND /afkdummy create actor=%s config max-dummies=%d owned_dummies=%d decision=DENY reason=MAX_DUMMIES_REACHED",
                    player.getName(), maxAllowed, currentCount));
            player.sendMessage("§c§l✕ §cYou have reached your maximum dummy limit ("
                    + currentCount + "/" + maxAllowed + ")!");
            return;
        }

        // Re-verify: server limit
        if (dummyManager.getActiveCount() >= config.getMaxServerWideDummies()) {
            DebugLogger.log(String.format("COMMAND /afkdummy create actor=%s config max-server-dummies=%d total_dummies=%d decision=DENY reason=SERVER_LIMIT_REACHED",
                    player.getName(), config.getMaxServerWideDummies(), dummyManager.getActiveCount()));
            player.sendMessage("§c§l✕ §cServer dummy limit reached. Try again later.");
            return;
        }

        // Atomic inventory check and deduction
        Material paymentItem = config.getPaymentItem();
        int playerHasBefore = ItemCostUtil.countItems(player, paymentItem);
        if (!ItemCostUtil.removeItems(player, paymentItem, cost)) {
            DebugLogger.log(String.format("PURCHASE actor=%s config currency=%s required=%d player_has=%d decision=DENY reason=INSUFFICIENT_FUNDS",
                    player.getName(), paymentItem.name(), cost, playerHasBefore));
            String message = "§c§l✕ §cInsufficient funds. Please place §f"
                    + cost + " " + config.getPaymentItemDisplayName()
                    + " §cin your inventory.";
            player.sendMessage(message);
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "✕ Need " + cost + " " + config.getPaymentItemDisplayName())
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        DebugLogger.log(String.format("PURCHASE actor=%s config currency=%s required=%d player_had=%d decision=ALLOW duration_hours=%d",
                player.getName(), paymentItem.name(), cost, playerHasBefore, hours));

        // Payment successful — spawn the dummy
        long durationMs = TimeUtil.hoursToMillis(hours);
        DummySession session = dummyManager.spawnDummy(player, player.getLocation(), durationMs);

        if (session != null) {
            int newCount = dummyManager.getActiveCountByOwner(player.getUniqueId());
            player.sendMessage("§a§l✓ §aAFK Dummy spawned successfully! §7(" + newCount + "/" + maxAllowed + ")");
            player.sendMessage("§7  Duration: §f" + hours + (hours == 1 ? " hour" : " hours"));
            player.sendMessage("§7  Cost: §b" + cost + " " + config.getPaymentItemDisplayName());
            player.sendMessage("§7  Location: §f" + formatLocation(player.getLocation()));
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "✓ AFK Dummy active for " + hours + "h (" + newCount + "/" + maxAllowed + ")")
                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
        } else {
            // Spawn failed — refund the items
            player.getInventory().addItem(new ItemStack(paymentItem, cost));
            player.updateInventory();
            player.sendMessage("§c§l✕ §cFailed to spawn dummy. Your items have been refunded.");
            DebugLogger.log(String.format("PURCHASE_REFUND actor=%s currency=%s amount=%d reason=SPAWN_FAILED",
                    player.getName(), paymentItem.name(), cost));
        }
    }

    /**
     * Formats a location for display.
     */
    private String formatLocation(org.bukkit.Location loc) {
        return String.format("%s (%.1f, %.1f, %.1f)",
                loc.getWorld() != null ? loc.getWorld().getName() : "Unknown",
                loc.getX(), loc.getY(), loc.getZ());
    }
}
