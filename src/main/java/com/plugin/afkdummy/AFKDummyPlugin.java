package com.plugin.afkdummy;

import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.gui.MainMenu;
import com.plugin.afkdummy.listener.GUIListener;
import com.plugin.afkdummy.listener.PlayerListener;
import com.plugin.afkdummy.storage.StorageManager;
import com.plugin.afkdummy.util.SkinUtil;
import com.plugin.afkdummy.util.DebugLogger;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Main plugin class for the AFK Dummy system.
 * <p>
 * Orchestrates all subsystems including configuration, storage, entity management,
 * GUI framework, event listeners, and bStats metrics reporting.
 * </p>
 */
public class AFKDummyPlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 33409;

    private ConfigManager configManager;
    private StorageManager storageManager;
    private DummyManager dummyManager;
    private Metrics metrics;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();

        // Initialize Debug Logger
        DebugLogger.init(this);

        // ====================================================================
        // 1. Load Configuration
        // ====================================================================
        configManager = new ConfigManager(this);

        // ====================================================================
        // 2. Initialize Storage
        // ====================================================================
        storageManager = new StorageManager(this);
        storageManager.loadSync();

        // ====================================================================
        // 3. Initialize Dummy Manager
        // ====================================================================
        dummyManager = new DummyManager(this, configManager, storageManager);

        // ====================================================================
        // 4. Register Event Listeners
        // ====================================================================
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(), this);

        // ====================================================================
        // 5. Initialize bStats Metrics
        // ====================================================================
        try {
            metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            metrics.addCustomChart(new SingleLineChart("active_dummies", () -> dummyManager.getActiveCount()));
            metrics.addCustomChart(new SimplePie("payment_item", () -> configManager.getPaymentItem().name()));
            metrics.addCustomChart(new SimplePie("max_dummies_per_player", () -> String.valueOf(configManager.getMaxDummiesPerPlayer())));
            getLogger().info("bStats metrics initialized successfully.");
        } catch (Exception e) {
            getLogger().warning("Failed to initialize bStats metrics: " + e.getMessage());
        }

        // ====================================================================
        // 6. Schedule Delayed Respawn from Storage
        // ====================================================================
        // Wait for worlds to fully load before respawning dummies
        int respawnDelay = configManager.getRespawnDelayTicks();
        getServer().getScheduler().runTaskLater(this, () -> {
            dummyManager.respawnFromStorage();
            dummyManager.startCleanupTask();
        }, respawnDelay);

        long elapsed = System.currentTimeMillis() - startTime;
        getLogger().info("AFKDummy v" + getDescription().getVersion()
                + " enabled successfully! (" + elapsed + "ms)");
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down AFKDummy...");

        // ====================================================================
        // 1. Stop Cleanup Task
        // ====================================================================
        if (dummyManager != null) {
            dummyManager.stopCleanupTask();

            // ================================================================
            // 2. Despawn All Active Dummies
            // ================================================================
            dummyManager.despawnAll();
        }

        // ====================================================================
        // 3. Save State Synchronously
        // ====================================================================
        if (storageManager != null) {
            storageManager.saveSync();
        }

        // ====================================================================
        // 4. Clear Caches
        // ====================================================================
        SkinUtil.clearCache();

        getLogger().info("AFKDummy disabled. All dummies despawned safely.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("afkdummy")) {
            return false;
        }

        // Handle console or non-player command execution
        if (!(sender instanceof Player)) {
            if (args.length > 0) {
                return handleAdminCommand(sender, args);
            }
            sender.sendMessage("§e§lAFK Dummy Console Commands:");
            sender.sendMessage("§7 afkdummy reload §f— Reload config and reschedule tasks");
            sender.sendMessage("§7 afkdummy list §f— List active sessions");
            sender.sendMessage("§7 afkdummy despawnall §f— Remove all dummies");
            return true;
        }

        Player player = (Player) sender;

        // Check player permission
        if (!player.hasPermission("afkdummy.use")) {
            player.sendMessage("§c§l✕ §cYou don't have permission to use this command.");
            return true;
        }

        // Handle sub-commands for players
        if (args.length > 0) {
            String sub = args[0].toLowerCase();
            if (sub.equals("tp") || sub.equals("move") || sub.equals("relocate") || sub.equals("here")) {
                if (dummyManager.teleportNearestForOwner(player)) {
                    player.sendMessage("§a§l✓ §aTeleported your AFK dummy to your current location!");
                    try {
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    } catch (Throwable ignored) {}
                } else {
                    player.sendMessage("§c§l✕ §cYou don't have any active AFK dummies to teleport.");
                }
                return true;
            }

            if (sub.equals("skin")) {
                if (args.length < 2) {
                    player.sendMessage("§c§l✕ §cUsage: /afkdummy skin <playerName>");
                    return true;
                }
                String skinPlayer = args[1];
                if (dummyManager.setDummySkinForOwner(player, skinPlayer)) {
                    player.sendMessage("§a§l✓ §aFetching and applying skin from §f" + skinPlayer + "§a...");
                } else {
                    player.sendMessage("§c§l✕ §cYou don't have any active AFK dummies to update.");
                }
                return true;
            }

            if (sub.equals("name")) {
                if (args.length < 2) {
                    player.sendMessage("§c§l✕ §cUsage: /afkdummy name <customName>");
                    return true;
                }
                String newName = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                if (dummyManager.setDummyNameForOwner(player, newName)) {
                    player.sendMessage("§a§l✓ §aUpdated dummy display name to: §f" + newName);
                } else {
                    player.sendMessage("§c§l✕ §cYou don't have any active AFK dummies to update.");
                }
                return true;
            }

            if (player.hasPermission("afkdummy.admin")) {
                return handleAdminCommand(player, args);
            } else {
                player.sendMessage("§c§l✕ §cYou don't have permission to run admin sub-commands.");
                return true;
            }
        }

        // Default: open the main GUI
        new MainMenu(this, player).open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("afkdummy")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("tp");
            completions.add("move");
            completions.add("skin");
            completions.add("name");
            if (sender.hasPermission("afkdummy.admin")) {
                completions.addAll(List.of("reload", "list", "despawnall", "help"));
            }
            return completions.stream()
                    .filter(c -> c.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }

    /**
     * Handles admin sub-commands for both players and the console.
     *
     * @param sender the command sender
     * @param args   the command arguments
     * @return true if the command was handled
     */
    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                configManager.reload();
                if (dummyManager != null) {
                    dummyManager.restartCleanupTask();
                }
                sender.sendMessage("§a§l✓ §aConfiguration reloaded and tasks updated successfully.");
                return true;
            }
            case "list" -> {
                var sessions = dummyManager.getAllSessions();
                if (sessions.isEmpty()) {
                    sender.sendMessage("§7No active dummy sessions.");
                } else {
                    sender.sendMessage("§e§lActive Dummy Sessions (" + sessions.size() + "):");
                    sessions.forEach((sessionId, session) -> {
                        org.bukkit.Location loc = session.getLocation();
                        String locStr = loc != null && loc.getWorld() != null
                                ? loc.getWorld().getName() + " (" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")"
                                : "Unknown";
                        sender.sendMessage("§7 • §f" + session.getOwnerName()
                                + " §7[§e" + locStr + "§7] — §b" + session.getFormattedTimeRemaining()
                                + " §7remaining (ID: §8" + sessionId.toString().substring(0, 8) + "§7)");
                    });
                }
                return true;
            }
            case "despawnall" -> {
                int count = dummyManager.getActiveCount();
                dummyManager.despawnAll();
                storageManager.clear();
                sender.sendMessage("§a§l✓ §aDespawned " + count + " dummy(s).");
                return true;
            }
            case "help" -> {
                sender.sendMessage("§e§lAFK Dummy Admin Commands:");
                sender.sendMessage("§7 /afkdummy §f— Open the GUI");
                sender.sendMessage("§7 /afkdummy reload §f— Reload config");
                sender.sendMessage("§7 /afkdummy list §f— List active sessions");
                sender.sendMessage("§7 /afkdummy despawnall §f— Remove all dummies");
                return true;
            }
            default -> {
                sender.sendMessage("§cUnknown sub-command. Use §f/afkdummy help §cfor help.");
                return true;
            }
        }
    }

    // ========================================================================
    // Accessors for other classes
    // ========================================================================

    /** @return the configuration manager */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /** @return the storage manager */
    public StorageManager getStorageManager() {
        return storageManager;
    }

    /** @return the dummy entity manager */
    public DummyManager getDummyManager() {
        return dummyManager;
    }

    /** @return the bStats metrics instance */
    public Metrics getMetrics() {
        return metrics;
    }
}
