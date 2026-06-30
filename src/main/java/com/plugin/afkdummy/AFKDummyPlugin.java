package com.plugin.afkdummy;

import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.gui.MainMenu;
import com.plugin.afkdummy.listener.GUIListener;
import com.plugin.afkdummy.listener.PlayerListener;
import com.plugin.afkdummy.storage.StorageManager;
import com.plugin.afkdummy.util.SkinUtil;
import com.plugin.afkdummy.util.DebugLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for the AFK Dummy system.
 * <p>
 * Orchestrates all subsystems including configuration, storage, entity management,
 * GUI framework, and event listeners. Handles the complete plugin lifecycle from
 * enable through disable, including server restart recovery.
 * </p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>{@code onEnable()} — Load config → init storage → register listeners/commands → delayed respawn</li>
 *   <li>{@code onDisable()} — Despawn all dummies → save state synchronously → cleanup</li>
 * </ol>
 */
public class AFKDummyPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private StorageManager storageManager;
    private DummyManager dummyManager;

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
        // 5. Register Commands
        // ====================================================================
        // Commands are registered via plugin.yml; we handle them in onCommand()

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
            // This must happen BEFORE saving, as it cleans up NMS entities
            // from the world to prevent ghost/corrupted entities in region files
            dummyManager.despawnAll();
        }

        // ====================================================================
        // 3. Save State Synchronously
        // ====================================================================
        // Use sync save since the scheduler is shutting down
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
            sender.sendMessage("§7 afkdummy reload §f— Reload config");
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

        // Handle sub-commands for players (requires admin permission)
        if (args.length > 0) {
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
                sender.sendMessage("§a§l✓ §aConfiguration reloaded successfully.");
                return true;
            }
            case "list" -> {
                var sessions = dummyManager.getAllSessions();
                if (sessions.isEmpty()) {
                    sender.sendMessage("§7No active dummy sessions.");
                } else {
                    sender.sendMessage("§e§lActive Dummy Sessions (" + sessions.size() + "):");
                    sessions.forEach((uuid, session) -> {
                        sender.sendMessage("§7 • §f" + session.getOwnerName()
                                + " §7— §b" + session.getFormattedTimeRemaining()
                                + " §7remaining");
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
}
