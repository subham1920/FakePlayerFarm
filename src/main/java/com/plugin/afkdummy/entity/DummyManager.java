package com.plugin.afkdummy.entity;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.storage.DummyData;
import com.plugin.afkdummy.storage.StorageManager;
import com.plugin.afkdummy.util.DebugLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Central manager for all active dummy player sessions.
 * <p>
 * Handles the complete lifecycle of dummy players including spawning, tracking,
 * expiration cleanup, persistence, and server restart recovery. Supports multiple
 * dummies per player up to the configured limit.
 * </p>
 */
public class DummyManager {

    private final AFKDummyPlugin plugin;
    private final ConfigManager config;
    private final StorageManager storage;
    /** Map of Session ID -> DummySession */
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
     */
    public void startCleanupTask() {
        long intervalTicks = config.getCleanupIntervalSeconds() * 20L;
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpired, intervalTicks, intervalTicks);
        plugin.getLogger().info("Cleanup task started (interval: " + config.getCleanupIntervalSeconds() + "s)");

        // Run diagnostics debug task every 10 seconds (200 ticks)
        if (debugTask == null) {
            debugTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runDiagnostics, 200L, 200L);
        }
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
     * Restarts the cleanup task with updated configuration intervals.
     */
    public void restartCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        long intervalTicks = config.getCleanupIntervalSeconds() * 20L;
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpired, intervalTicks, intervalTicks);
        plugin.getLogger().info("Cleanup task rescheduled (interval: " + config.getCleanupIntervalSeconds() + "s)");
    }

    /**
     * Spawns a new AFK dummy at the given location for the specified duration with optional custom name and skin.
     *
     * @param owner       the player requesting the dummy
     * @param location    the spawn location
     * @param durationMs  how long the dummy should remain active (in milliseconds)
     * @param customName  optional custom display name
     * @param skinName    optional custom skin username
     * @return the created DummySession, or null if spawning failed
     */
    public DummySession spawnDummy(Player owner, Location location, long durationMs, String customName, String skinName) {
        if (location == null || location.getWorld() == null) {
            owner.sendMessage("§c§l✕ §cInvalid location: World is not loaded.");
            return null;
        }

        UUID ownerUUID = owner.getUniqueId();
        String ownerName = owner.getName();

        // Validate per-player limit
        int currentCount = getActiveCountByOwner(ownerUUID);
        int maxAllowed = config.getMaxDummiesPerPlayer();
        if (currentCount >= maxAllowed) {
            owner.sendMessage("§c§l✕ §cYou have reached your maximum dummy limit ("
                    + currentCount + "/" + maxAllowed + ")!");
            return null;
        }

        // Validate server-wide limit
        if (activeSessions.size() >= config.getMaxServerWideDummies()) {
            owner.sendMessage("§c§l✕ §cServer-wide dummy limit reached ("
                    + config.getMaxServerWideDummies() + "). Try again later.");
            return null;
        }

        try {
            // Generate unique session ID
            UUID sessionId = UUID.randomUUID();

            // Create the dummy player entity with custom name & skin
            DummyPlayer dummyPlayer = new DummyPlayer(ownerUUID, ownerName, location, sessionId, customName, skinName, plugin);

            // Create the session and pre-register it so PlayerSpawnLocationEvent finds it during placeNewPlayer()
            long expirationTimestamp = System.currentTimeMillis() + durationMs;
            DummySession session = new DummySession(sessionId, dummyPlayer, ownerUUID, ownerName, expirationTimestamp);
            activeSessions.put(sessionId, session);

            try {
                // Spawn it into the world
                dummyPlayer.spawn();
            } catch (Exception e) {
                activeSessions.remove(sessionId);
                throw e;
            }

            // Persist to storage
            DummyData data = new DummyData(
                    sessionId, ownerUUID, ownerName, dummyPlayer.getEntityId(),
                    location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(),
                    expirationTimestamp,
                    customName, skinName
            );
            storage.addEntry(data);

            plugin.getLogger().info("Spawned dummy for " + ownerName + " [Session: " + sessionId
                    + "] (duration: " + com.plugin.afkdummy.util.TimeUtil.formatDurationLong(durationMs) + ")");
            DebugLogger.log(String.format("Spawned dummy for owner: %s, Session: %s, loc: %s(%d, %d, %d)",
                    ownerName, sessionId, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));

            return session;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn dummy for " + ownerName, e);
            owner.sendMessage("§c§l✕ §cAn error occurred while spawning your dummy. Please contact an admin.");
            return null;
        }
    }

    /**
     * Spawns a new AFK dummy at the given location for the specified duration.
     */
    public DummySession spawnDummy(Player owner, Location location, long durationMs) {
        return spawnDummy(owner, location, durationMs, null, null);
    }

    /**
     * Safely despawns a dummy by session ID.
     *
     * @param sessionId the session ID of the dummy
     * @return true if a dummy was found and despawned
     */
    public boolean despawnDummy(UUID sessionId) {
        DummySession session = activeSessions.remove(sessionId);
        if (session == null) {
            return false;
        }

        session.despawn();
        storage.removeEntry(sessionId);

        plugin.getLogger().info("Despawned dummy for " + session.getOwnerName() + " (session: " + sessionId + ")");
        DebugLogger.log(String.format("Despawned dummy for owner: %s, Session: %s", session.getOwnerName(), sessionId));
        return true;
    }

    /**
     * Despawns all dummies owned by a specific player.
     *
     * @param ownerUUID the owner's UUID
     * @return count of despawned dummies
     */
    public int despawnAllForOwner(UUID ownerUUID) {
        List<DummySession> userSessions = getSessionsByOwner(ownerUUID);
        for (DummySession session : userSessions) {
            despawnDummy(session.getSessionId());
        }
        return userSessions.size();
    }

    /**
     * Despawns the dummy owned by the player that is closest to the player's current location.
     *
     * @param player the player requesting despawn
     * @return true if a dummy was despawned
     */
    public boolean despawnNearest(Player player) {
        List<DummySession> userSessions = getSessionsByOwner(player.getUniqueId());
        if (userSessions.isEmpty()) {
            return false;
        }

        Location playerLoc = player.getLocation();
        DummySession nearest = null;
        double minDistanceSq = Double.MAX_VALUE;

        for (DummySession session : userSessions) {
            Location loc = session.getLocation();
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(playerLoc.getWorld())) {
                double distSq = loc.distanceSquared(playerLoc);
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    nearest = session;
                }
            } else if (nearest == null) {
                nearest = session;
            }
        }

        if (nearest != null) {
            return despawnDummy(nearest.getSessionId());
        }
        return false;
    }

    /**
     * Teleports a specific dummy session to a new location.
     * Keeps the remaining duration intact and updates persistent storage.
     *
     * @param sessionId   the unique session ID
     * @param newLocation the destination Location
     * @return true if the dummy was successfully relocated
     */
    public boolean teleportDummy(UUID sessionId, Location newLocation) {
        if (sessionId == null || newLocation == null || newLocation.getWorld() == null) {
            return false;
        }

        DummySession session = activeSessions.get(sessionId);
        if (session == null || !session.isSpawned()) {
            return false;
        }

        try {
            DummyPlayer dp = session.getDummyPlayer();
            dp.teleport(newLocation);
            storage.updateLocation(sessionId, newLocation);

            // Schedule multi-tick verification timeline (T0, T+1, T+2, T+5, T+10, T+20, T+40)
            DebugLogger.scheduleTeleportVerification(
                    plugin,
                    sessionId,
                    session.getOwnerName(),
                    newLocation,
                    () -> dp.getHandle() != null ? new Location(
                            dp.getLocation().getWorld(),
                            dp.getHandle().getX(), dp.getHandle().getY(), dp.getHandle().getZ(),
                            dp.getHandle().getYRot(), dp.getHandle().getXRot()
                    ) : null,
                    () -> dp.getBukkitPlayer() != null ? dp.getBukkitPlayer().getLocation() : null,
                    () -> storage.getBySession(sessionId).map(com.plugin.afkdummy.storage.DummyData::toLocation).orElse(null)
            );

            plugin.getLogger().info(String.format("Teleported AFK dummy for %s (session %s) to %s, %s",
                    session.getOwnerName(), sessionId, newLocation.getWorld().getName(),
                    String.format("%.1f, %.1f, %.1f", newLocation.getX(), newLocation.getY(), newLocation.getZ())));
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to teleport dummy for session " + sessionId, e);
            DebugLogger.log("ERROR: Teleport dummy failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Teleports the nearest active dummy owned by the player (or their only dummy) to their current location.
     *
     * @param player the player requesting the teleport
     * @return true if a dummy was relocated
     */
    public boolean teleportNearestForOwner(Player player) {
        List<DummySession> userSessions = getSessionsByOwner(player.getUniqueId());
        if (userSessions.isEmpty()) {
            return false;
        }

        Location playerLoc = player.getLocation();
        DummySession target = null;
        double minDistanceSq = Double.MAX_VALUE;

        for (DummySession session : userSessions) {
            Location loc = session.getLocation();
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(playerLoc.getWorld())) {
                double distSq = loc.distanceSquared(playerLoc);
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    target = session;
                }
            } else if (target == null) {
                target = session;
            }
        }

        if (target != null) {
            return teleportDummy(target.getSessionId(), playerLoc);
        }
        return false;
    }

    /**
     * Despawns ALL active dummy players. Called during server shutdown.
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
            UUID sessionId = data.getSessionId();

            // Check if the session has expired
            if (data.isExpired()) {
                storage.removeEntry(sessionId, false);
                expired++;
                continue;
            }

            // Check if the world exists
            Location location = data.toLocation();
            if (location == null || location.getWorld() == null) {
                plugin.getLogger().warning("World '" + data.getWorldName()
                        + "' not found for dummy owned by " + data.getOwnerName()
                        + ". Purging entry.");
                storage.removeEntry(sessionId, false);
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

            // Check per-player limit
            if (getActiveCountByOwner(data.getOwnerUUID()) >= config.getMaxDummiesPerPlayer()) {
                plugin.getLogger().warning("Per-player limit exceeded for " + data.getOwnerName()
                        + ". Cannot restore dummy.");
                failed++;
                continue;
            }

            try {
                // Create and pre-register dummy with preserved session ID and custom name/skin
                DummyPlayer dummyPlayer = new DummyPlayer(
                        data.getOwnerUUID(), data.getOwnerName(), location, sessionId,
                        data.getCustomName(), data.getSkinName(), plugin);

                DummySession session = new DummySession(
                        sessionId, dummyPlayer, data.getOwnerUUID(), data.getOwnerName(),
                        data.getExpirationTimestamp());

                activeSessions.put(sessionId, session);

                try {
                    dummyPlayer.spawn();
                    // Update the entity ID in storage
                    data.setDummyEntityId(dummyPlayer.getEntityId());
                    restored++;
                    plugin.getLogger().info("Restored dummy for " + data.getOwnerName()
                            + " [Session: " + sessionId + "] (remaining: " + session.getFormattedTimeRemaining() + ")");
                } catch (Exception spawnEx) {
                    activeSessions.remove(sessionId);
                    throw spawnEx;
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to restore dummy for " + data.getOwnerName(), e);
                storage.removeEntry(sessionId, false);
                failed++;
            }
        }

        if (expired > 0 || failed > 0) {
            storage.saveAsync();
        }

        plugin.getLogger().info("Dummy restoration complete. Restored: " + restored
                + ", Expired: " + expired + ", Failed: " + failed);
    }

    /**
     * Changes the skin of a specific dummy session.
     */
    public boolean setDummySkin(UUID sessionId, String skinPlayerName) {
        DummySession session = activeSessions.get(sessionId);
        if (session == null || !session.isSpawned()) {
            return false;
        }
        session.getDummyPlayer().setSkinByName(skinPlayerName);
        storage.updateSkinName(sessionId, skinPlayerName);
        return true;
    }

    /**
     * Changes the skin of the player's active dummy (or nearest).
     */
    public boolean setDummySkinForOwner(Player player, String skinPlayerName) {
        List<DummySession> userSessions = getSessionsByOwner(player.getUniqueId());
        if (userSessions.isEmpty()) {
            return false;
        }

        Location playerLoc = player.getLocation();
        DummySession target = null;
        double minDistanceSq = Double.MAX_VALUE;

        for (DummySession session : userSessions) {
            Location loc = session.getLocation();
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(playerLoc.getWorld())) {
                double distSq = loc.distanceSquared(playerLoc);
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    target = session;
                }
            } else if (target == null) {
                target = session;
            }
        }

        if (target != null) {
            return setDummySkin(target.getSessionId(), skinPlayerName);
        }
        return false;
    }

    /**
     * Changes the visual name of a specific dummy session.
     */
    public boolean setDummyName(UUID sessionId, String customName) {
        DummySession session = activeSessions.get(sessionId);
        if (session == null || !session.isSpawned()) {
            return false;
        }
        session.getDummyPlayer().setCustomDisplayName(customName);
        storage.updateCustomName(sessionId, customName);
        return true;
    }

    /**
     * Changes the visual name of the player's active dummy (or nearest).
     */
    public boolean setDummyNameForOwner(Player player, String customName) {
        List<DummySession> userSessions = getSessionsByOwner(player.getUniqueId());
        if (userSessions.isEmpty()) {
            return false;
        }

        Location playerLoc = player.getLocation();
        DummySession target = null;
        double minDistanceSq = Double.MAX_VALUE;

        for (DummySession session : userSessions) {
            Location loc = session.getLocation();
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(playerLoc.getWorld())) {
                double distSq = loc.distanceSquared(playerLoc);
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    target = session;
                }
            } else if (target == null) {
                target = session;
            }
        }

        if (target != null) {
            return setDummyName(target.getSessionId(), customName);
        }
        return false;
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
                        + " [Session: " + entry.getKey() + "]. Dummy despawned.");

                Player owner = Bukkit.getPlayer(session.getOwnerUUID());
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage("§e§l⏰ §eOne of your AFK dummy sessions has expired and was despawned.");
                }
            }
        }
    }

    /**
     * Handles sending spawn packets to a newly joined player for all active dummies.
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
     * Gets all active sessions for a specific player.
     *
     * @param ownerUUID the player's UUID
     * @return list of active sessions for this player
     */
    public List<DummySession> getSessionsByOwner(UUID ownerUUID) {
        return activeSessions.values().stream()
                .filter(s -> s.getOwnerUUID().equals(ownerUUID))
                .toList();
    }

    /**
     * Gets the number of active dummies for a specific owner.
     *
     * @param ownerUUID the player's UUID
     * @return count of active sessions for this owner
     */
    public int getActiveCountByOwner(UUID ownerUUID) {
        return (int) activeSessions.values().stream()
                .filter(s -> s.getOwnerUUID().equals(ownerUUID))
                .count();
    }

    /**
     * Checks if a player has at least one active dummy.
     *
     * @param ownerUUID the player's UUID
     * @return true if the player has >= 1 active dummy
     */
    public boolean hasActiveDummy(UUID ownerUUID) {
        return getActiveCountByOwner(ownerUUID) > 0;
    }

    /**
     * Checks if a player is allowed to spawn more dummies.
     *
     * @param ownerUUID the player's UUID
     * @return true if below the configured per-player limit
     */
    public boolean canSpawnMore(UUID ownerUUID) {
        return getActiveCountByOwner(ownerUUID) < config.getMaxDummiesPerPlayer();
    }

    /**
     * Gets a specific session by its unique session ID.
     *
     * @param sessionId the session ID
     * @return an Optional containing the session if active
     */
    public Optional<DummySession> getSession(UUID sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /**
     * Gets the first active session for a specific player (convenience method).
     *
     * @param ownerUUID the player's UUID
     * @return an Optional containing a session if active
     */
    public Optional<DummySession> getFirstSessionByOwner(UUID ownerUUID) {
        return activeSessions.values().stream()
                .filter(s -> s.getOwnerUUID().equals(ownerUUID))
                .findFirst();
    }

    /**
     * Gets the total number of active dummy sessions server-wide.
     *
     * @return the count of active sessions
     */
    public int getActiveCount() {
        return activeSessions.size();
    }

    /**
     * Returns an unmodifiable view of all active sessions.
     *
     * @return map of session ID to session
     */
    public Map<UUID, DummySession> getAllSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    /**
     * Checks if a given Bukkit entity ID belongs to any managed dummy.
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

                boolean inPlayerList = org.bukkit.Bukkit.getServer().getOnlinePlayers().stream()
                        .anyMatch(p -> p.getUniqueId().equals(dummyBukkit.getUniqueId()));

                int monsterCount = world.getNearbyEntities(loc, 32, 32, 32,
                        e -> e instanceof org.bukkit.entity.Monster).size();

                long realPlayersNearby = world.getNearbyEntities(loc, 128, 128, 128,
                        e -> e instanceof Player && !isDummyPlayer((Player) e)).size();

                DebugLogger.log(String.format(
                        "Dummy [%s (Session: %s)]: Pos=%s(%.4f, %.4f, %.4f, yaw=%.2f, pitch=%.2f) | InPlayerList=%b | ChunkLoaded=%b | affectsSpawning=%b | GameMode=%s | doMobSpawning=%b | NearbyMonsters(32m)=%d | RealPlayersNearby(128m)=%d | EntityValid=%b",
                        session.getOwnerName(), session.getSessionId(),
                        world.getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
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

    /**
     * Searches server entity registries and player list to detect any duplicate dummy entities.
     */
    public List<String> checkForDuplicates() {
        List<String> reports = new ArrayList<>();
        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            Map<UUID, Integer> uuidCounts = new HashMap<>();
            Map<Integer, Integer> idCounts = new HashMap<>();

            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                uuidCounts.put(sp.getUUID(), uuidCounts.getOrDefault(sp.getUUID(), 0) + 1);
                idCounts.put(sp.getId(), idCounts.getOrDefault(sp.getId(), 0) + 1);
            }

            for (Map.Entry<UUID, DummySession> entry : activeSessions.entrySet()) {
                UUID sessionId = entry.getKey();
                DummySession session = entry.getValue();
                DummyPlayer dp = session.getDummyPlayer();
                if (dp == null || dp.getHandle() == null) continue;

                UUID entityUUID = dp.getHandle().getUUID();
                int entityId = dp.getEntityId();
                int countByUUID = uuidCounts.getOrDefault(entityUUID, 0);
                int countById = idCounts.getOrDefault(entityId, 0);

                if (countByUUID > 1 || countById > 1) {
                    reports.add(String.format("DUPLICATE ENTITY DETECTED: session=%s owner=%s UUID_copies=%d ID_copies=%d loc=%s",
                            sessionId, session.getOwnerName(), countByUUID, countById, dp.getLocation()));
                }
            }
        } catch (Throwable t) {
            DebugLogger.log("Warning in duplicate check: " + t.getMessage());
        }
        return reports;
    }
}
