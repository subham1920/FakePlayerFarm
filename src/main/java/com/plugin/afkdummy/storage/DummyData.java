package com.plugin.afkdummy.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Data class representing a persisted dummy player session.
 * Serialized to/from JSON via GSON.
 * <p>
 * Uniquely identified by a {@code sessionId} to support multiple dummies per owner.
 * </p>
 */
public class DummyData {

    private String sessionId;
    private String ownerUniqueId;
    private String ownerName;
    private int dummyEntityId;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private long expirationTimestamp;
    private String customName;
    private String skinName;

    /** Default constructor required by GSON deserialization. */
    public DummyData() {}

    /**
     * Constructs a complete DummyData entry with a unique session ID.
     */
    public DummyData(UUID sessionId, UUID ownerUniqueId, String ownerName, int dummyEntityId,
                     String worldName, double x, double y, double z,
                     float yaw, float pitch, long expirationTimestamp) {
        this(sessionId, ownerUniqueId, ownerName, dummyEntityId, worldName, x, y, z, yaw, pitch, expirationTimestamp, null, null);
    }

    /**
     * Constructs a complete DummyData entry with custom name and skin.
     */
    public DummyData(UUID sessionId, UUID ownerUniqueId, String ownerName, int dummyEntityId,
                     String worldName, double x, double y, double z,
                     float yaw, float pitch, long expirationTimestamp,
                     String customName, String skinName) {
        this.sessionId = (sessionId != null ? sessionId : UUID.randomUUID()).toString();
        this.ownerUniqueId = ownerUniqueId.toString();
        this.ownerName = ownerName;
        this.dummyEntityId = dummyEntityId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.expirationTimestamp = expirationTimestamp;
        this.customName = customName;
        this.skinName = skinName;
    }

    /** @return the unique session ID */
    public UUID getSessionId() {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.nameUUIDFromBytes(("session:" + ownerUniqueId + ":" + expirationTimestamp).getBytes()).toString();
        }
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            sessionId = UUID.randomUUID().toString();
            return UUID.fromString(sessionId);
        }
    }

    /** @return raw session ID string */
    public String getRawSessionId() {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = getSessionId().toString();
        }
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /** @return the owner's UUID parsed from the stored string */
    public UUID getOwnerUUID() {
        return UUID.fromString(ownerUniqueId);
    }

    /** @return the raw owner UUID string */
    public String getOwnerUniqueId() {
        return ownerUniqueId;
    }

    public void setOwnerUniqueId(String ownerUniqueId) {
        this.ownerUniqueId = ownerUniqueId;
    }

    /** @return the owner's display name */
    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    /** @return the NMS entity ID of the dummy */
    public int getDummyEntityId() {
        return dummyEntityId;
    }

    public void setDummyEntityId(int dummyEntityId) {
        this.dummyEntityId = dummyEntityId;
    }

    /** @return the world name where the dummy is located */
    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }

    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; }

    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; }

    /** @return epoch millis when this session expires */
    public long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public void setExpirationTimestamp(long expirationTimestamp) {
        this.expirationTimestamp = expirationTimestamp;
    }

    public String getCustomName() {
        return customName;
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }

    public String getSkinName() {
        return skinName;
    }

    public void setSkinName(String skinName) {
        this.skinName = skinName;
    }

    /**
     * Calculates the remaining time in milliseconds for this session.
     *
     * @return remaining time in ms, or 0 if expired
     */
    public long getRemainingTimeMs() {
        return Math.max(0, expirationTimestamp - System.currentTimeMillis());
    }

    /**
     * Checks if this dummy session has expired.
     *
     * @return true if current time is past the expiration timestamp
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= expirationTimestamp;
    }

    /**
     * Converts the stored coordinates to a Bukkit Location.
     * Returns null if the world is not loaded or does not exist.
     *
     * @return a Location object, or null if the world is unavailable
     */
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    @Override
    public String toString() {
        return "DummyData{session=" + sessionId + ", owner=" + ownerName + ", world=" + worldName
                + ", pos=(" + String.format("%.1f, %.1f, %.1f", x, y, z)
                + "), expires=" + expirationTimestamp + "}";
    }
}
