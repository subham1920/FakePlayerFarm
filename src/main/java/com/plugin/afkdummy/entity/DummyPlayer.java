package com.plugin.afkdummy.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.plugin.afkdummy.util.SkinUtil;
import com.plugin.afkdummy.util.DebugLogger;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
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
 * The dummy player is injected into the server's player list and chunk tracking
 * systems, ensuring it contributes to mob cap calculations and keeps chunks
 * loaded with proper tick processing.
 * </p>
 *
 * <h3>Key Technical Details:</h3>
 * <ul>
 *   <li>Uses a mocked {@link Connection} via Netty's {@link EmbeddedChannel}
 *       to satisfy the server's networking requirements without a real socket</li>
 *   <li>Registered in {@link net.minecraft.server.players.PlayerList} for full
 *       chunk ticket and mob cap participation</li>
 *   <li>Set to invulnerable with no physics processing</li>
 * </ul>
 */
public class DummyPlayer {

    private final ServerPlayer handle;
    private final UUID ownerUUID;
    private final String ownerName;
    private final Plugin plugin;
    private boolean spawned = false;

    /**
     * Creates a new DummyPlayer at the specified location.
     *
     * @param ownerUUID the UUID of the player who owns this dummy
     * @param ownerName the display name of the owner
     * @param location  the spawn location for the dummy
     * @param plugin    the owning plugin instance
     * @throws IllegalStateException if the NMS player cannot be created
     */
    public DummyPlayer(UUID ownerUUID, String ownerName, Location location, Plugin plugin) {
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.plugin = plugin;

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();

        // Create GameProfile with a unique UUID to avoid conflicts
        // Use a deterministic UUID derived from the owner's UUID for consistency across restarts
        UUID dummyUUID = generateDummyUUID(ownerUUID);
        String dummyName = "[AFK] " + truncateName(ownerName, 10);
        GameProfile profile = new GameProfile(dummyUUID, dummyName);

        // Create the ServerPlayer entity
        this.handle = new ServerPlayer(server, level, profile, ClientInformation.createDefault());

        // Set position and rotation
        handle.setPos(location.getX(), location.getY(), location.getZ());
        handle.setRot(location.getYaw(), location.getPitch());

        // Configure dummy attributes
        handle.setInvulnerable(true);
        handle.setNoGravity(true);
        handle.setSilent(true);
        handle.getBukkitEntity().setCollidable(false);

        // Set game mode to survival (important for mob spawning algorithms)
        handle.setGameMode(GameType.SURVIVAL);

        // Set up the mock network connection
        setupMockConnection(server);

        // Load the owner's skin asynchronously
        loadOwnerSkin(profile);
    }

