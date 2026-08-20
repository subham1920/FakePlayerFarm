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
        // Create GameProfile with unique UUID and exact username (no extra trailing characters!)
        UUID dummyUUID = generateDummyUUID(ownerUUID, this.sessionId);
        String dummyProfileName = generateProfileName(customName != null ? customName : ownerName);
        GameProfile profile = new GameProfile(dummyUUID, dummyProfileName);

        // Create ClientInformation with default settings
        ClientInformation clientInfo = ClientInformation.createDefault();

        // Create the ServerPlayer entity
        this.handle = new ServerPlayer(server, level, profile, clientInfo);

        // Pre-set position and rotation (including head and body yaw)
        handle.setPos(location.getX(), location.getY(), location.getZ());
        handle.setRot(location.getYaw(), location.getPitch());
        handle.setYHeadRot(location.getYaw());
        handle.setYBodyRot(location.getYaw());
        handle.setOldPosAndRot();

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
        String oldName = this.customName;
        this.customName = (newName != null && !newName.trim().isEmpty()) ? sanitizeRawName(newName) : null;

        if (handle != null && handle.getBukkitEntity() != null) {
            String formatted = getFormattedDisplayName();

            // 1. Update Adventure Player List Name (Tab List) & Bukkit Custom Name
            handle.getBukkitEntity().playerListName(net.kyori.adventure.text.Component.text(formatted));
            handle.getBukkitEntity().customName(net.kyori.adventure.text.Component.text(formatted));
            handle.getBukkitEntity().setCustomNameVisible(true);

            // 2. Update GameProfile Name on NMS ServerPlayer so 3D nametag renders correctly (clean name, zero trailing chars!)
            String newProfileName = generateProfileName(getRawName());
            updateGameProfileName(newProfileName);

            // 3. Update Scoreboard Team for in-world styling
            updateScoreboardTeam();

            // 4. Send client refresh packets
            resendPlayerInfoToAll();

            DebugLogger.trace("DummyPlayer.java:setCustomDisplayName",
                    String.format("Updated dummy name for %s: old=\"%s\" -> new=\"%s\" (profile=\"%s\", formatted=\"%s\")",
                            ownerName, oldName, this.customName, newProfileName, formatted));
        }
    }

    /**
     * Reflectively updates the GameProfile name on the NMS ServerPlayer entity.
     */
    private void updateGameProfileName(String newProfileName) {
        try {
            GameProfile current = handle.getGameProfile();
            GameProfile updated = new GameProfile(current.id(), newProfileName);
            for (var entry : current.properties().entries()) {
                updated.properties().put(entry.getKey(), entry.getValue());
            }

            java.lang.reflect.Field gameProfileField = null;
            Class<?> clazz = net.minecraft.world.entity.player.Player.class;
            while (clazz != null && clazz != Object.class) {
                try {
                    gameProfileField = clazz.getDeclaredField("gameProfile");
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (gameProfileField != null) {
                gameProfileField.setAccessible(true);
                gameProfileField.set(handle, updated);
            }
        } catch (Throwable t) {
            DebugLogger.log("Warning: Failed to update GameProfile name: " + t.getMessage());
        }
    }

    /**
     * Changes the dummy's skin dynamically to any player's skin by username.
     */
    public void setSkinByName(String newSkinUsername) {
        this.skinName = (newSkinUsername != null && !newSkinUsername.trim().isEmpty()) ? newSkinUsername.trim() : null;
        if (this.skinName != null) {
            loadCustomSkin(this.skinName, handle.getGameProfile());
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

            DebugLogger.lifecycle(sessionId.toString(), "SPAWN_START",
                    String.format("Spawning dummy for %s at %s", ownerName, formatLocation()));

            // Pre-write playerdata with exact location so placeNewPlayer spawns directly in target chunk
            writeInitialPlayerData(handle.getUUID(), spawnLocation);

            // Pre-set NMS position
            handle.setPos(spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ());
            handle.setRot(spawnLocation.getYaw(), spawnLocation.getPitch());
            handle.setOldPosAndRot();

            // Inject the player into the server list and world
            server.getPlayerList().placeNewPlayer(connection, handle, cookie);

            // Reset connection teleport state to prevent pending unconfirmed teleport
            if (handle.connection != null) {
                handle.connection.resetPosition();
            }

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
            String displayName = getFormattedDisplayName();
            handle.getBukkitEntity().playerListName(net.kyori.adventure.text.Component.text(displayName));
            handle.getBukkitEntity().customName(net.kyori.adventure.text.Component.text(displayName));
            handle.getBukkitEntity().setCustomNameVisible(true);

            // Register Scoreboard Team for in-world nametag display ([AFK] prefix)
            updateScoreboardTeam();

            // Update ChunkMap tracking to target location
            try {
                level.getChunkSource().chunkMap.move(handle);
            } catch (Throwable ignored) {}

            spawned = true;
            resendPlayerInfoToAll();

            plugin.getLogger().info("Spawned AFK dummy for " + ownerName
                    + " at " + formatLocation());
            DebugLogger.lifecycle(sessionId.toString(), "SPAWN_SUCCESS",
                    String.format("Successfully spawned dummy for %s at %s. EntityID: %d, UUID: %s",
                            ownerName, formatLocation(), handle.getId(), handle.getUUID()));

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn dummy for " + ownerName, e);
            DebugLogger.lifecycle(sessionId.toString(), "SPAWN_FAIL", "Reason: " + e.getMessage());
            throw new IllegalStateException("Dummy spawn failed", e);
        }
    }

    /**
     * Writes pre-configured playerdata NBT file for the dummy to guarantee
     * placeNewPlayer spawns directly at the target location instead of world spawn.
     */
    private void writeInitialPlayerData(UUID uuid, Location loc) {
        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            java.io.File worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).toFile();
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
            root.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());

            net.minecraft.nbt.CompoundTag bukkitTag = new net.minecraft.nbt.CompoundTag();
            UUID worldUUID = loc.getWorld().getUID();
            bukkitTag.putLong("worldLow", worldUUID.getLeastSignificantBits());
            bukkitTag.putLong("worldHigh", worldUUID.getMostSignificantBits());
            bukkitTag.putString("world", loc.getWorld().getName());
            root.put("bukkit", bukkitTag);

            net.minecraft.nbt.NbtIo.writeCompressed(root, playerFile.toPath());
            DebugLogger.storage("WRITE", playerFile.getAbsolutePath(),
                    String.format("Wrote pre-spawn playerdata for %s at %s", uuid, loc));
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
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            java.io.File worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).toFile();
            java.io.File playerFile = new java.io.File(worldFolder, uuid + ".dat");
            if (playerFile.exists()) {
                if (playerFile.delete()) {
                    DebugLogger.storage("DELETE", playerFile.getAbsolutePath(), "Deleted temporary playerdata for " + uuid);
                }
            }
            java.io.File playerFileOld = new java.io.File(worldFolder, uuid + ".dat_old");
            if (playerFileOld.exists()) {
                playerFileOld.delete();
            }
        } catch (Exception e) {
            DebugLogger.log("Warning: Failed to clean playerdata for UUID " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Gets a collision-proof Scoreboard Team name dedicated to this dummy session.
     */
    private String getTeamName() {
        return "afk_" + sessionId.toString().replace("-", "").substring(0, 12);
    }

    /**
     * Updates or registers the Scoreboard Team to format the in-world nametag cleanly with [AFK] prefix.
     */
    private void updateScoreboardTeam() {
        try {
            String teamName = getTeamName();
            String currentScoreboardName = handle.getScoreboardName();

            // 1. Update MainScoreboard
            org.bukkit.scoreboard.Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            org.bukkit.scoreboard.Team team = scoreboard.getTeam(teamName);
            if (team == null) {
                team = scoreboard.registerNewTeam(teamName);
            }
            for (String entry : new java.util.HashSet<>(team.getEntries())) {
                if (!entry.equals(currentScoreboardName)) {
                    team.removeEntry(entry);
                }
            }
            if (!team.hasEntry(currentScoreboardName)) {
                team.addEntry(currentScoreboardName);
            }
            team.prefix(net.kyori.adventure.text.Component.text("[AFK] ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
            team.suffix(net.kyori.adventure.text.Component.empty());
            team.color(net.kyori.adventure.text.format.NamedTextColor.WHITE);

            // 2. Update every online player's individual active scoreboard (for servers with scoreboard plugins)
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    org.bukkit.scoreboard.Scoreboard pScoreboard = player.getScoreboard();
                    if (pScoreboard != null && pScoreboard != scoreboard) {
                        org.bukkit.scoreboard.Team pTeam = pScoreboard.getTeam(teamName);
                        if (pTeam == null) {
                            pTeam = pScoreboard.registerNewTeam(teamName);
                        }
                        for (String entry : new java.util.HashSet<>(pTeam.getEntries())) {
                            if (!entry.equals(currentScoreboardName)) {
                                pTeam.removeEntry(entry);
                            }
                        }
                        if (!pTeam.hasEntry(currentScoreboardName)) {
                            pTeam.addEntry(currentScoreboardName);
                        }
                        pTeam.prefix(net.kyori.adventure.text.Component.text("[AFK] ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
                        pTeam.suffix(net.kyori.adventure.text.Component.empty());
                        pTeam.color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
                    }
                } catch (Throwable ignored) {}
            }

            // 3. Update NMS Server Scoreboard and broadcast ClientboundSetPlayerTeamPacket directly
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            net.minecraft.world.scores.Scoreboard nmsScoreboard = server.getScoreboard();
            net.minecraft.world.scores.PlayerTeam nmsTeam = nmsScoreboard.getPlayerTeam(teamName);
            if (nmsTeam == null) {
                nmsTeam = nmsScoreboard.addPlayerTeam(teamName);
            }
            nmsTeam.setPlayerPrefix(net.minecraft.network.chat.Component.literal("[AFK] ").withStyle(net.minecraft.ChatFormatting.GRAY));
            if (!nmsTeam.getPlayers().contains(currentScoreboardName)) {
                nmsScoreboard.addPlayerToTeam(currentScoreboardName, nmsTeam);
            }

            ClientboundSetPlayerTeamPacket addTeamPacket = ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(nmsTeam, true);
            ClientboundSetPlayerTeamPacket addPlayerPacket = ClientboundSetPlayerTeamPacket.createPlayerPacket(nmsTeam, currentScoreboardName, ClientboundSetPlayerTeamPacket.Action.ADD);

            for (Player player : Bukkit.getOnlinePlayers()) {
                ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
                if (nmsPlayer.connection != null) {
                    nmsPlayer.connection.send(addTeamPacket);
                    nmsPlayer.connection.send(addPlayerPacket);
                }
            }

        } catch (Throwable e) {
            DebugLogger.log("Warning: Failed to update scoreboard team for dummy: " + e.getMessage());
        }
    }

    /**
     * Removes the dummy's Scoreboard Team upon removal.
     */
    private void removeScoreboardTeam() {
        try {
            org.bukkit.scoreboard.Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = getTeamName();
            org.bukkit.scoreboard.Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.unregister();
            }
        } catch (Throwable e) {
            DebugLogger.log("Warning: Failed to remove scoreboard team for dummy: " + e.getMessage());
        }
    }

    /**
     * Teleports the dummy to a new location authoritatively.
     * Updates internal spawnLocation, calls NMS moveTo, resets connection state, and moves ChunkMap tracking.
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
        ServerLevel currentLevel = (ServerLevel) handle.level();

        DebugLogger.trace("DummyPlayer.java:teleport",
                String.format("Teleporting dummy %s from %s to %s",
                        ownerName, formatLocation(), formatLoc(newLocation)));

        if (!targetLevel.equals(currentLevel)) {
            // Cross-world teleport
            handle.teleportTo(targetLevel, newLocation.getX(), newLocation.getY(), newLocation.getZ(),
                    java.util.Set.of(), newLocation.getYaw(), newLocation.getPitch(), true);
            handle.setYHeadRot(newLocation.getYaw());
            handle.setYBodyRot(newLocation.getYaw());
        } else {
            // Same world authoritative moveTo
            handle.setPos(newLocation.getX(), newLocation.getY(), newLocation.getZ());
            handle.setRot(newLocation.getYaw(), newLocation.getPitch());
            handle.setYHeadRot(newLocation.getYaw());
            handle.setYBodyRot(newLocation.getYaw());
            handle.setOldPosAndRot();
        }

        // Reset connection awaitingTeleport to prevent unacknowledged packet snap-backs
        if (handle.connection != null) {
            handle.connection.resetPosition();
        }

        // Update ChunkMap tracking to the new chunk
        try {
            targetLevel.getChunkSource().chunkMap.move(handle);
        } catch (Throwable ignored) {}

        // Broadcast authoritative TeleportEntity packet to all tracking players in the destination world
        ClientboundTeleportEntityPacket tpPacket = ClientboundTeleportEntityPacket.teleport(
                handle.getId(),
                net.minecraft.world.entity.PositionMoveRotation.of(handle),
                java.util.Set.of(),
                handle.onGround()
        );

        byte headYawByte = (byte) ((newLocation.getYaw() * 256.0F) / 360.0F);
        byte pitchByte = (byte) ((newLocation.getPitch() * 256.0F) / 360.0F);
        ClientboundRotateHeadPacket headPacket = new ClientboundRotateHeadPacket(handle, headYawByte);
        ClientboundMoveEntityPacket.Rot rotPacket = new ClientboundMoveEntityPacket.Rot(handle.getId(), headYawByte, pitchByte, handle.onGround());

        for (Player p : newLocation.getWorld().getPlayers()) {
            ServerPlayer nmsP = ((CraftPlayer) p).getHandle();
            if (nmsP.connection != null && !nmsP.getUUID().equals(handle.getUUID())) {
                nmsP.connection.send(tpPacket);
                nmsP.connection.send(headPacket);
                nmsP.connection.send(rotPacket);
            }
        }

        DebugLogger.trace("DummyPlayer.java:teleport",
                String.format("Teleport complete for %s (session %s) at %s",
                        ownerName, sessionId, formatLocation()));
    }

    private static String formatLoc(Location l) {
        if (l == null || l.getWorld() == null) return "null";
        return String.format("%s(%.1f, %.1f, %.1f)", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    /**
     * Re-sends player info and entity spawn packets to all online players to refresh the skin and model.
     */
    private void resendPlayerInfoToAll() {
        if (!spawned || handle == null) return;

        // 1. Remove entity from client viewport
        ClientboundRemoveEntitiesPacket removeEntitiesPacket = new ClientboundRemoveEntitiesPacket(handle.getId());

        // 2. Remove profile from client player-info cache (forces client to reload skin upon ADD_PLAYER)
        ClientboundPlayerInfoRemovePacket removeInfoPacket = new ClientboundPlayerInfoRemovePacket(java.util.List.of(handle.getUUID()));

        // 3. Add updated profile and skin properties to client player-info map
        ClientboundPlayerInfoUpdatePacket addInfoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY
                ),
                java.util.List.of(handle)
        );

        // 4. Re-spawn entity at updated coordinates
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

        // 5. Ensure 3D outer layers (Hat, Jacket, Sleeves, Pants, Cape) are packed
        handle.getEntityData().set(net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 127);
        var nonDefault = handle.getEntityData().getNonDefaultValues();
        ClientboundSetEntityDataPacket metaPacket = (nonDefault != null && !nonDefault.isEmpty())
                ? new ClientboundSetEntityDataPacket(handle.getId(), nonDefault)
                : null;

        // Broadcast to all online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            if (nmsPlayer.connection != null) {
                nmsPlayer.connection.send(removeEntitiesPacket);
                nmsPlayer.connection.send(removeInfoPacket);
                nmsPlayer.connection.send(addInfoPacket);
                nmsPlayer.connection.send(spawnPacket);
                if (metaPacket != null) {
                    nmsPlayer.connection.send(metaPacket);
                }
            }
        }

        // 6. Update scoreboard team display
        updateScoreboardTeam();
    }

    /**
     * Cleanly removes the dummy from the server.
     */
    public void remove() {
        if (!spawned) return;

        try {
            // Clean up scoreboard team
            removeScoreboardTeam();

            // Clean up temporary playerdata
            deletePlayerData(handle.getUUID());

            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            server.getPlayerList().remove(handle);
            spawned = false;
            plugin.getLogger().info("Removed AFK dummy for " + ownerName + " (session: " + sessionId + ")");
            DebugLogger.lifecycle(sessionId.toString(), "REMOVE", "Removed dummy for " + ownerName);
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
        removeScoreboardTeam();
        deletePlayerData(handle.getUUID());

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
        if (!spawned || handle == null || player == null || !player.isOnline()) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        if (nmsPlayer.connection == null) return;

        // Send Scoreboard Team packets first so the client associates the profile name with [AFK] prefix
        try {
            String teamName = getTeamName();
            String currentScoreboardName = handle.getScoreboardName();
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            net.minecraft.world.scores.Scoreboard nmsScoreboard = server.getScoreboard();
            net.minecraft.world.scores.PlayerTeam nmsTeam = nmsScoreboard.getPlayerTeam(teamName);
            if (nmsTeam != null) {
                nmsPlayer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(nmsTeam, true));
                nmsPlayer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(nmsTeam, currentScoreboardName, ClientboundSetPlayerTeamPacket.Action.ADD));
            }
        } catch (Throwable ignored) {}

        ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY
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

        var nonDefault = handle.getEntityData().getNonDefaultValues();
        if (nonDefault != null && !nonDefault.isEmpty()) {
            nmsPlayer.connection.send(new ClientboundSetEntityDataPacket(handle.getId(), nonDefault));
        }
    }

    /**
     * Generates a unique, deterministic UUID for each dummy session.
     */
    private static UUID generateDummyUUID(UUID ownerUUID, UUID sessionId) {
        return UUID.nameUUIDFromBytes(("afkdummy:" + ownerUUID + ":" + sessionId).getBytes());
    }

    /**
     * Cleans and sanitizes a raw dummy name by stripping any existing AFK prefix.
     */
    public static String sanitizeRawName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Dummy";
        }
        String trimmed = name.trim();
        while (trimmed.toUpperCase().startsWith("[AFK]") || trimmed.toUpperCase().startsWith("AFK_") || trimmed.toUpperCase().startsWith("AFK ")) {
            if (trimmed.toUpperCase().startsWith("[AFK]")) {
                trimmed = trimmed.substring(5).trim();
            } else if (trimmed.toUpperCase().startsWith("AFK_")) {
                trimmed = trimmed.substring(4).trim();
            } else if (trimmed.toUpperCase().startsWith("AFK ")) {
                trimmed = trimmed.substring(4).trim();
            }
        }
        if (trimmed.isEmpty()) {
            trimmed = "Dummy";
        }
        return trimmed;
    }

    /**
     * Formats the authoritative display name for TAB and nametag display: "[AFK] <cleanName>".
     */
    public static String formatDisplayName(String rawName) {
        String clean = sanitizeRawName(rawName);
        return "[AFK] " + clean;
    }

    /**
     * Gets this dummy's clean raw name (e.g. "Afkin" or "Steve" or ownerName).
     */
    public String getRawName() {
        return customName != null ? customName : ownerName;
    }

    /**
     * Gets this dummy's formatted display name (e.g. "[AFK] Afkin" or "[AFK] JustRyt").
     */
    public String getFormattedDisplayName() {
        return formatDisplayName(getRawName());
    }

    /**
     * Generates a valid alphanumeric GameProfile username (<= 16 chars) with zero trailing suffixes or extra characters.
     */
    public static String generateProfileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Dummy";
        }
        String clean = sanitizeRawName(name);
        String sanitized = clean.replaceAll("[^a-zA-Z0-9_]", "");
        if (sanitized.isEmpty()) {
            sanitized = "Dummy";
        }
        if (sanitized.length() > 16) {
            sanitized = sanitized.substring(0, 16);
        }
        return sanitized;
    }

    /**
     * Overload for backward compatibility.
     */
    public static String generateProfileName(String name, UUID sessionId) {
        return generateProfileName(name);
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
