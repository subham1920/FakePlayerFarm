package com.plugin.afkdummy.entity;

import com.plugin.afkdummy.util.TimeUtil;
import org.bukkit.Location;

import java.util.UUID;

/**
 * Represents an active dummy player session, wrapping the {@link DummyPlayer}
 * entity with session metadata including expiration time and owner information.
 * <p>
 * Sessions are created when a player purchases an AFK dummy and are tracked
 * by the {@link DummyManager} until they expire or are manually terminated.
 * </p>
 */
public class DummySession {

    private final DummyPlayer dummyPlayer;
    private final UUID ownerUUID;
    private final String ownerName;
    private final long expirationTimestamp;
    private final long creationTimestamp;

    /**
     * Creates a new DummySession.
     *
     * @param dummyPlayer         the wrapped dummy player entity
     * @param ownerUUID           UUID of the player who owns this session
     * @param ownerName           display name of the owner
     * @param expirationTimestamp epoch millis when this session expires
     */
    public DummySession(DummyPlayer dummyPlayer, UUID ownerUUID, String ownerName,
                        long expirationTimestamp) {
        this.dummyPlayer = dummyPlayer;
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.expirationTimestamp = expirationTimestamp;
        this.creationTimestamp = System.currentTimeMillis();
    }

    /**
     * Gets the remaining time for this session in milliseconds.
     *
     * @return remaining time in ms, or 0 if expired
     */
    public long getRemainingTimeMs() {
        return Math.max(0, expirationTimestamp - System.currentTimeMillis());
    }

    /**
     * Checks if this session has expired.
     *
     * @return true if the current time is past the expiration timestamp
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= expirationTimestamp;
    }

    /**
     * Returns the remaining time formatted as HH:mm:ss.
     *
     * @return formatted time remaining string
     */
    public String getFormattedTimeRemaining() {
        return TimeUtil.formatDuration(getRemainingTimeMs());
    }

    /**
     * Returns the remaining time in a human-readable long format.
     *
     * @return e.g., "2 hours, 30 minutes"
     */
    public String getFormattedTimeRemainingLong() {
        return TimeUtil.formatDurationLong(getRemainingTimeMs());
    }

    /**
     * Gets the total duration of this session in milliseconds.
     *
     * @return total session duration
     */
    public long getTotalDurationMs() {
        return expirationTimestamp - creationTimestamp;
    }

    /**
     * Gets the dummy's current location.
     *
     * @return the Location of the dummy player
     */
    public Location getLocation() {
        return dummyPlayer.getLocation();
    }

    /**
     * Safely despawns the dummy player entity.
     */
    public void despawn() {
        dummyPlayer.remove();
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    /** @return the underlying DummyPlayer entity */
    public DummyPlayer getDummyPlayer() {
        return dummyPlayer;
    }

    /** @return UUID of the session owner */
    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    /** @return display name of the session owner */
    public String getOwnerName() {
        return ownerName;
    }

    /** @return epoch millis when this session expires */
    public long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    /** @return epoch millis when this session was created */
    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    /** @return true if the dummy is currently spawned in the world */
    public boolean isSpawned() {
        return dummyPlayer.isSpawned();
    }

    @Override
    public String toString() {
        return "DummySession{owner=" + ownerName
                + ", remaining=" + getFormattedTimeRemaining()
                + ", spawned=" + isSpawned() + "}";
    }
}
