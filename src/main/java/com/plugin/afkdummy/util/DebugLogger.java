package com.plugin.afkdummy.util;

import com.plugin.afkdummy.AFKDummyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Centralized, high-precision structured debug logger for AFKDummy.
 * <p>
 * Writes session diagnostics to plugins/AFKDummy/latest-debug.txt.
 * Resets on every plugin startup and records detailed timelines for:
 * <ul>
 *   <li>Commands & Permissions</li>
 *   <li>GUI Interactions & Chat Prompts</li>
 *   <li>Entity Lifecycle & Spawn Timelines</li>
 *   <li>Teleportation & Multi-tick Position Verification (T0..T+40)</li>
 *   <li>Packet Dispatch & Tracking Synchronization</li>
 *   <li>Storage Operations & Persistence</li>
 * </ul>
 * </p>
 */
public final class DebugLogger {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static File debugFile;
    private static PrintWriter writer;
    private static AFKDummyPlugin plugin;

    private DebugLogger() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Initializes the debug logger session and creates a fresh latest-debug.txt.
     */
    public static synchronized void init(AFKDummyPlugin pl) {
        plugin = pl;
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            debugFile = new File(dataFolder, "latest-debug.txt");
            if (debugFile.exists()) {
                debugFile.delete();
            }

            writer = new PrintWriter(new FileWriter(debugFile, false), true);

            String time = DATE_FORMAT.format(new Date());
            String version = plugin.getDescription().getVersion();
            String serverVersion = Bukkit.getVersion();
            String bukkitVersion = Bukkit.getBukkitVersion();
            String javaVersion = System.getProperty("java.version");
            String os = System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")";

            writer.println("============================================================");
            writer.println("               AFKDummy DEBUG SESSION                       ");
            writer.println("============================================================");
            writer.println("Timestamp:             " + time);
            writer.println("Plugin Version:        " + version);
            writer.println("Server Implementation: " + serverVersion);
            writer.println("Bukkit/Paper API:      " + bukkitVersion);
            writer.println("Java Version:          " + javaVersion);
            writer.println("Operating System:      " + os);
            writer.println("Branch:                debug");
            writer.println("Base Commit:           a85243c");
            writer.println("============================================================");
            writer.println();
            writer.flush();

            plugin.getLogger().info("DebugLogger initialized. Writing to: " + debugFile.getAbsolutePath());
        } catch (Exception e) {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to initialize latest-debug.txt", e);
            }
        }
    }

    /**
     * Generic log message with timestamp.
     */
    public static synchronized void log(String message) {
        writeEntry("LOG", message);
    }

    /**
     * Traces method execution with source file and method origin.
     */
    public static synchronized void trace(String source, String message) {
        writeEntry("TRACE", "[" + source + "] " + message);
    }

    /**
     * Logs command execution with actor, command, result, and validation details.
     */
    public static synchronized void command(String actor, String command, String result, String details) {
        writeEntry("COMMAND", String.format("actor=%s command=\"%s\" result=%s | %s", actor, command, result, details));
    }

    /**
     * Logs GUI interactions, menu opens, clicks, and chat input prompts.
     */
    public static synchronized void gui(String actor, String action, String details) {
        writeEntry("GUI", String.format("actor=%s action=\"%s\" | %s", actor, action, details));
    }

    /**
     * Logs storage operations (read, write, update, delete).
     */
    public static synchronized void storage(String action, String path, String details) {
        writeEntry("STORAGE", String.format("action=%s path=%s | %s", action, path, details));
    }

    /**
     * Logs entity lifecycle events (creation, spawn, rename, skin, despawn, removal).
     */
    public static synchronized void lifecycle(String dummyId, String event, String details) {
        writeEntry("LIFECYCLE", String.format("dummy=%s event=%s | %s", dummyId, event, details));
    }

    /**
     * Logs packet dispatch operations.
     */
    public static synchronized void packet(String packetClass, String recipient, String details) {
        writeEntry("PACKET", String.format("type=%s recipient=%s | %s", packetClass, recipient, details));
    }

    /**
     * Logs state inspection / key-value mappings.
     */
    public static synchronized void state(String dummyId, String key, Object value) {
        writeEntry("STATE", String.format("dummy=%s %s = %s", dummyId, key, value));
    }

    /**
     * Logs scheduler task execution or cancellation.
     */
    public static synchronized void scheduler(String taskName, String details) {
        writeEntry("SCHEDULER", String.format("task=%s | %s", taskName, details));
    }

    /**
     * Schedules a multi-tick verification checkpoint timeline for a teleport operation.
     * Logs state immediately (T0), and at +1, +2, +5, +10, +20, and +40 ticks.
     */
    public static void scheduleTeleportVerification(
            AFKDummyPlugin pl,
            UUID sessionId,
            String ownerName,
            Location requestedDest,
            java.util.function.Supplier<Location> nmsLocationSupplier,
            java.util.function.Supplier<Location> bukkitLocationSupplier,
            java.util.function.Supplier<Location> storedLocationSupplier
    ) {
        if (pl == null || !pl.isEnabled()) return;

        // T0 Immediate Check
        logTeleportSnapshot("T+0 (Immediate)", sessionId, ownerName, requestedDest,
                nmsLocationSupplier.get(), bukkitLocationSupplier.get(), storedLocationSupplier.get());

        int[] checkpoints = {1, 2, 5, 10, 20, 40};
        for (int delay : checkpoints) {
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                logTeleportSnapshot("T+" + delay + " ticks", sessionId, ownerName, requestedDest,
                        nmsLocationSupplier.get(), bukkitLocationSupplier.get(), storedLocationSupplier.get());
            }, delay);
        }
    }

    private static void logTeleportSnapshot(
            String stage,
            UUID sessionId,
            String ownerName,
            Location requested,
            Location nmsLoc,
            Location bukkitLoc,
            Location storedLoc
    ) {
        String reqStr = formatLoc(requested);
        String nmsStr = formatLoc(nmsLoc);
        String bukStr = formatLoc(bukkitLoc);
        String stoStr = formatLoc(storedLoc);

        boolean match = nmsLoc != null && requested != null
                && Math.abs(nmsLoc.getX() - requested.getX()) < 0.1
                && Math.abs(nmsLoc.getY() - requested.getY()) < 0.1
                && Math.abs(nmsLoc.getZ() - requested.getZ()) < 0.1;

        writeEntry("TELEPORT_VERIFY", String.format(
                "[%s] session=%s owner=%s | Req=%s | NMS=%s | Bukkit=%s | Stored=%s | SyncMatch=%b",
                stage, sessionId, ownerName, reqStr, nmsStr, bukStr, stoStr, match
        ));
    }

    private static String formatLoc(Location l) {
        if (l == null || l.getWorld() == null) return "null";
        return String.format("%s(%.2f, %.2f, %.2f, yaw=%.1f, pitch=%.1f)",
                l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    private static synchronized void writeEntry(String category, String message) {
        String timestamp = DATE_FORMAT.format(new Date());
        String line = String.format("[%s] [%-15s] %s", timestamp, category, message);

        if (writer != null) {
            writer.println(line);
            writer.flush();
        }
    }

    /**
     * Closes the debug logger on shutdown.
     */
    public static synchronized void close() {
        if (writer != null) {
            writer.println();
            writer.println("============================================================");
            writer.println("PLUGIN SHUTDOWN — END OF DEBUG SESSION: " + DATE_FORMAT.format(new Date()));
            writer.println("============================================================");
            writer.flush();
            writer.close();
            writer = null;
        }
    }
}
