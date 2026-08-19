package com.plugin.afkdummy.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages persistent storage of dummy player session data using JSON.
 * <p>
 * Data is stored in {@code dummies.json} within the plugin's data folder.
 * Write operations run asynchronously using atomic file writes to prevent data corruption.
 * Thread-safe access is ensured via synchronization.
 * </p>
 */
public class StorageManager {

    private static final Type DATA_LIST_TYPE = new TypeToken<List<DummyData>>() {}.getType();

    private final Plugin plugin;
    private final File dataFile;
    private final File tempFile;
    private final Gson gson;
    private final List<DummyData> dataList;
    private final Object fileLock = new Object();

    /**
     * Constructs a new StorageManager.
     *
     * @param plugin the owning plugin instance
     */
    public StorageManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "dummies.json");
        this.tempFile = new File(plugin.getDataFolder(), "dummies.json.tmp");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.dataList = new ArrayList<>();
    }

    /**
     * Loads all dummy data from the JSON file synchronously.
     * Should only be called during plugin enable on the main thread.
     * Handles missing files, empty files, and corrupt JSON gracefully.
     */
    public void loadSync() {
        if (!dataFile.exists()) {
            plugin.getLogger().info("No existing dummies.json found. Starting fresh.");
            return;
        }

        synchronized (fileLock) {
            try (Reader reader = new InputStreamReader(
                    new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
                List<DummyData> loaded = gson.fromJson(reader, DATA_LIST_TYPE);
                synchronized (dataList) {
                    dataList.clear();
                    if (loaded != null) {
                        dataList.addAll(loaded);
                    }
                }
                plugin.getLogger().info("Loaded " + dataList.size() + " dummy session(s) from storage.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load dummies.json. Data may be corrupt. Starting fresh.", e);
                synchronized (dataList) {
                    dataList.clear();
                }
            }
        }
    }

    /**
     * Saves all dummy data to the JSON file asynchronously.
     * Creates parent directories if they don't exist and writes atomically.
     */
    public void saveAsync() {
        final String jsonContent;
        synchronized (dataList) {
            jsonContent = gson.toJson(new ArrayList<>(dataList), DATA_LIST_TYPE);
        }

        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                writeToFile(jsonContent);
            });
        } else {
            writeToFile(jsonContent);
        }
    }

    /**
     * Saves all dummy data to the JSON file synchronously.
     * Used during plugin disable when the scheduler is no longer available.
     */
    public void saveSync() {
        final String jsonContent;
        synchronized (dataList) {
            jsonContent = gson.toJson(new ArrayList<>(dataList), DATA_LIST_TYPE);
        }
        writeToFile(jsonContent);
    }

    /**
     * Internal atomic method to write JSON content to disk safely.
     */
    private void writeToFile(String jsonContent) {
        synchronized (fileLock) {
            try {
                File parentDir = dataFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                // Write to temp file first, then atomically replace data file
                Files.writeString(tempFile.toPath(), jsonContent, StandardCharsets.UTF_8);
                Files.move(tempFile.toPath(), dataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // If atomic move fails (e.g. filesystem limitations), fallback to direct write
                try {
                    Files.writeString(dataFile.toPath(), jsonContent, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save dummies.json!", ex);
                }
            }
        }
    }

    /**
     * Adds a new dummy session entry and triggers an async save.
     *
     * @param data the dummy data to add
     */
    public void addEntry(DummyData data) {
        synchronized (dataList) {
            dataList.add(data);
        }
        saveAsync();
    }

    /**
     * Removes a dummy session entry by its unique session ID.
     *
     * @param sessionId the unique session ID
     * @param save      whether to trigger an asynchronous save
     * @return true if an entry was removed
     */
    public boolean removeEntry(UUID sessionId, boolean save) {
        boolean removed;
        synchronized (dataList) {
            removed = dataList.removeIf(d -> sessionId.equals(d.getSessionId()));
        }
        if (removed && save) {
            saveAsync();
        }
        return removed;
    }

    /**
     * Removes a dummy session entry by its unique session ID and triggers an async save.
     *
     * @param sessionId the unique session ID
     * @return true if an entry was removed
     */
    public boolean removeEntry(UUID sessionId) {
        return removeEntry(sessionId, true);
    }

    /**
     * Removes all dummy session entries for a given owner UUID.
     *
     * @param ownerUUID the UUID of the owner
     * @param save      whether to trigger an asynchronous save
     * @return count of entries removed
     */
    public int removeByOwner(UUID ownerUUID, boolean save) {
        int removed;
        synchronized (dataList) {
            int before = dataList.size();
            dataList.removeIf(d -> ownerUUID.toString().equals(d.getOwnerUniqueId()));
            removed = before - dataList.size();
        }
        if (removed > 0 && save) {
            saveAsync();
        }
        return removed;
    }

    /**
     * Finds a dummy session entry by session ID.
     *
     * @param sessionId the session ID
     * @return an Optional containing the data if found
     */
    public Optional<DummyData> getBySession(UUID sessionId) {
        synchronized (dataList) {
            return dataList.stream()
                    .filter(d -> sessionId.equals(d.getSessionId()))
                    .findFirst();
        }
    }

    /**
     * Updates the stored location of an existing dummy session and saves asynchronously.
     *
     * @param sessionId   the unique session ID
     * @param newLocation the updated Location
     * @return true if an entry was found and updated
     */
    public boolean updateLocation(UUID sessionId, org.bukkit.Location newLocation) {
        if (newLocation == null || newLocation.getWorld() == null) {
            return false;
        }
        boolean updated = false;
        synchronized (dataList) {
            for (DummyData data : dataList) {
                if (sessionId.equals(data.getSessionId())) {
                    data.setWorldName(newLocation.getWorld().getName());
                    data.setX(newLocation.getX());
                    data.setY(newLocation.getY());
                    data.setZ(newLocation.getZ());
                    data.setYaw(newLocation.getYaw());
                    data.setPitch(newLocation.getPitch());
                    updated = true;
                    break;
                }
            }
        }
        if (updated) {
            saveAsync();
        }
        return updated;
    }

    /**
     * Gets all dummy session entries for a specific owner.
     *
     * @param ownerUUID the owner's UUID
     * @return list of dummy data entries for this owner
     */
    public List<DummyData> getEntriesByOwner(UUID ownerUUID) {
        synchronized (dataList) {
            return dataList.stream()
                    .filter(d -> ownerUUID.toString().equals(d.getOwnerUniqueId()))
                    .toList();
        }
    }

    /**
     * Returns an unmodifiable snapshot of all stored entries.
     *
     * @return list of all dummy data entries
     */
    public List<DummyData> getAllEntries() {
        synchronized (dataList) {
            return Collections.unmodifiableList(new ArrayList<>(dataList));
        }
    }

    /**
     * Removes all expired entries and saves if any were purged.
     *
     * @return the number of expired entries removed
     */
    public int purgeExpired() {
        int removed;
        synchronized (dataList) {
            int before = dataList.size();
            dataList.removeIf(DummyData::isExpired);
            removed = before - dataList.size();
        }
        if (removed > 0) {
            saveAsync();
            plugin.getLogger().info("Purged " + removed + " expired dummy session(s) from storage.");
        }
        return removed;
    }

    /**
     * Clears all stored entries and saves.
     */
    public void clear() {
        synchronized (dataList) {
            dataList.clear();
        }
        saveAsync();
    }

    /**
     * Returns the current number of stored entries.
     *
     * @return entry count
     */
    public int size() {
        synchronized (dataList) {
            return dataList.size();
        }
    }
}
