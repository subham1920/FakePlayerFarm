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
import java.util.*;
import java.util.logging.Level;

/**
 * Manages persistent storage of dummy player session data using JSON.
 * <p>
 * Data is stored in {@code dummies.json} within the plugin's data folder.
 * Write operations run asynchronously to prevent main thread lag.
 * Thread-safe access is ensured via synchronized blocks on the internal data list.
 * </p>
 */
public class StorageManager {

    private static final Type DATA_LIST_TYPE = new TypeToken<List<DummyData>>() {}.getType();

    private final Plugin plugin;
    private final File dataFile;
    private final Gson gson;
    private final List<DummyData> dataList;

    /**
     * Constructs a new StorageManager.
     *
     * @param plugin the owning plugin instance
     */
    public StorageManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "dummies.json");
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

    /**
     * Saves all dummy data to the JSON file asynchronously.
     * Creates parent directories if they don't exist.
     */
    public void saveAsync() {
        // Create a snapshot of the data under the lock
        final String jsonContent;
        synchronized (dataList) {
            jsonContent = gson.toJson(new ArrayList<>(dataList), DATA_LIST_TYPE);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            writeToFile(jsonContent);
        });
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
     * Internal method to write JSON content to the data file.
     */
    private void writeToFile(String jsonContent) {
        try {
            File parentDir = dataFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            Files.writeString(dataFile.toPath(), jsonContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to save dummies.json!", e);
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
     * Removes the dummy session entry for a given owner.
     *
     * @param ownerUUID the UUID of the dummy's owner
     * @param save      whether to trigger an asynchronous save
     * @return true if an entry was removed
     */
    public boolean removeEntry(UUID ownerUUID, boolean save) {
        boolean removed;
        synchronized (dataList) {
            removed = dataList.removeIf(d ->
                    ownerUUID.toString().equals(d.getOwnerUniqueId()));
        }
        if (removed && save) {
            saveAsync();
        }
        return removed;
    }

    /**
     * Removes the dummy session entry for a given owner and triggers an async save.
     *
     * @param ownerUUID the UUID of the dummy's owner
     * @return true if an entry was removed
     */
    public boolean removeEntry(UUID ownerUUID) {
        return removeEntry(ownerUUID, true);
    }

    /**
     * Finds a dummy session entry by owner UUID.
     *
     * @param ownerUUID the owner's UUID
     * @return an Optional containing the data if found
     */
    public Optional<DummyData> getByOwner(UUID ownerUUID) {
        synchronized (dataList) {
            return dataList.stream()
                    .filter(d -> ownerUUID.toString().equals(d.getOwnerUniqueId()))
                    .findFirst();
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
