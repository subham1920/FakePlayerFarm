package com.plugin.afkdummy.config;

import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Manages plugin configuration loaded from config.yml.
 * Provides validated, type-safe access to all configuration values
 * with automatic fallback to safe defaults on invalid input.
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger logger;

    private int costPerHour;
    private Material paymentItem;
    private int maxDummiesPerPlayer;
    private int maxServerWideDummies;
    private int cleanupIntervalSeconds;
    private int respawnDelayTicks;

    /**
     * Constructs a ConfigManager and immediately loads the configuration.
     *
     * @param plugin the owning plugin instance
     */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload();
    }

    /**
     * Reloads all configuration values from disk.
     * Validates each value and falls back to sensible defaults on invalid input.
     */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        // Cost per hour (minimum 1)
        costPerHour = Math.max(1, plugin.getConfig().getInt("settings.cost-per-hour", 5));

        // Payment item with MINECRAFT: prefix stripping
        String itemName = plugin.getConfig().getString("settings.payment-item", "DIAMOND");
        if (itemName != null && itemName.toUpperCase().startsWith("MINECRAFT:")) {
            itemName = itemName.substring("MINECRAFT:".length());
        }
        paymentItem = Material.matchMaterial(itemName != null ? itemName : "DIAMOND");
        if (paymentItem == null || !paymentItem.isItem()) {
            logger.warning("Invalid payment-item '" + itemName
                    + "' in config.yml. Defaulting to DIAMOND.");
            paymentItem = Material.DIAMOND;
        }

        // Limits
        maxDummiesPerPlayer = Math.max(1,
                plugin.getConfig().getInt("settings.max-dummies-per-player", 1));
        maxServerWideDummies = Math.max(1,
                plugin.getConfig().getInt("settings.max-server-wide-dummies", 20));

        // Timing
        cleanupIntervalSeconds = Math.max(5,
                plugin.getConfig().getInt("settings.cleanup-interval-seconds", 30));
        respawnDelayTicks = Math.max(1,
                plugin.getConfig().getInt("settings.respawn-delay-ticks", 40));

        logger.info("Configuration loaded: cost=" + costPerHour + " "
                + paymentItem.name() + "/hr, max-per-player=" + maxDummiesPerPlayer
                + ", max-server=" + maxServerWideDummies);
    }

    /**
     * Calculates the total item cost for a given number of hours.
     *
     * @param hours the number of rental hours
     * @return total item cost
     */
    public int calculateCost(int hours) {
        return costPerHour * hours;
    }

    /** @return cost in items per hour of rental */
    public int getCostPerHour() {
        return costPerHour;
    }

    /** @return the Material used as payment currency */
    public Material getPaymentItem() {
        return paymentItem;
    }

    /** @return maximum dummies allowed per player */
    public int getMaxDummiesPerPlayer() {
        return maxDummiesPerPlayer;
    }

    /** @return maximum dummies allowed server-wide */
    public int getMaxServerWideDummies() {
        return maxServerWideDummies;
    }

    /** @return how often (seconds) to run the cleanup task */
    public int getCleanupIntervalSeconds() {
        return cleanupIntervalSeconds;
    }

    /** @return delay in ticks before respawning dummies after restart */
    public int getRespawnDelayTicks() {
        return respawnDelayTicks;
    }

    /**
     * Returns a user-friendly display name for the payment item.
     * Converts material enum names like GOLD_INGOT to "Gold Ingot".
     *
     * @return formatted item name
     */
    public String getPaymentItemDisplayName() {
        String name = paymentItem.name().replace('_', ' ');
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == ' ') {
                result.append(' ');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }
}
