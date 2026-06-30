package com.plugin.afkdummy.util;

import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Diagnostic logger that writes details directly to plugins/AFKDummy/debug.log.
 * Features thread-safe, lightweight file appending with automated timestamps.
 */
public final class DebugLogger {
    private static File logFile;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private DebugLogger() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Initializes the debug logger. Creates the log file in the plugin data directory.
     *
     * @param plugin the owning plugin instance
     */
    public static void init(Plugin plugin) {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        logFile = new File(plugin.getDataFolder(), "debug.log");
        log("=== Debug Logger Initialized ===");
    }

    /**
     * Logs a message to the debug.log file.
     *
     * @param message the message to log
     */
    public static synchronized void log(String message) {
        if (logFile == null) return;
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            String timestamp = DATE_FORMAT.format(new Date());
            pw.println("[" + timestamp + "] " + message);
        } catch (IOException e) {
            System.err.println("[AFKDummy-Debug-Fail] " + message);
        }
    }
}
