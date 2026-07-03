package com.plugin.afkdummy.entity;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.storage.DummyData;
import com.plugin.afkdummy.storage.StorageManager;
import com.plugin.afkdummy.util.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Central manager for all active dummy player sessions.
 * <p>
 * Handles the complete lifecycle of dummy players including spawning, tracking,
 * expiration cleanup, persistence, and server restart recovery. This class is
 * the single source of truth for all active dummy entities on the server.
 * </p>
 */
public class DummyManager {

    private final AFKDummyPlugin plugin;
    private final ConfigManager config;
    private final StorageManager storage;
    private final Map<UUID, DummySession> activeSessions;
    private BukkitTask cleanupTask;
    private BukkitTask debugTask;

    /**
     * Constructs a new DummyManager.
     *
     * @param plugin  the owning plugin instance
     * @param config  the configuration manager
     * @param storage the storage manager
     */
    public DummyManager(AFKDummyPlugin plugin, ConfigManager config, StorageManager storage) {
        this.plugin = plugin;
        this.config = config;
        this.storage = storage;
        this.activeSessions = new ConcurrentHashMap<>();
    }

    /**
     * Starts the periodic cleanup task that checks for expired sessions.
     * The task runs on the main thread at the interval specified in config.
     */
    public void startCleanupTask() {
        long intervalTicks = config.getCleanupIntervalSeconds() * 20L;
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpired, intervalTicks, intervalTicks);
        plugin.getLogger().info("Cleanup task started (interval: " + config.getCleanupIntervalSeconds() + "s)");

