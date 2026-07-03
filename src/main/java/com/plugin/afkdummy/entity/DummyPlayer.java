package com.plugin.afkdummy.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.plugin.afkdummy.util.SkinUtil;
import com.plugin.afkdummy.util.DebugLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
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
 * {@link net.minecraft.server.players.PlayerList#placeNewPlayer}, the same
 * method used when a real player logs in. This ensures the server's
 * simulation engine fully recognizes the dummy for:
 * <ul>
 *   <li>PLAYER chunk ticket creation (keeps chunks entity-ticking)</li>
 *   <li>ChunkMap player tracking (mob cap contribution + spawn radius)</li>
 *   <li>Natural mob spawning loop inclusion</li>
 *   <li>Random block tick processing (crop growth, sugar cane, etc.)</li>
 * </ul>
 * </p>
 *
 * <h3>Key Technical Details:</h3>
 * <ul>
 *   <li>Uses a spoofed {@link Connection} via Netty's {@link EmbeddedChannel}
 *       to satisfy the server's networking requirements without a real socket</li>
 *   <li>Registered via {@link net.minecraft.server.players.PlayerList#placeNewPlayer}
 *       for full chunk ticket and mob cap participation</li>
 *   <li>Set to invulnerable with no physics processing</li>
 * </ul>
 */
public class DummyPlayer {

    private final ServerPlayer handle;
    private final UUID ownerUUID;
    private final String ownerName;
    private final Plugin plugin;

    /** The spoofed network connection, retained for lifecycle cleanup. */
    private final Connection connection;

    /** The authentication cookie used during placeNewPlayer. */
    private final CommonListenerCookie cookie;

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

        // Create ClientInformation with default settings (view distance, language, etc.)
        ClientInformation clientInfo = ClientInformation.createDefault();

        // Create the ServerPlayer entity
        this.handle = new ServerPlayer(server, level, profile, clientInfo);

        // Set position and rotation BEFORE placeNewPlayer so the server
        // creates chunk tickets around the correct location
        handle.setPos(location.getX(), location.getY(), location.getZ());
        handle.setRot(location.getYaw(), location.getPitch());

        // Set up the mock network connection
        // This must happen BEFORE spawn() / placeNewPlayer()
        this.connection = createSpoofedConnection();
        this.cookie = new CommonListenerCookie(profile, 0, clientInfo, false, "vanilla", java.util.Collections.emptySet(), new io.papermc.paper.util.KeepAlive());
        setupMockPacketListener(server);

        // Load the owner's skin asynchronously
        loadOwnerSkin(profile);
    }

    /**
     * Creates a spoofed {@link Connection} backed by a no-op {@link EmbeddedChannel}.
     * <p>
     * Uses {@link PacketFlow#CLIENTBOUND} because from the server's perspective,
     * packets flow toward the "client" (our fake player). The EmbeddedChannel
     * silently consumes all outbound data.
     * </p>
     *
     * @return a fully wired Connection ready for ServerGamePacketListenerImpl
     */
    private Connection createSpoofedConnection() {
        // Create a no-op embedded channel that silently consumes everything
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                // Silently consume all inbound messages
            }
        }) {
            @Override
            public java.net.SocketAddress remoteAddress() {
                return new java.net.InetSocketAddress("127.0.0.1", 0);
            }

            @Override
            public java.net.SocketAddress localAddress() {
                return new java.net.InetSocketAddress("127.0.0.1", 0);
            }
        };

        // Create the Connection with SERVERBOUND flow direction
        // (client → server direction, which is what the server receives)
        Connection conn = new Connection(PacketFlow.SERVERBOUND);

        // Inject the embedded channel into the Connection.
        // Also manually assign the public fields 'channel' and 'address' because 
        // EmbeddedChannel is already active when created, meaning Netty will not 
        // fire channelActive() on handlers added post-construction.
        conn.channel = channel;
        conn.address = channel.remoteAddress();
        channel.pipeline().addLast("packet_handler", conn);

        return conn;
    }

    /**
     * Sets up the mock packet listener that prevents NPEs when the server
     * attempts to send packets to the fake player.
     *
     * @param server the Minecraft server instance
     */
    private void setupMockPacketListener(MinecraftServer server) {
        try {
            // Create and bind the no-op packet listener
            ServerGamePacketListenerImpl listener = new ServerGamePacketListenerImpl(
                    server, connection, handle, cookie) {
                @Override
                public void send(Packet<?> packet) {
                    // No-op: discard all outbound packets
                }

                @Override
                public void disconnect(net.minecraft.network.chat.Component reason) {
                    // No-op: dummy cannot be disconnected via normal means
                }

                @Override
                public boolean isAcceptingMessages() {
                    return true;
                }
            };

            // Bind the listener to the player BEFORE placeNewPlayer
            handle.connection = listener;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to set up mock packet listener for dummy player!", e);
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
     * Spawns the dummy player into the world using the server's full
     * player join lifecycle.
     * <p>
     * Uses {@link net.minecraft.server.players.PlayerList#placeNewPlayer} which
     * handles all necessary registration including:
     * <ul>
     *   <li>Creating PLAYER chunk tickets around the dummy</li>
     *   <li>Registering in ChunkMap for mob spawning radius tracking</li>
     *   <li>Adding to the server's player list</li>
     *   <li>Broadcasting spawn packets to all online players</li>
     *   <li>Entity tracking registration</li>
     * </ul>
     * </p>
     */
    public void spawn() {
        if (spawned) {
            plugin.getLogger().warning("Attempted to spawn an already-spawned dummy for " + ownerName);
            return;
        }

        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();

            // Use placeNewPlayer — the SAME method called when a real player logs in.
            // This is the critical call that registers the player in all server systems:
            // chunk tickets, ChunkMap tracking, entity tracking, player list, etc.
            server.getPlayerList().placeNewPlayer(connection, handle, cookie);

            // Post-spawn configuration
            // These must be set AFTER placeNewPlayer because placeNewPlayer may
            // reset some values during its initialization sequence.

            // Survival mode is critical — the mob spawning algorithm skips spectators
            handle.setGameMode(GameType.SURVIVAL);

            // Make invulnerable and static
            handle.setInvulnerable(true);
            handle.setNoGravity(true);
            handle.setSilent(true);
            handle.getBukkitEntity().setCollidable(false);

            // Force Paper's spawner to recognize the dummy for natural mob spawning
            handle.getBukkitEntity().setAffectsSpawning(true);

            spawned = true;
            plugin.getLogger().info("Spawned AFK dummy for " + ownerName
                    + " at " + formatLocation());
            DebugLogger.log(String.format(
                    "Successfully spawned dummy player via placeNewPlayer for %s at %s. ID: %d, UUID: %s",
                    ownerName, formatLocation(), handle.getId(), handle.getUUID()));

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
     * Cleanly removes the dummy from the server using the server's full
     * player removal lifecycle.
     * <p>
     * Uses {@link net.minecraft.server.players.PlayerList#remove} which handles:
     * <ul>
     *   <li>Chunk ticket removal</li>
     *   <li>Entity untracking from ChunkMap</li>
     *   <li>Removal from internal player list</li>
     *   <li>Broadcasting remove packets to all online players</li>
     * </ul>
     * </p>
     */
    public void remove() {
        if (!spawned) return;

        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();

            // Use the server's own removal method — mirrors what happens when a
            // real player disconnects. This cleanly handles chunk tickets,
            // entity tracking, player list removal, and packet broadcasting.
            server.getPlayerList().remove(handle);

            spawned = false;
            plugin.getLogger().info("Removed AFK dummy for " + ownerName);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Error removing dummy for " + ownerName, e);

            // Fallback: if PlayerList.remove() fails, try manual cleanup
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
     * Manually removes the entity from the world and player list.
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
     * Sends spawn packets to a specific player (e.g., when they join the server).
     * <p>
     * This is a safety net for late-joining players. In most cases,
     * the server's entity tracking system (set up by placeNewPlayer)
     * will handle this automatically, but we send packets explicitly
     * as a fallback.
     * </p>
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
