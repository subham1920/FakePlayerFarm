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
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.plugin.afkdummy.entity.DummySession;
import com.plugin.afkdummy.entity.DummyPlayer;

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
    private com.plugin.afkdummy.gui.InputManager inputManager;
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
        // 3. Initialize Dummy Manager & Input Manager
        // ====================================================================
        dummyManager = new DummyManager(this, configManager, storageManager);
        inputManager = new com.plugin.afkdummy.gui.InputManager(this);
        inputManager.start();

        // ====================================================================
        // 4. Register Event Listeners
        // ====================================================================
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(), this);
        getServer().getPluginManager().registerEvents(inputManager, this);

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
        // 0. Shutdown Input Manager
        // ====================================================================
        if (inputManager != null) {
            inputManager.shutdown();
        }

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
        // 4. Clear Caches & Close Debug Logger
        // ====================================================================
        SkinUtil.clearCache();
        DebugLogger.close();

        getLogger().info("AFKDummy disabled. All dummies despawned safely.");
    }

    private static String formatLoc(Location l) {
        if (l == null || l.getWorld() == null) return "null";
        return String.format("%s(%.1f, %.1f, %.1f)", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
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
            case "debug" -> {
                if (!sender.hasPermission("afkdummy.admin")) {
                    sender.sendMessage("§cYou do not have permission to run debug diagnostics.");
                    return true;
                }
                sender.sendMessage("§6§l=== AFKDummy Debug Diagnostics ===");
                sender.sendMessage("§7• Branch: §fdebug §7| Base: §fa85243c");
                sender.sendMessage("§7• Active Dummies: §f" + dummyManager.getActiveCount() + "§7/§f" + configManager.getMaxServerWideDummies());
                sender.sendMessage("§7• Stored Dummies: §f" + storageManager.getAllEntries().size());

                DebugLogger.log("--- MANUAL DEBUG COMMAND EXECUTED BY " + sender.getName() + " ---");
                DebugLogger.log("Active count: " + dummyManager.getActiveCount() + ", Stored count: " + storageManager.getAllEntries().size());

                for (Map.Entry<UUID, DummySession> entry : dummyManager.getAllSessions().entrySet()) {
                    UUID id = entry.getKey();
                    DummySession s = entry.getValue();
                    DummyPlayer dp = s.getDummyPlayer();
                    Location bukkitLoc = dp != null && dp.getBukkitPlayer() != null ? dp.getBukkitPlayer().getLocation() : null;
                    Location nmsLoc = dp != null && dp.getHandle() != null ? new Location(
                            dp.getHandle().level().getWorld(),
                            dp.getHandle().getX(), dp.getHandle().getY(), dp.getHandle().getZ(),
                            dp.getHandle().getYRot(), dp.getHandle().getXRot()
                    ) : null;
                    Location storedLoc = s.getLocation();

                    String info = String.format("Session: %s | Owner: %s | Name: %s | Skin: %s | NMS: %s | Bukkit: %s | Stored: %s",
                            id, s.getOwnerName(), s.getCustomName(), s.getSkinName(),
                            formatLoc(nmsLoc), formatLoc(bukkitLoc), formatLoc(storedLoc));
                    DebugLogger.log("  " + info);
                    sender.sendMessage("§e[Dummy " + id.toString().substring(0, 8) + "] §f" + s.getOwnerName() + " (" + (s.getCustomName() != null ? s.getCustomName() : "default") + ")");
                    sender.sendMessage("  §7NMS: §f" + (nmsLoc != null ? String.format("%.4f, %.4f, %.4f (yaw=%.2f, pitch=%.2f)", nmsLoc.getX(), nmsLoc.getY(), nmsLoc.getZ(), nmsLoc.getYaw(), nmsLoc.getPitch()) : "null"));
                    sender.sendMessage("  §7Bukkit: §f" + (bukkitLoc != null ? String.format("%.4f, %.4f, %.4f (yaw=%.2f, pitch=%.2f)", bukkitLoc.getX(), bukkitLoc.getY(), bukkitLoc.getZ(), bukkitLoc.getYaw(), bukkitLoc.getPitch()) : "null"));
                }

                var dupes = dummyManager.checkForDuplicates();
                if (dupes.isEmpty()) {
                    sender.sendMessage("§a• Duplicate Entity Check: §f0 duplicates detected (OK)");
                    DebugLogger.log("Duplicate Check: Clean (0 duplicates)");
                } else {
                    for (String d : dupes) {
                        sender.sendMessage("§c§l• " + d);
                        DebugLogger.log("WARNING: " + d);
                    }
                }

                sender.sendMessage("§aFull state snapshot written to §fplugins/AFKDummy/latest-debug.txt");
                return true;
            }
            case "help" -> {
                sender.sendMessage("§e§lAFK Dummy Admin Commands:");
                sender.sendMessage("§7 /afkdummy §f— Open the GUI");
                sender.sendMessage("§7 /afkdummy reload §f— Reload config");
                sender.sendMessage("§7 /afkdummy list §f— List active sessions");
                sender.sendMessage("§7 /afkdummy despawnall §f— Remove all dummies");
                sender.sendMessage("§7 /afkdummy debug §f— Run diagnostic checks");
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

    /** @return the chat input manager */
    public com.plugin.afkdummy.gui.InputManager getInputManager() {
        return inputManager;
    }
}
