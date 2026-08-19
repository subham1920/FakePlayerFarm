package com.plugin.afkdummy.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.plugin.afkdummy.util.SkinUtil;
import com.plugin.afkdummy.util.DebugLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Wraps a NMS {@link ServerPlayer} to create a fake player entity that behaves
 * identically to a real player for chunk loading and mob spawning mechanics.
 * <p>
 * The dummy player is injected into the server's player list via
 * {@link net.minecraft.server.players.PlayerList#placeNewPlayer}.
 * Supports multiple dummies per owner with unique session UUIDs.
 * </p>
 */
public class DummyPlayer {

    private final UUID sessionId;
    private final ServerPlayer handle;
    private final UUID ownerUUID;
    private final String ownerName;
    private Location spawnLocation;
    private final Plugin plugin;

    /** The spoofed network connection, retained for lifecycle cleanup. */
    private final Connection connection;

    /** The authentication cookie used during placeNewPlayer. */
    private final CommonListenerCookie cookie;

    private boolean spawned = false;

    /**
     * Creates a new DummyPlayer with a unique session ID.
     *
     * @param ownerUUID the UUID of the player who owns this dummy
     * @param ownerName the display name of the owner
     * @param location  the spawn location for the dummy
     * @param sessionId the unique session ID for this dummy
     * @param plugin    the owning plugin instance
     */
    public DummyPlayer(UUID ownerUUID, String ownerName, Location location, UUID sessionId, Plugin plugin) {
        this.sessionId = sessionId != null ? sessionId : UUID.randomUUID();
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.spawnLocation = location.clone();
        this.plugin = plugin;

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();

        // Create GameProfile with unique UUID and compliant username
        UUID dummyUUID = generateDummyUUID(ownerUUID, this.sessionId);
        String dummyProfileName = generateProfileName(ownerName, this.sessionId);
        GameProfile profile = new GameProfile(dummyUUID, dummyProfileName);

        // Create ClientInformation with default settings
        ClientInformation clientInfo = ClientInformation.createDefault();

        // Create the ServerPlayer entity
        this.handle = new ServerPlayer(server, level, profile, clientInfo);

        // Pre-set position
        handle.setPos(location.getX(), location.getY(), location.getZ());
        handle.setRot(location.getYaw(), location.getPitch());

        // Set up spoofed network connection
        this.connection = createSpoofedConnection();
        this.cookie = new CommonListenerCookie(profile, 0, clientInfo, false, "vanilla",
                java.util.Collections.emptySet(), new io.papermc.paper.util.KeepAlive());
        setupMockPacketListener(server);

        // Load the owner's skin asynchronously
        loadOwnerSkin(profile);
    }

    /**
     * Creates a new DummyPlayer, generating a random session ID.
     */
    public DummyPlayer(UUID ownerUUID, String ownerName, Location location, Plugin plugin) {
        this(ownerUUID, ownerName, location, UUID.randomUUID(), plugin);
    }

    /**
     * Creates a spoofed {@link Connection} backed by an {@link EmbeddedChannel} with outbound release handler.
     * <p>
     * Memory Leak Fix: Intercepts and releases all outbound packets/buffers to prevent
     * accumulation in {@code EmbeddedChannel.outboundMessages}.
     * </p>
     */
    private Connection createSpoofedConnection() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ReferenceCountUtil.release(msg);
                    }
                },
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        ReferenceCountUtil.release(msg);
                        promise.setSuccess();
                    }
                }
        ) {
            @Override
            public java.net.SocketAddress remoteAddress() {
                return new java.net.InetSocketAddress("127.0.0.1", 0);
            }

            @Override
            public java.net.SocketAddress localAddress() {
                return new java.net.InetSocketAddress("127.0.0.1", 0);
            }
        };

        Connection conn = new Connection(PacketFlow.SERVERBOUND);
        conn.channel = channel;
        conn.address = channel.remoteAddress();
        channel.pipeline().addLast("packet_handler", conn);

        return conn;
    }

    /**
     * Sets up the mock packet listener.
     */
    private void setupMockPacketListener(MinecraftServer server) {
        try {
            ServerGamePacketListenerImpl listener = new ServerGamePacketListenerImpl(
                    server, connection, handle, cookie) {
                @Override
                public void send(Packet<?> packet) {
                    // Outbound packets handled and released by channel pipeline
                }

                @Override
                public void disconnect(net.minecraft.network.chat.Component reason) {
                    // No-op: dummy cannot be disconnected via network
                }

                @Override
                public boolean isAcceptingMessages() {
                    return true;
                }
            };

            handle.connection = listener;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to set up mock packet listener for dummy player!", e);
            throw new IllegalStateException("Cannot create dummy player: network setup failed", e);
        }
    }

    /**
     * Loads the owner's skin asynchronously from Mojang's API and applies it.
     */
    private void loadOwnerSkin(GameProfile profile) {
        SkinUtil.fetchSkinAsync(ownerUUID, (Property textures) -> {
            if (textures != null) {
                SkinUtil.applySkin(profile, textures);

                // If already spawned, re-send entity and player info packets to update skin in viewports
                if (spawned) {
                    resendPlayerInfoToAll();
                }
            }
        }, plugin);
    }

    /**
     * Spawns the dummy player into the world using placeNewPlayer.
     * Guarantees coordinates are properly enforced post-injection.
     * <p>
     * Deletes any stale playerdata for this dummy's UUID before spawning
     * to prevent placeNewPlayer from overriding the target position.
     * </p>
     */
    public void spawn() {
        if (spawned) {
            plugin.getLogger().warning("Attempted to spawn an already-spawned dummy for " + ownerName);
            return;
        }

        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            ServerLevel level = ((CraftWorld) spawnLocation.getWorld()).getHandle();

            // Delete any stale playerdata that could override our target position
            deletePlayerData(handle.getUUID());

            // Inject the player into the server list and world
            server.getPlayerList().placeNewPlayer(connection, handle, cookie);

            // Re-enforce the exact target location AFTER placeNewPlayer
            // (placeNewPlayer might load old coords from playerdata or world spawn)
            handle.setPos(spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ());
            handle.setRot(spawnLocation.getYaw(), spawnLocation.getPitch());
            handle.setOldPosAndRot();

            // Force teleport at NMS level to ensure correct position data is sent to all clients
            handle.teleportTo(level, spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ(),
                    java.util.Set.of(), spawnLocation.getYaw(), spawnLocation.getPitch(), true);

            // Post-spawn configuration
            handle.setGameMode(GameType.SURVIVAL);
            handle.setInvulnerable(true);
            handle.setNoGravity(true);
            handle.setSilent(true);
            handle.getBukkitEntity().setCollidable(false);
            handle.getBukkitEntity().setAffectsSpawning(true);

            // Exclude dummy from sleep requirement so real players can sleep
            handle.getBukkitEntity().setSleepingIgnored(true);

            // Set tab list display name using Adventure API
            handle.getBukkitEntity().playerListName(net.kyori.adventure.text.Component.text("[AFK] " + ownerName));
            handle.getBukkitEntity().customName(net.kyori.adventure.text.Component.text("[AFK] " + ownerName));
            handle.getBukkitEntity().setCustomNameVisible(true);

            spawned = true;
            plugin.getLogger().info("Spawned AFK dummy for " + ownerName
                    + " at " + formatLocation());
            DebugLogger.log(String.format(
                    "Successfully spawned dummy player via placeNewPlayer for %s at %s. ID: %d, UUID: %s, Session: %s",
                    ownerName, formatLocation(), handle.getId(), handle.getUUID(), sessionId));

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to spawn dummy for " + ownerName, e);
            DebugLogger.log(String.format("ERROR: Failed to spawn dummy for %s. Reason: %s",
                    ownerName, e.toString()));
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            DebugLogger.log(sw.toString());
            throw new IllegalStateException("Dummy spawn failed", e);
        }
    }

    /**
     * Deletes any stale playerdata file for the given UUID to prevent
     * placeNewPlayer from loading old coordinates or state.
     */
    private void deletePlayerData(UUID uuid) {
        try {
            java.io.File worldFolder = ((CraftServer) Bukkit.getServer()).getServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).toFile();
            java.io.File playerFile = new java.io.File(worldFolder, uuid + ".dat");
            if (playerFile.exists()) {
                if (playerFile.delete()) {
                    DebugLogger.log("Deleted stale playerdata for dummy UUID: " + uuid);
                } else {
                    plugin.getLogger().warning("Could not delete stale playerdata for dummy UUID: " + uuid);
                }
            }
            // Also try .dat_old backup
            java.io.File playerFileOld = new java.io.File(worldFolder, uuid + ".dat_old");
            if (playerFileOld.exists()) {
                playerFileOld.delete();
            }
        } catch (Exception e) {
            DebugLogger.log("Warning: Failed to clean playerdata for UUID " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Teleports the dummy to a new location.
     * Updates internal spawnLocation, calls NMS teleportTo, and updates position tracking.
     *
     * @param newLocation the destination Location
     */
    public void teleport(Location newLocation) {
        if (!spawned || handle == null) {
            throw new IllegalStateException("Cannot teleport an unspawned dummy");
        }
        if (newLocation == null || newLocation.getWorld() == null) {
            throw new IllegalArgumentException("Target location and world cannot be null");
        }

        this.spawnLocation = newLocation.clone();
        ServerLevel targetLevel = ((CraftWorld) newLocation.getWorld()).getHandle();

        handle.teleportTo(targetLevel, newLocation.getX(), newLocation.getY(), newLocation.getZ(),
                java.util.Set.of(), newLocation.getYaw(), newLocation.getPitch(), true);

        handle.setPos(newLocation.getX(), newLocation.getY(), newLocation.getZ());
        handle.setRot(newLocation.getYaw(), newLocation.getPitch());
        handle.setOldPosAndRot();

        resendPlayerInfoToAll();
        DebugLogger.log(String.format("Teleported dummy %s (session %s) to %s",
                ownerName, sessionId, formatLocation()));
    }

    /**
     * Re-sends player info and entity spawn packets to all online players to refresh the skin.
     */
    private void resendPlayerInfoToAll() {
        ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
                ),
                java.util.List.of(handle)
        );

        ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(handle.getId());
        ClientboundAddEntityPacket spawnPacket = new ClientboundAddEntityPacket(
                handle.getId(),
                handle.getUUID(),
                handle.getX(),
                handle.getY(),
                handle.getZ(),
                handle.getXRot(),
                handle.getYRot(),
                handle.getType(),
                0,
                Vec3.ZERO,
                handle.getYHeadRot()
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            if (nmsPlayer.connection != null) {
                nmsPlayer.connection.send(infoPacket);
                nmsPlayer.connection.send(removePacket);
                nmsPlayer.connection.send(spawnPacket);
            }
        }
    }

    /**
     * Cleanly removes the dummy from the server.
     */
    public void remove() {
        if (!spawned) return;

        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            server.getPlayerList().remove(handle);
            spawned = false;
            plugin.getLogger().info("Removed AFK dummy for " + ownerName + " (session: " + sessionId + ")");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Error removing dummy for " + ownerName, e);

            try {
                server_fallbackRemove();
            } catch (Exception fallbackEx) {
                plugin.getLogger().log(Level.SEVERE,
                        "Fallback removal also failed for " + ownerName, fallbackEx);
            }
        }
    }

    /**
     * Emergency fallback removal in case PlayerList.remove() throws.
     */
    private void server_fallbackRemove() {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        server.getPlayerList().getPlayers().remove(handle);
        ServerLevel level = (ServerLevel) handle.level();
        level.removePlayerImmediately(handle, Entity.RemovalReason.DISCARDED);
        spawned = false;
        plugin.getLogger().warning("Used fallback removal for dummy " + ownerName);
    }

    /**
     * Sends spawn packets to a specific player (e.g. on join).
     */
    public void sendSpawnPacketsTo(Player player) {
        if (!spawned) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        if (nmsPlayer.connection == null) return;

        ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
                ),
                java.util.List.of(handle)
        );

        ClientboundAddEntityPacket spawnPacket = new ClientboundAddEntityPacket(
                handle.getId(),
                handle.getUUID(),
                handle.getX(),
                handle.getY(),
                handle.getZ(),
                handle.getXRot(),
                handle.getYRot(),
                handle.getType(),
                0,
                Vec3.ZERO,
                handle.getYHeadRot()
        );

        nmsPlayer.connection.send(infoPacket);
        nmsPlayer.connection.send(spawnPacket);

        ClientboundSetEntityDataPacket dataPacket = new ClientboundSetEntityDataPacket(
                handle.getId(),
                handle.getEntityData().getNonDefaultValues()
        );
        if (dataPacket.packedItems() != null && !dataPacket.packedItems().isEmpty()) {
            nmsPlayer.connection.send(dataPacket);
        }
    }

    /**
     * Generates a unique, deterministic UUID for each dummy session.
     */
    private static UUID generateDummyUUID(UUID ownerUUID, UUID sessionId) {
        return UUID.nameUUIDFromBytes(("afkdummy:" + ownerUUID + ":" + sessionId).getBytes());
    }

    /**
     * Generates a valid alphanumeric GameProfile username (<= 16 chars).
     */
    private static String generateProfileName(String ownerName, UUID sessionId) {
        String sanitized = ownerName.replaceAll("[^a-zA-Z0-9_]", "");
        if (sanitized.isEmpty()) sanitized = "Dummy";
        String suffix = sessionId.toString().substring(0, 4);
        String prefix = "AFK_";
        int maxBase = 16 - prefix.length() - 1 - suffix.length();
        String base = sanitized.length() > maxBase ? sanitized.substring(0, maxBase) : sanitized;
        return prefix + base + "_" + suffix;
    }

    /**
     * Formats the dummy's location as a readable string.
     */
    private String formatLocation() {
        return String.format("%s (%.1f, %.1f, %.1f)",
                ((ServerLevel) handle.level()).getWorld().getName(),
                handle.getX(), handle.getY(), handle.getZ());
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    /** @return the unique session ID */
    public UUID getSessionId() {
        return sessionId;
    }

    /** @return the underlying NMS ServerPlayer handle */
    public ServerPlayer getHandle() {
        return handle;
    }

    /** @return the UUID of the player who owns this dummy */
    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    /** @return the display name of the owner */
    public String getOwnerName() {
        return ownerName;
    }

    /** @return the NMS entity ID */
    public int getEntityId() {
        return handle.getId();
    }

    /** @return true if the dummy is currently spawned in the world */
    public boolean isSpawned() {
        return spawned;
    }

    /** @return the dummy's current Location as a Bukkit Location */
    public Location getLocation() {
        return handle.getBukkitEntity().getLocation();
    }

    /** @return the Bukkit Player entity wrapping this dummy */
    public Player getBukkitPlayer() {
        return handle.getBukkitEntity();
    }
}