        // Run diagnostics debug task every 10 seconds (200 ticks)
        debugTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runDiagnostics, 200L, 200L);
    }

    /**
     * Stops the periodic cleanup task.
     */
    public void stopCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (debugTask != null) {
            debugTask.cancel();
            debugTask = null;
        }
    }

    /**
     * Spawns a new AFK dummy at the given location for the specified duration.
     *
     * @param owner      the player requesting the dummy
     * @param location   the spawn location
     * @param durationMs how long the dummy should remain active (in milliseconds)
     * @return the created DummySession, or null if spawning failed
     */
    public DummySession spawnDummy(Player owner, Location location, long durationMs) {
        if (location == null || location.getWorld() == null) {
            owner.sendMessage("§c§l✕ §cInvalid location: World is not loaded.");
            return null;
        }

        UUID ownerUUID = owner.getUniqueId();
        String ownerName = owner.getName();

        // Validate limits
        if (activeSessions.containsKey(ownerUUID)) {
            owner.sendMessage("§c§l✕ §cYou already have an active AFK dummy!");
            return null;
        }

        if (activeSessions.size() >= config.getMaxServerWideDummies()) {
            owner.sendMessage("§c§l✕ §cServer-wide dummy limit reached ("
                    + config.getMaxServerWideDummies() + "). Try again later.");
            return null;
        }

        try {
            // Create the dummy player entity
            DummyPlayer dummyPlayer = new DummyPlayer(ownerUUID, ownerName, location, plugin);

            // Spawn it into the world
            dummyPlayer.spawn();

            // Create the session
            long expirationTimestamp = System.currentTimeMillis() + durationMs;
            DummySession session = new DummySession(dummyPlayer, ownerUUID, ownerName, expirationTimestamp);

            // Register the session
            activeSessions.put(ownerUUID, session);

            // Persist to storage
            DummyData data = new DummyData(
                    ownerUUID, ownerName, dummyPlayer.getEntityId(),
                    location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(),
                    expirationTimestamp
            );
            storage.addEntry(data);

            plugin.getLogger().info("Spawned dummy for " + ownerName
                    + " (duration: " + com.plugin.afkdummy.util.TimeUtil.formatDurationLong(durationMs) + ")");
            DebugLogger.log(String.format("Spawned dummy for owner: %s, UUID: %s, loc: %s(%d, %d, %d)",
                    ownerName, ownerUUID, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));

            return session;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn dummy for " + ownerName, e);
            owner.sendMessage("§c§l✕ §cAn error occurred while spawning your dummy. Please contact an admin.");
            return null;
        }
    }

    /**
     * Safely despawns a dummy owned by the specified player.
     *
     * @param ownerUUID the UUID of the dummy's owner
     * @return true if a dummy was found and despawned
     */
    public boolean despawnDummy(UUID ownerUUID) {
        DummySession session = activeSessions.remove(ownerUUID);
        if (session == null) {
            return false;
        }

        session.despawn();
        storage.removeEntry(ownerUUID);

        plugin.getLogger().info("Despawned dummy for " + session.getOwnerName());
        DebugLogger.log(String.format("Despawned dummy for owner: %s, UUID: %s", session.getOwnerName(), ownerUUID));
        return true;
    }

    /**
     * Despawns ALL active dummy players. Called during server shutdown.
     * This method runs synchronously to ensure clean removal before the server stops.
     */
    public void despawnAll() {
        plugin.getLogger().info("Despawning all active dummies (" + activeSessions.size() + " total)...");

        for (Map.Entry<UUID, DummySession> entry : activeSessions.entrySet()) {
            try {
                entry.getValue().despawn();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error despawning dummy for " + entry.getValue().getOwnerName(), e);
            }
        }

        activeSessions.clear();
    }

    /**
     * Respawns dummies from persistent storage after a server restart.
     * <p>
     * This method reads saved session data, validates expiration times,
     * and respawns any dummies that still have remaining time. Expired
     * entries are purged from storage.
     * </p>
     * <p>
     * Should be called after a delay (e.g., 40 ticks) to ensure all worlds
     * are fully loaded before attempting to spawn entities.
     * </p>
     */
    public void respawnFromStorage() {
        List<DummyData> entries = storage.getAllEntries();
        if (entries.isEmpty()) {
            plugin.getLogger().info("No dummy sessions to restore from storage.");
            return;
        }

        plugin.getLogger().info("Attempting to restore " + entries.size() + " dummy session(s)...");

        int restored = 0;
        int expired = 0;
        int failed = 0;

        for (DummyData data : entries) {
            // Check if the session has expired
            if (data.isExpired()) {
                storage.removeEntry(data.getOwnerUUID(), false);
                expired++;
                continue;
            }

            // Check if the world exists
            Location location = data.toLocation();
            if (location == null || location.getWorld() == null) {
                plugin.getLogger().warning("World '" + data.getWorldName()
                        + "' not found for dummy owned by " + data.getOwnerName()
                        + ". Purging entry.");
                storage.removeEntry(data.getOwnerUUID(), false);
                failed++;
                continue;
            }

            // Check server-wide limit
            if (activeSessions.size() >= config.getMaxServerWideDummies()) {
                plugin.getLogger().warning("Server dummy limit reached. Cannot restore dummy for "
                        + data.getOwnerName());
                failed++;
                continue;
            }

            try {
                // Create and spawn the dummy
                DummyPlayer dummyPlayer = new DummyPlayer(
                        data.getOwnerUUID(), data.getOwnerName(), location, plugin);
                dummyPlayer.spawn();

                // Create session with the ORIGINAL expiration timestamp
                DummySession session = new DummySession(
                        dummyPlayer, data.getOwnerUUID(), data.getOwnerName(),
                        data.getExpirationTimestamp());

                activeSessions.put(data.getOwnerUUID(), session);

                // Update the entity ID in storage (may differ after restart)
                data.setDummyEntityId(dummyPlayer.getEntityId());

                restored++;
                plugin.getLogger().info("Restored dummy for " + data.getOwnerName()
                        + " (remaining: " + session.getFormattedTimeRemaining() + ")");

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to restore dummy for " + data.getOwnerName(), e);
                storage.removeEntry(data.getOwnerUUID(), false);
                failed++;
            }
        }

        // Save any changes (updated entity IDs, removed entries)
        storage.saveAsync();

        plugin.getLogger().info("Dummy restoration complete: "
                + restored + " restored, " + expired + " expired, " + failed + " failed.");
    }

    /**
     * Checks for and removes expired sessions.
     */
    private void cleanupExpired() {
        Iterator<Map.Entry<UUID, DummySession>> iterator = activeSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DummySession> entry = iterator.next();
            DummySession session = entry.getValue();

            if (session.isExpired()) {
                session.despawn();
                iterator.remove();
                storage.removeEntry(entry.getKey());

                plugin.getLogger().info("Session expired for " + session.getOwnerName()
                        + ". Dummy despawned.");

                // Notify the owner if they're online
                Player owner = Bukkit.getPlayer(entry.getKey());
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage("§e§l⏰ §eYour AFK dummy session has expired. The dummy has been despawned.");
                }
            }
        }
    }

    /**
     * Handles sending spawn packets to a newly joined player for all active dummies.
     *
     * @param player the player who just joined
     */
    public void handlePlayerJoin(Player player) {
        for (DummySession session : activeSessions.values()) {
            if (session.isSpawned()) {
                session.getDummyPlayer().sendSpawnPacketsTo(player);
            }
        }
    }

    /**
     * Handles a world being unloaded — despawns any dummies in that world.
     *
     * @param worldName the name of the world being unloaded
     */
    public void handleWorldUnload(String worldName) {
        Iterator<Map.Entry<UUID, DummySession>> iterator = activeSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DummySession> entry = iterator.next();
            DummySession session = entry.getValue();
            Location loc = session.getLocation();

            if (loc != null && loc.getWorld() != null
                    && loc.getWorld().getName().equals(worldName)) {
                plugin.getLogger().warning("World '" + worldName
                        + "' unloading — despawning dummy for " + session.getOwnerName());
                session.despawn();
                iterator.remove();
                storage.removeEntry(entry.getKey());
            }
        }
    }

    // ========================================================================
    // Query Methods
    // ========================================================================

    /**
     * Gets the active session for a specific player.
     *
     * @param ownerUUID the player's UUID
     * @return an Optional containing the session if active
     */
    public Optional<DummySession> getSession(UUID ownerUUID) {
        return Optional.ofNullable(activeSessions.get(ownerUUID));
    }

    /**
     * Checks if a player currently has an active dummy.
     *
     * @param ownerUUID the player's UUID
     * @return true if the player has an active session
     */
    public boolean hasActiveDummy(UUID ownerUUID) {
        return activeSessions.containsKey(ownerUUID);
    }

    /**
     * Gets the total number of active dummy sessions.
     *
     * @return the count of active sessions
     */
    public int getActiveCount() {
        return activeSessions.size();
    }

    /**
     * Returns an unmodifiable view of all active sessions.
     *
     * @return map of owner UUID to session
     */
    public Map<UUID, DummySession> getAllSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    /**
     * Checks if a given Bukkit entity is one of our dummy players.
     *
     * @param entityId the entity ID to check
     * @return true if the entity is a managed dummy
     */
    public boolean isDummyEntity(int entityId) {
        return activeSessions.values().stream()
                .anyMatch(s -> s.getDummyPlayer().getEntityId() == entityId);
    }

    /**
     * Finds the session associated with a specific entity ID.
     *
     * @param entityId the entity ID to look up
     * @return an Optional containing the session if found
     */
    public Optional<DummySession> getSessionByEntityId(int entityId) {
        return activeSessions.values().stream()
                .filter(s -> s.getDummyPlayer().getEntityId() == entityId)
                .findFirst();
    }

    /**
     * Checks if a given Bukkit Player entity is one of our dummies.
     *
     * @param player the Player to check
     * @return true if this player is a managed dummy
     */
    public boolean isDummyPlayer(Player player) {
        return activeSessions.values().stream()
                .anyMatch(s -> s.getDummyPlayer().getBukkitPlayer().equals(player));
    }

    /**
     * Finds the session for a given Bukkit Player entity.
     *
     * @param player the Player to look up
     * @return an Optional containing the session if found
     */
    public Optional<DummySession> getSessionByPlayer(Player player) {
        return activeSessions.values().stream()
                .filter(s -> s.getDummyPlayer().getBukkitPlayer().equals(player))
                .findFirst();
    }

    /**
     * Runs natural mob spawning diagnostics for all active dummies and writes to debug.log.
     */
    private void runDiagnostics() {
        if (activeSessions.isEmpty()) {
            return;
        }
        DebugLogger.log("=== Active Dummy Diagnostics Cycle ===");
        for (DummySession session : activeSessions.values()) {
            try {
                Player dummyBukkit = session.getDummyPlayer().getBukkitPlayer();
                Location loc = session.getLocation();
                if (loc == null || loc.getWorld() == null) {
                    DebugLogger.log(String.format("Dummy [%s]: ERROR - Spawn location or world is null!", session.getOwnerName()));
                    continue;
                }

                org.bukkit.World world = loc.getWorld();
                int chunkX = loc.getBlockX() >> 4;
                int chunkZ = loc.getBlockZ() >> 4;
                boolean chunkLoaded = world.isChunkLoaded(chunkX, chunkZ);
                boolean affectsSpawning = dummyBukkit.getAffectsSpawning();
                String gameMode = dummyBukkit.getGameMode().name();
                boolean doMobSpawning = Boolean.TRUE.equals(world.getGameRuleValue(org.bukkit.GameRules.SPAWN_MOBS));

                // Check if the dummy is registered in the server's PlayerList
                // This is the critical indicator that placeNewPlayer worked
                boolean inPlayerList = org.bukkit.Bukkit.getServer().getOnlinePlayers().stream()
                        .anyMatch(p -> p.getUniqueId().equals(dummyBukkit.getUniqueId()));

                // Count nearby monsters (within 32 blocks)
                int monsterCount = world.getNearbyEntities(loc, 32, 32, 32,
                        e -> e instanceof org.bukkit.entity.Monster).size();

                // Count nearby players (excluding dummies)
                long realPlayersNearby = world.getNearbyEntities(loc, 128, 128, 128,
                        e -> e instanceof Player && !isDummyPlayer((Player) e)).size();

                DebugLogger.log(String.format(
                        "Dummy [%s]: Pos=%s(%d, %d, %d) | InPlayerList=%b | ChunkLoaded=%b | affectsSpawning=%b | GameMode=%s | doMobSpawning=%b | NearbyMonsters(32m)=%d | RealPlayersNearby(128m)=%d | EntityValid=%b",
                        session.getOwnerName(),
                        world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                        inPlayerList,
                        chunkLoaded,
                        affectsSpawning,
                        gameMode,
                        doMobSpawning,
                        monsterCount,
                        realPlayersNearby,
                        dummyBukkit.isValid()
                ));
            } catch (Exception e) {
                DebugLogger.log(String.format("Dummy [%s]: Exception in diagnostics: %s", session.getOwnerName(), e.getMessage()));
            }
        }
    }
}
