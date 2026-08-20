package com.plugin.afkdummy.listener;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.entity.DummySession;
import com.plugin.afkdummy.gui.MainMenu;
import com.plugin.afkdummy.util.DebugLogger;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import io.papermc.paper.event.entity.EntityKnockbackEvent;

import java.util.Optional;

/**
 * Handles all entity-related events for dummy players, including:
 * <ul>
 *   <li>Damage prevention (all damage causes)</li>
 *   <li>Entity interaction routing (owner → GUI, others → cancel)</li>
 *   <li>Displacement prevention (pistons, water, explosions, portals, vehicles)</li>
 *   <li>Combat target cancellation</li>
 *   <li>World unload handling</li>
 *   <li>New player join packet sending</li>
 * </ul>
 */
public class PlayerListener implements Listener {

    private final AFKDummyPlugin plugin;
    private final DummyManager dummyManager;

    /**
     * Constructs a new PlayerListener.
     *
     * @param plugin the owning plugin instance
     */
    public PlayerListener(AFKDummyPlugin plugin) {
        this.plugin = plugin;
        this.dummyManager = plugin.getDummyManager();
    }

    // ========================================================================
    // Damage Prevention
    // ========================================================================

    /**
     * Cancels ALL damage to dummy entities.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (isDummy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Also explicitly cancel EntityDamageByEntity for thorough coverage.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (isDummy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ========================================================================
    // Entity Interaction
    // ========================================================================

    /**
     * Handles player right-click interaction with dummy entities.
     * Filters for main hand to prevent double-opening glitches.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Prevent double execution from offhand click
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Player clickedPlayer)) return;

        Optional<DummySession> sessionOpt = dummyManager.getSessionByPlayer(clickedPlayer);
        if (sessionOpt.isEmpty()) return;

        // Always cancel the interaction to prevent trade / inventory viewing
        event.setCancelled(true);

        DummySession session = sessionOpt.get();
        Player clicker = event.getPlayer();

        // If the clicker is the owner, open the management GUI
        if (clicker.getUniqueId().equals(session.getOwnerUUID())) {
            new MainMenu(plugin, clicker).open(clicker);
        }
    }

    // ========================================================================
    // Target Prevention
    // ========================================================================

    /**
     * Prevents mobs from targeting dummy entities.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetEvent event) {
        if (event.getTarget() != null && isDummy(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents mobs from targeting dummy entities via living entity targeting.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTargetLivingEntity(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() != null && isDummy(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    // ========================================================================
    // Displacement & Piston Prevention
    // ========================================================================

    /**
     * Prevents dummies from being pushed by pistons or crushed by piston heads.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        BlockFace direction = event.getDirection();

        // Check piston head destination
        Location headTarget = event.getBlock().getRelative(direction).getLocation();
        if (isAnyDummyAt(headTarget)) {
            event.setCancelled(true);
            return;
        }

        // Check all pushed blocks
        for (Block block : event.getBlocks()) {
            Location targetLoc = block.getLocation().add(
                    direction.getModX(),
                    direction.getModY(),
                    direction.getModZ()
            );

            if (isAnyDummyAt(targetLoc)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Prevents dummies from being pulled by sticky pistons.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        BlockFace direction = event.getDirection();

        for (Block block : event.getBlocks()) {
            // Check the location where the block will be pulled to
            Location targetLoc = block.getLocation().add(
                    direction.getModX(),
                    direction.getModY(),
                    direction.getModZ()
            );

            if (isAnyDummyAt(targetLoc) || isAnyDummyAt(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Prevents dummies from entering vehicles (boats, minecarts).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (isDummy(event.getEntered())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents dummies from being knocked back by entity explosions.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        resetDummyVelocityNear(event.getLocation());
    }

    /**
     * Prevents dummies from being knocked back by block explosions (beds / respawn anchors).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        resetDummyVelocityNear(event.getBlock().getLocation());
    }

    private void resetDummyVelocityNear(Location location) {
        if (location == null || location.getWorld() == null) return;

        for (Entity entity : location.getWorld().getNearbyEntities(location, 10, 10, 10)) {
            if (isDummy(entity)) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    entity.setVelocity(new Vector(0, 0, 0));
                });
            }
        }
    }

    /**
     * Cancels fishing rod hooking on dummy entities.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getHitEntity() != null && isDummy(event.getHitEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents dummies from being knocked back.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityKnockback(EntityKnockbackEvent event) {
        if (isDummy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents dummies from being teleported through portals.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (isDummy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents dummy player portal interactions.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (isDummy(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ========================================================================
    // Player Join / Spawn / World Unload
    // ========================================================================

    /**
     * Guarantees the initial spawn location of a dummy during placeNewPlayer()
     * directly matches its designated spawn location, preventing fallback to world spawn.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerSpawnLocation(org.spigotmc.event.player.PlayerSpawnLocationEvent event) {
        Player player = event.getPlayer();
        if (dummyManager.isDummyPlayer(player)) {
            Optional<DummySession> session = dummyManager.getSessionByPlayer(player);
            if (session.isPresent()) {
                Location loc = session.get().getLocation();
                if (loc != null && loc.getWorld() != null) {
                    event.setSpawnLocation(loc);
                    DebugLogger.trace("PlayerListener.java:onPlayerSpawnLocation",
                            "Overrode PlayerSpawnLocationEvent for dummy " + player.getName() + " to " + loc);
                }
            }
        }
    }

    /**
     * Sends dummy spawn packets to newly joined players.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                dummyManager.handlePlayerJoin(player);
            }
        }, 10L);
    }

    /**
     * Handles a world being unloaded.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldUnload(WorldUnloadEvent event) {
        dummyManager.handleWorldUnload(event.getWorld().getName());
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    private boolean isDummy(Entity entity) {
        if (!(entity instanceof Player player)) return false;
        return dummyManager.isDummyPlayer(player);
    }

    private boolean isAnyDummyAt(Location blockLoc) {
        if (blockLoc == null || blockLoc.getWorld() == null) return false;

        for (DummySession session : dummyManager.getAllSessions().values()) {
            Location dummyLoc = session.getLocation();
            if (dummyLoc != null && dummyLoc.getWorld() != null
                    && dummyLoc.getWorld().equals(blockLoc.getWorld())
                    && dummyLoc.getBlockX() == blockLoc.getBlockX()
                    && dummyLoc.getBlockY() == blockLoc.getBlockY()
                    && dummyLoc.getBlockZ() == blockLoc.getBlockZ()) {
                return true;
            }
        }
        return false;
    }
}
