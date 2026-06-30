package com.plugin.afkdummy.listener;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.entity.DummySession;
import com.plugin.afkdummy.gui.MainMenu;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.util.Vector;
import io.papermc.paper.event.entity.EntityKnockbackEvent;

import java.util.Optional;

/**
 * Handles all entity-related events for dummy players, including:
 * <ul>
 *   <li>Damage prevention (all damage causes)</li>
 *   <li>Entity interaction routing (owner → GUI, others → cancel)</li>
 *   <li>Displacement prevention (pistons, water, explosions, vehicles)</li>
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
     * Covers void damage, fire, suffocation, explosions, drowning,
     * entity attacks, projectiles, and all other damage causes.
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
     * <p>
     * If the clicker is the owner → opens the management GUI.
     * If the clicker is anyone else → silently cancels the interaction.
     * </p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();

        if (!(clicked instanceof Player clickedPlayer)) return;

        Optional<DummySession> sessionOpt = dummyManager.getSessionByPlayer(clickedPlayer);
        if (sessionOpt.isEmpty()) return;

        // Always cancel the interaction to prevent inventory viewing
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
     * This prevents hostile mobs from attacking the invulnerable dummy.
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
    // Displacement Prevention
    // ========================================================================

    /**
     * Prevents entities (including dummies) from being pushed by pistons.
     * Checks if any affected block would displace a dummy entity.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (org.bukkit.block.Block block : event.getBlocks()) {
            org.bukkit.Location targetLoc = block.getLocation().add(
                    event.getDirection().getModX(),
                    event.getDirection().getModY(),
                    event.getDirection().getModZ()
            );

            for (DummySession session : dummyManager.getAllSessions().values()) {
                org.bukkit.Location dummyLoc = session.getLocation();
                if (dummyLoc != null && dummyLoc.getWorld() != null
                        && dummyLoc.getWorld().equals(targetLoc.getWorld())
                        && dummyLoc.getBlockX() == targetLoc.getBlockX()
                        && dummyLoc.getBlockY() == targetLoc.getBlockY()
                        && dummyLoc.getBlockZ() == targetLoc.getBlockZ()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    /**
     * Prevents dummies from being pulled by sticky pistons.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (org.bukkit.block.Block block : event.getBlocks()) {
            org.bukkit.Location targetLoc = block.getLocation();

            for (DummySession session : dummyManager.getAllSessions().values()) {
                org.bukkit.Location dummyLoc = session.getLocation();
                if (dummyLoc != null && dummyLoc.getWorld() != null
                        && dummyLoc.getWorld().equals(targetLoc.getWorld())
                        && dummyLoc.getBlockX() == targetLoc.getBlockX()
                        && dummyLoc.getBlockY() == targetLoc.getBlockY()
                        && dummyLoc.getBlockZ() == targetLoc.getBlockZ()) {
                    event.setCancelled(true);
                    return;
                }
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
     * Prevents dummies from being knocked back by explosions.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // Reset velocity for any dummies near the explosion
        if (event.getLocation().getWorld() == null) return;

        for (Entity entity : event.getLocation().getWorld()
                .getNearbyEntities(event.getLocation(), 10, 10, 10)) {
            if (isDummy(entity)) {
                // Schedule velocity reset for next tick
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
     * Prevents dummies from being picked up or pushed by any entity collision.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityKnockback(EntityKnockbackEvent event) {
        if (isDummy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ========================================================================
    // Player Join / World Unload
    // ========================================================================

    /**
     * Sends dummy spawn packets to newly joined players so they can see
     * all active dummies in the world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Delay by 10 ticks to ensure the player is fully connected
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                dummyManager.handlePlayerJoin(player);
            }
        }, 10L);
    }

    /**
     * Handles a world being unloaded — gracefully despawns any dummies
     * in that world to prevent ghost entities.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldUnload(WorldUnloadEvent event) {
        dummyManager.handleWorldUnload(event.getWorld().getName());
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Checks if a given entity is one of our managed dummy players.
     *
     * @param entity the entity to check
     * @return true if the entity is a dummy
     */
    private boolean isDummy(Entity entity) {
        if (!(entity instanceof Player player)) return false;
        return dummyManager.isDummyPlayer(player);
    }
}