    /**
     * Sets up a mock network connection for the dummy player.
     * This prevents NPEs when the server attempts to send packets to the fake player.
     */
    private void setupMockConnection(MinecraftServer server) {
        try {
            // Create a no-op embedded channel for the Connection
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    // Silently consume all inbound messages
                }
            }) {
                @Override
                public java.net.SocketAddress remoteAddress() {
                    return new java.net.InetSocketAddress("127.0.0.1", 25565);
                }
                @Override
                public java.net.SocketAddress localAddress() {
                    return new java.net.InetSocketAddress("127.0.0.1", 25565);
                }
            };

            // Create the Connection with the embedded channel
            Connection connection = new Connection(PacketFlow.SERVERBOUND);

            // Inject our embedded channel into the Connection
            // The Connection class stores the channel in a field
            try {
                var channelField = Connection.class.getDeclaredField("channel");
                channelField.setAccessible(true);
                channelField.set(connection, channel);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not inject channel into Connection via field. "
                                + "Attempting alternative approach.", e);
            }

            // Create the CommonListenerCookie for authentication
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                    handle.getGameProfile(), false);

            // Create and bind the packet listener
            ServerGamePacketListenerImpl listener = new ServerGamePacketListenerImpl(
                    server, connection, handle, cookie) {
                @Override
                public void send(Packet<?> packet) {
                    // No-op: discard all outbound packets
                }

                @Override
                public void disconnect(net.minecraft.network.chat.Component reason) {
                    // No-op: dummy cannot be disconnected
                }

                @Override
                public boolean isAcceptingMessages() {
                    return true;
                }
            };

            handle.connection = listener;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to set up mock connection for dummy player!", e);
            throw new IllegalStateException("Cannot create dummy player: network setup failed", e);
        }
    }

    /**
     * Loads the owner's skin asynchronously from Mojang's API
     * and applies it to the dummy's GameProfile.
     */
    private void loadOwnerSkin(GameProfile profile) {
        SkinUtil.fetchSkinAsync(ownerUUID, (Property textures) -> {
            if (textures != null) {
                SkinUtil.applySkin(profile, textures);

                // If already spawned, re-send player info to update skin for all viewers
                if (spawned) {
                    resendPlayerInfoToAll();
                }
            }
        }, plugin);
    }

    /**
     * Spawns the dummy player into the world and registers it with
     * all server tracking systems.
     */
    public void spawn() {
        if (spawned) {
            plugin.getLogger().warning("Attempted to spawn an already-spawned dummy for " + ownerName);
            return;
        }

        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();

            // Add to the server's player list - this handles:
            // - Chunk ticket creation (keeps chunks loaded)
            // - ChunkMap player tracking (mob cap contribution)
            // - Entity tracking for visibility
            server.getPlayerList().getPlayers().add(handle);

            // Add entity to the world level
            ServerLevel level = (ServerLevel) handle.level();
            level.addNewPlayer(handle);

            // Force Paper's spawner to recognize the dummy for natural mob spawning
            handle.getBukkitEntity().setAffectsSpawning(true);

            // Send spawn packets to all online players
            sendSpawnPacketsToAll();

            spawned = true;
            plugin.getLogger().info("Spawned AFK dummy for " + ownerName
                    + " at " + formatLocation());
            DebugLogger.log(String.format("Successfully spawned dummy player entity for %s at %s. ID: %d, UUID: %s",
                    ownerName, formatLocation(), handle.getId(), handle.getUUID()));

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to spawn dummy for " + ownerName, e);
            DebugLogger.log(String.format("ERROR: Failed to spawn dummy for %s. Reason: %s", ownerName, e.toString()));
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            DebugLogger.log(sw.toString());
            throw new IllegalStateException("Dummy spawn failed", e);
        }
    }

    /**
     * Sends the necessary spawn packets to all online players so the dummy
     * appears visually in the world.
     */
    private void sendSpawnPacketsToAll() {
        // Player info packet (adds to tab list and provides skin data)
        ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED
                ),
                java.util.List.of(handle)
        );

        // Entity spawn packet
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

        // Entity metadata packet (skin layers, pose, etc.)
        ClientboundSetEntityDataPacket dataPacket = new ClientboundSetEntityDataPacket(
                handle.getId(),
                handle.getEntityData().getNonDefaultValues()
        );

        // Send to all online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            if (nmsPlayer.connection != null) {
                nmsPlayer.connection.send(infoPacket);
                nmsPlayer.connection.send(spawnPacket);
                if (dataPacket.packedItems() != null && !dataPacket.packedItems().isEmpty()) {
                    nmsPlayer.connection.send(dataPacket);
                }
            }
        }
    }

    /**
     * Re-sends player info to all online players (used after skin update).
     */
    private void resendPlayerInfoToAll() {
        ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED
                ),
                java.util.List.of(handle)
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            if (nmsPlayer.connection != null) {
                nmsPlayer.connection.send(infoPacket);
            }
        }
    }

    /**
     * Cleanly removes the dummy from the server, unregistering it from
     * all tracking systems and removing it from the world.
     */
    public void remove() {
        if (!spawned) return;

        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();

            // Send removal packets to all online players
            sendRemovalPacketsToAll();

            // Remove from server player list
            server.getPlayerList().getPlayers().remove(handle);

            // Remove entity from the world
            ServerLevel level = (ServerLevel) handle.level();
            level.removePlayerImmediately(handle, Entity.RemovalReason.DISCARDED);

            spawned = false;
            plugin.getLogger().info("Removed AFK dummy for " + ownerName);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Error removing dummy for " + ownerName, e);
        }
    }

    /**
     * Sends removal packets to all online players.
     */
    private void sendRemovalPacketsToAll() {
        // Remove from tab list
        ClientboundPlayerInfoRemovePacket removeInfoPacket =
                new ClientboundPlayerInfoRemovePacket(java.util.List.of(handle.getUUID()));

        // Remove entity
        ClientboundRemoveEntitiesPacket removeEntityPacket =
                new ClientboundRemoveEntitiesPacket(handle.getId());

        for (Player player : Bukkit.getOnlinePlayers()) {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            if (nmsPlayer.connection != null) {
                nmsPlayer.connection.send(removeInfoPacket);
                nmsPlayer.connection.send(removeEntityPacket);
            }
        }
    }

    /**
     * Sends spawn packets to a specific player (e.g., when they join the server).
     *
     * @param player the player to send packets to
     */
    public void sendSpawnPacketsTo(Player player) {
        if (!spawned) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        if (nmsPlayer.connection == null) return;

        ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED
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
     * Generates a deterministic UUID for the dummy based on the owner's UUID.
     * This ensures the same dummy UUID is used across server restarts.
     *
     * @param ownerUUID the owner's UUID
     * @return a derived UUID for the dummy
     */
    private static UUID generateDummyUUID(UUID ownerUUID) {
        return UUID.nameUUIDFromBytes(("afkdummy:" + ownerUUID).getBytes());
    }

    /**
     * Truncates a player name to fit within Minecraft's 16-character limit
     * when combined with the "[AFK] " prefix.
     */
    private static String truncateName(String name, int maxLength) {
        return name.length() > maxLength ? name.substring(0, maxLength) : name;
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
