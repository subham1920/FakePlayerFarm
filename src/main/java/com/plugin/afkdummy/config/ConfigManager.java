package com.plugin.afkdummy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.plugin.afkdummy.util.DebugLogger;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages plugin configuration loaded from config.json (and fallback config.yml).
 * Provides validated, type-safe access to all configuration values with automatic
 * fallback to safe defaults on invalid input and structured runtime logging.
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Gson gson;

    private int costPerHour;
    private Material paymentItem;
    private String paymentItemDisplayName;
    private int maxDummiesPerPlayer;
    private int maxServerWideDummies;
    private int cleanupIntervalSeconds;
    private int respawnDelayTicks;
    private boolean updateCheckerEnabled;
    private boolean notifyUpdatesEnabled;

    /**
     * Constructs a ConfigManager and immediately loads the configuration.
     *
     * @param plugin the owning plugin instance
     */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        reload();
    }

    /**
     * Reloads all configuration values from disk (config.json or config.yml).
     * Validates each value and falls back to sensible defaults on invalid input.
     */
    public void reload() {
        File dataFolder = plugin.getDataFolder();
        File jsonConfigFile = dataFolder != null ? new File(dataFolder, "config.json") : null;

        // Ensure default config.yml is saved
        try {
            plugin.saveDefaultConfig();
            plugin.reloadConfig();
        } catch (Throwable ignored) {}

        // Try reading from config.json if the file exists on disk
        boolean loadedFromJson = false;
        if (jsonConfigFile != null && jsonConfigFile.exists() && jsonConfigFile.length() > 0) {
            try (Reader reader = new FileReader(jsonConfigFile, StandardCharsets.UTF_8)) {
                JsonObject jsonRoot = gson.fromJson(reader, JsonObject.class);
                if (jsonRoot != null) {
                    JsonObject settingsObj = jsonRoot.has("settings") && jsonRoot.get("settings").isJsonObject()
                            ? jsonRoot.getAsJsonObject("settings")
                            : jsonRoot;

                    parseAndValidateSettings(settingsObj, "config.json");
                    loadedFromJson = true;
                    DebugLogger.log("CONFIG LOAD file=plugins/AFKDummy/config.json result=SUCCESS");
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to parse config.json! Falling back to config.yml: " + e.getMessage());
                DebugLogger.log("CONFIG LOAD file=plugins/AFKDummy/config.json result=FAILED reason=" + e.getMessage());
            }
        }

        // Fallback to config.yml if JSON loading didn't take place
        if (!loadedFromJson) {
            parseFromYaml();
            DebugLogger.log("CONFIG LOAD file=plugins/AFKDummy/config.yml result=SUCCESS");
        }

        logConfigProperties();
    }

    private void parseAndValidateSettings(JsonObject obj, String sourceFile) {
        // 1. Cost per hour (min 1, default 5)
        int rawCost = getIntFromObj(obj, 5, "cost-per-hour", "cost_per_hour", "cost");
        this.costPerHour = Math.max(1, rawCost);

        // 2. Payment item / currency (default DIAMOND)
        String rawItem = getStringFromObj(obj, "DIAMOND", "payment-item", "payment_item", "currency", "payment");
        this.paymentItem = parseMaterial(rawItem, Material.DIAMOND);

        // 3. Payment item display name
        String rawDisplayName = getStringFromObj(obj, null, "payment-item-display-name", "payment_item_display_name", "display-name");
        if (rawDisplayName != null && !rawDisplayName.trim().isEmpty()) {
            this.paymentItemDisplayName = rawDisplayName.trim();
        } else {
            this.paymentItemDisplayName = formatMaterialDisplayName(this.paymentItem);
        }

        // 4. Limits
        int rawMaxPerPlayer = getIntFromObj(obj, 1, "max-dummies-per-player", "max_dummies_per_player", "max-dummies", "max_dummies");
        this.maxDummiesPerPlayer = Math.max(1, rawMaxPerPlayer);

        int rawMaxServer = getIntFromObj(obj, 20, "max-server-wide-dummies", "max_server_wide_dummies", "max-server-dummies");
        this.maxServerWideDummies = Math.max(1, rawMaxServer);

        // 5. Timing
        int rawCleanup = getIntFromObj(obj, 30, "cleanup-interval-seconds", "cleanup_interval_seconds", "cleanup-interval");
        this.cleanupIntervalSeconds = Math.max(5, rawCleanup);

        int rawRespawn = getIntFromObj(obj, 40, "respawn-delay-ticks", "respawn_delay_ticks", "respawn-delay");
        this.respawnDelayTicks = Math.max(1, rawRespawn);

        // 6. Update Checker
        if (obj.has("update-checker") && obj.get("update-checker").isJsonObject()) {
            JsonObject uc = obj.getAsJsonObject("update-checker");
            this.updateCheckerEnabled = getBooleanFromObj(uc, true, "enabled");
            this.notifyUpdatesEnabled = getBooleanFromObj(uc, true, "notify-players", "notify_players", "notify");
        } else if (obj.has("update_checker") && obj.get("update_checker").isJsonObject()) {
            JsonObject uc = obj.getAsJsonObject("update_checker");
            this.updateCheckerEnabled = getBooleanFromObj(uc, true, "enabled");
            this.notifyUpdatesEnabled = getBooleanFromObj(uc, true, "notify-players", "notify_players", "notify");
        } else {
            this.updateCheckerEnabled = getBooleanFromObj(obj, true, "update-checker-enabled", "update_checker_enabled", "update-checker");
            this.notifyUpdatesEnabled = getBooleanFromObj(obj, true, "update-checker-notify-players", "update_checker_notify_players");
        }
    }

    private void parseFromYaml() {
        var cfg = plugin.getConfig();

        // 1. Cost per hour
        costPerHour = Math.max(1, cfg.getInt("settings.cost-per-hour", 5));

        // 2. Payment item
        String rawItem = cfg.getString("settings.payment-item", "DIAMOND");
        paymentItem = parseMaterial(rawItem, Material.DIAMOND);

        // 3. Display name
        String rawDisp = cfg.getString("settings.payment-item-display-name", null);
        if (rawDisp != null && !rawDisp.trim().isEmpty()) {
            paymentItemDisplayName = rawDisp.trim();
        } else {
            paymentItemDisplayName = formatMaterialDisplayName(paymentItem);
        }

        // 4. Limits
        maxDummiesPerPlayer = Math.max(1, cfg.getInt("settings.max-dummies-per-player", 1));
        maxServerWideDummies = Math.max(1, cfg.getInt("settings.max-server-wide-dummies", 20));

        // 5. Timing
        cleanupIntervalSeconds = Math.max(5, cfg.getInt("settings.cleanup-interval-seconds", 30));
        respawnDelayTicks = Math.max(1, cfg.getInt("settings.respawn-delay-ticks", 40));

        // 6. Update Checker
        updateCheckerEnabled = cfg.getBoolean("settings.update-checker.enabled", true);
        notifyUpdatesEnabled = cfg.getBoolean("settings.update-checker.notify-players", true);
    }

    private int getIntFromObj(JsonObject obj, int def, String... keys) {
        for (String k : keys) {
            if (obj.has(k)) {
                JsonElement elem = obj.get(k);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                    return elem.getAsInt();
                }
                try {
                    return Integer.parseInt(elem.getAsString());
                } catch (NumberFormatException ignored) {}
            }
        }
        return def;
    }

    private String getStringFromObj(JsonObject obj, String def, String... keys) {
        for (String k : keys) {
            if (obj.has(k)) {
                JsonElement elem = obj.get(k);
                if (elem.isJsonPrimitive()) {
                    return elem.getAsString();
                }
            }
        }
        return def;
    }

    private Material parseMaterial(String itemName, Material fallback) {
        if (itemName == null || itemName.trim().isEmpty()) {
            return fallback;
        }
        String cleaned = itemName.trim().toUpperCase();
        if (cleaned.startsWith("MINECRAFT:")) {
            cleaned = cleaned.substring("MINECRAFT:".length());
        }
        try {
            Material match = Material.matchMaterial(cleaned);
            if (match != null) {
                return match;
            }
        } catch (Throwable ignored) {}

        try {
            Material match = Material.valueOf(cleaned);
            if (match != null) {
                return match;
            }
        } catch (Throwable ignored) {}

        logger.warning("Invalid payment item Material: '" + itemName + "'. Falling back to " + fallback.name());
        return fallback;
    }

    private boolean getBooleanFromObj(JsonObject obj, boolean def, String... keys) {
        for (String k : keys) {
            if (obj.has(k)) {
                JsonElement elem = obj.get(k);
                if (elem.isJsonPrimitive()) {
                    if (elem.getAsJsonPrimitive().isBoolean()) {
                        return elem.getAsBoolean();
                    }
                    String s = elem.getAsString();
                    if ("true".equalsIgnoreCase(s)) return true;
                    if ("false".equalsIgnoreCase(s)) return false;
                }
            }
        }
        return def;
    }

    private String formatMaterialDisplayName(Material material) {
        if (material == null) return "Diamond";
        String name = material.name().replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private void logConfigProperties() {
        DebugLogger.log(String.format("CONFIG key=cost-per-hour rawValue=%d parsedValue=%d consumer=TimeSelectionMenu.createTimeOption()",
                costPerHour, costPerHour));
        DebugLogger.log(String.format("CONFIG key=payment-item rawValue=%s parsedValue=%s consumer=ItemCostUtil.removeItems()",
                paymentItem.name(), paymentItem.name()));
        DebugLogger.log(String.format("CONFIG key=payment-item-display-name rawValue=%s parsedValue=%s consumer=GUI/Messages",
                paymentItemDisplayName, paymentItemDisplayName));
        DebugLogger.log(String.format("CONFIG key=max-dummies-per-player rawValue=%d parsedValue=%d consumer=DummyManager.canSpawnMore()",
                maxDummiesPerPlayer, maxDummiesPerPlayer));
        DebugLogger.log(String.format("CONFIG key=max-server-wide-dummies rawValue=%d parsedValue=%d consumer=DummyManager.canSpawnMore()",
                maxServerWideDummies, maxServerWideDummies));
        DebugLogger.log(String.format("CONFIG key=cleanup-interval-seconds rawValue=%d parsedValue=%d consumer=DummyManager.startCleanupTask()",
                cleanupIntervalSeconds, cleanupIntervalSeconds));
        DebugLogger.log(String.format("CONFIG key=respawn-delay-ticks rawValue=%d parsedValue=%d consumer=AFKDummyPlugin.onEnable()",
                respawnDelayTicks, respawnDelayTicks));
        DebugLogger.log(String.format("CONFIG key=update-checker.enabled rawValue=%b parsedValue=%b consumer=UpdateChecker",
                updateCheckerEnabled, updateCheckerEnabled));
        DebugLogger.log(String.format("CONFIG key=update-checker.notify-players rawValue=%b parsedValue=%b consumer=UpdateChecker",
                notifyUpdatesEnabled, notifyUpdatesEnabled));

        logger.info("Configuration active: cost=" + costPerHour + " " + paymentItemDisplayName
                + "/hr, max-per-player=" + maxDummiesPerPlayer + ", max-server=" + maxServerWideDummies
                + ", update-checker=" + updateCheckerEnabled);
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

    /** @return user-friendly display name for the payment currency item */
    public String getPaymentItemDisplayName() {
        return paymentItemDisplayName != null ? paymentItemDisplayName : formatMaterialDisplayName(paymentItem);
    }

    /** @return maximum number of active dummies a single player can own */
    public int getMaxDummiesPerPlayer() {
        return maxDummiesPerPlayer;
    }

    /** @return maximum number of active dummies allowed across the entire server */
    public int getMaxServerWideDummies() {
        return maxServerWideDummies;
    }

    /** @return interval in seconds between cleanup runs for expired dummies */
    public int getCleanupIntervalSeconds() {
        return cleanupIntervalSeconds;
    }

    /** @return delay in ticks before respawning dummies on server start */
    public int getRespawnDelayTicks() {
        return respawnDelayTicks;
    }

    /** @return true if automatic update checking is enabled */
    public boolean isUpdateCheckerEnabled() {
        return updateCheckerEnabled;
    }

    /** @return true if permitted players should receive update notifications */
    public boolean isNotifyUpdatesEnabled() {
        return notifyUpdatesEnabled;
    }
}
