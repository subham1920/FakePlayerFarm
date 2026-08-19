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
    private String customName;
    private String skinName;

    /** The spoofed network connection, retained for lifecycle cleanup. */
    private final Connection connection;

    /** The authentication cookie used during placeNewPlayer. */
    private final CommonListenerCookie cookie;

    private boolean spawned = false;

    /**
     * Creates a new DummyPlayer with full customization support.
     */
    public DummyPlayer(UUID ownerUUID, String ownerName, Location location, UUID sessionId,
                       String customName, String skinName, Plugin plugin) {
        this.sessionId = sessionId != null ? sessionId : UUID.randomUUID();
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.spawnLocation = location.clone();
        this.plugin = plugin;
        this.customName = customName;
        this.skinName = skinName;

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

        // Load skin asynchronously (custom skin if specified, else owner's skin)
        if (skinName != null && !skinName.trim().isEmpty()) {
            loadCustomSkin(skinName.trim(), profile);
        } else {
            loadOwnerSkin(profile);
        }
    }

    public DummyPlayer(UUID ownerUUID, String ownerName, Location location, UUID sessionId, Plugin plugin) {
        this(ownerUUID, ownerName, location, sessionId, null, null, plugin);
    }

    public DummyPlayer(UUID ownerUUID, String ownerName, Location location, Plugin plugin) {
        this(ownerUUID, ownerName, location, UUID.randomUUID(), null, null, plugin);
    }

    /**
     * Creates a spoofed {@link Connection} backed by an {@link EmbeddedChannel} with outbound release handler.
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
     * Loads a custom skin by player username.
     */
    private void loadCustomSkin(String username, GameProfile profile) {
        SkinUtil.fetchSkinByNameAsync(username, (Property textures) -> {
            if (textures != null) {
                SkinUtil.applySkin(profile, textures);
                if (spawned) {
                    resendPlayerInfoToAll();
                }
            } else {
                // Fallback to owner skin
                loadOwnerSkin(profile);
            }
        }, plugin);
    }

    /**
     * Loads the owner's skin asynchronously and applies it to the GameProfile.
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
     * Changes the dummy's visual display name dynamically.
     */
    public void setCustomDisplayName(String newName) {
        this.customName = newName;
        if (handle != null && handle.getBukkitEntity() != null) {
            String displayName = (newName != null && !newName.trim().isEmpty()) ? newName.trim() : "[AFK] " + ownerName;
            handle.getBukkitEntity().playerListName(net.kyori.adventure.text.Component.text(displayName));
            handle.getBukkitEntity().customName(net.kyori.adventure.text.Component.text(displayName));
            handle.getBukkitEntity().setCustomNameVisible(true);
            resendPlayerInfoToAll();
        }
    }

    /**
     * Changes the dummy's skin dynamically to any player's skin by username.
     */
    public void setSkinByName(String newSkinUsername) {
        this.skinName = newSkinUsername;
        if (newSkinUsername != null && !newSkinUsername.trim().isEmpty()) {
            loadCustomSkin(newSkinUsername.trim(), handle.getGameProfile());
        } else {
            loadOwnerSkin(handle.getGameProfile());
        }
    }

    public String getCustomName() {
        return customName;
    }

    public String getSkinName() {
        return skinName;
    }

    /**
     * Spawns the dummy player into the world using placeNewPlayer.
     * Writes pre-spawn playerdata to guarantee correct initial placement in the target chunk.
     */
    public void spawn() {
        if (spawned) {
            plugin.getLogger().warning("Attempted to spawn an already-spawned dummy for " + ownerName);
            return;
        }

        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            ServerLevel level = ((CraftWorld) spawnLocation.getWorld()).getHandle();

            // Pre-write playerdata with exact location so placeNewPlayer spawns directly in target chunk
            writeInitialPlayerData(handle.getUUID(), spawnLocation);

            // Inject the player into the server list and world
            server.getPlayerList().placeNewPlayer(connection, handle, cookie);

            // Re-enforce position post-spawn
            handle.setPos(spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ());
            handle.setRot(spawnLocation.getYaw(), spawnLocation.getPitch());
            handle.setOldPosAndRot();

            // Post-spawn configuration
            handle.setGameMode(GameType.SURVIVAL);
            handle.setInvulnerable(true);
            handle.setNoGravity(true);
            handle.setSilent(true);
            handle.getBukkitEntity().setCollidable(false);
            handle.getBukkitEntity().setAffectsSpawning(true);

            // Enable all 7 player skin model parts (Hat, Jacket, Left/Right Sleeves, Left/Right Pants, Cape)
            handle.getEntityData().set(net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 127);

            // Exclude dummy from sleep requirement so real players can sleep
            handle.getBukkitEntity().setSleepingIgnored(true);

            // Set clean tab list display name & nametag using Adventure API
            String displayName = (customName != null && !customName.trim().isEmpty()) ? customName.trim() : "[AFK] " + ownerName;
            handle.getBukkitEntity().playerListName(net.kyori.adventure.text.Component.text(displayName));
            handle.getBukkitEntity().customName(net.kyori.adventure.text.Component.text(displayName));
            handle.getBukkitEntity().setCustomNameVisible(true);

            spawned = true;
            resendPlayerInfoToAll();

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
     * Writes pre-configured playerdata NBT file for the dummy to guarantee
     * placeNewPlayer spawns directly at the target location instead of world spawn.
     */
    private void writeInitialPlayerData(UUID uuid, Location loc) {
        try {
            ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
            java.io.File worldFolder = ((CraftServer) Bukkit.getServer()).getServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).toFile();
            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }
            java.io.File playerFile = new java.io.File(worldFolder, uuid + ".dat");

            net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();

            net.minecraft.nbt.ListTag posList = new net.minecraft.nbt.ListTag();
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(loc.getX()));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(loc.getY()));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(loc.getZ()));
            root.put("Pos", posList);

            net.minecraft.nbt.ListTag rotList = new net.minecraft.nbt.ListTag();
            rotList.add(net.minecraft.nbt.FloatTag.valueOf(loc.getYaw()));
            rotList.add(net.minecraft.nbt.FloatTag.valueOf(loc.getPitch()));
            root.put("Rotation", rotList);

            net.minecraft.nbt.ListTag motionList = new net.minecraft.nbt.ListTag();
            motionList.add(net.minecraft.nbt.DoubleTag.valueOf(0.0));
            motionList.add(net.minecraft.nbt.DoubleTag.valueOf(0.0));
            motionList.add(net.minecraft.nbt.DoubleTag.valueOf(0.0));
            root.put("Motion", motionList);

            root.putString("Dimension", loc.getWorld().getKey().toString());
            root.putFloat("FallDistance", 0.0f);
            root.putShort("Fire", (short) 0);
            root.putShort("Air", (short) 300);
            root.putBoolean("OnGround", true);
            root.putBoolean("Invulnerable", true);
            root.putInt("playerGameType", 0);

            net.minecraft.nbt.CompoundTag bukkitTag = new net.minecraft.nbt.CompoundTag();
            bukkitTag.putString("world", loc.getWorld().getName());
            root.put("bukkit", bukkitTag);

            net.minecraft.nbt.NbtIo.writeCompressed(root, playerFile.toPath());
            DebugLogger.log("Wrote pre-spawn playerdata for " + uuid + " at " + loc);
        } catch (Throwable e) {
            DebugLogger.log("Warning: Failed to write pre-spawn playerdata for " + uuid + ": " + e.getMessage());
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

        var nonDefault = handle.getEntityData().getNonDefaultValues();
        ClientboundSetEntityDataPacket metaPacket = (nonDefault != null && !nonDefault.isEmpty())
                ? new ClientboundSetEntityDataPacket(handle.getId(), nonDefault)
                : null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            if (nmsPlayer.connection != null) {
                nmsPlayer.connection.send(infoPacket);
                nmsPlayer.connection.send(removePacket);
                nmsPlayer.connection.send(spawnPacket);
                if (metaPacket != null) {
                    nmsPlayer.connection.send(metaPacket);
                }
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
