package com.plugin.afkdummy.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageManager Tests")
class StorageManagerTest {

    private Plugin plugin;
    private File dataFolder;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dataFolder = tempDir.toFile();
        plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StorageManagerTest"));
        when(plugin.isEnabled()).thenReturn(false); // So saveAsync writes synchronously in test
    }

    @Nested
    @DisplayName("Load & Save Tests")
    class LoadSaveTests {

        @Test
        @DisplayName("loadSync when file doesn't exist starts empty")
        void testLoadSyncNoFile() {
            StorageManager sm = new StorageManager(plugin);
            sm.loadSync();
            assertEquals(0, sm.size());
            assertTrue(sm.getAllEntries().isEmpty());
        }

        @Test
        @DisplayName("saveSync and loadSync roundtrip")
        void testSaveAndLoadSync() {
            StorageManager sm = new StorageManager(plugin);
            UUID s1 = UUID.randomUUID();
            UUID o1 = UUID.randomUUID();
            DummyData d1 = new DummyData(s1, o1, "Steve", 1, "world", 10, 64, 20, 0, 0, 1000);
            sm.addEntry(d1);

            sm.saveSync();

            // Load into fresh StorageManager
            StorageManager sm2 = new StorageManager(plugin);
            sm2.loadSync();

            assertEquals(1, sm2.size());
            Optional<DummyData> loaded = sm2.getBySession(s1);
            assertTrue(loaded.isPresent());
            assertEquals("Steve", loaded.get().getOwnerName());
        }

        @Test
        @DisplayName("loadSync with corrupt JSON handles gracefully without crash")
        void testLoadSyncCorruptJson() throws Exception {
            File file = new File(dataFolder, "dummies.json");
            Files.writeString(file.toPath(), "{ this is invalid json content !@#$ }");

            StorageManager sm = new StorageManager(plugin);
            assertDoesNotThrow(sm::loadSync);
            assertEquals(0, sm.size());
        }

        @Test
        @DisplayName("saveAsync when plugin disabled saves synchronously")
        void testSaveAsyncDisabled() {
            when(plugin.isEnabled()).thenReturn(false);
            StorageManager sm = new StorageManager(plugin);
            sm.addEntry(new DummyData(UUID.randomUUID(), UUID.randomUUID(), "Alex", 2, "world", 0, 0, 0, 0, 0, 1000));

            File file = new File(dataFolder, "dummies.json");
            assertTrue(file.exists());
        }
    }

    @Nested
    @DisplayName("CRUD Operations")
    class CrudTests {

        @Test
        @DisplayName("addEntry, getBySession, removeEntry by session ID")
        void testAddGetRemove() {
            StorageManager sm = new StorageManager(plugin);
            UUID sessionId = UUID.randomUUID();
            UUID ownerUUID = UUID.randomUUID();

            DummyData data = new DummyData(sessionId, ownerUUID, "Steve", 10, "world", 0, 0, 0, 0, 0, 5000);
            sm.addEntry(data);
            assertEquals(1, sm.size());

            Optional<DummyData> opt = sm.getBySession(sessionId);
            assertTrue(opt.isPresent());
            assertEquals("Steve", opt.get().getOwnerName());

            boolean removed = sm.removeEntry(sessionId);
            assertTrue(removed);
            assertEquals(0, sm.size());
            assertFalse(sm.getBySession(sessionId).isPresent());

            // Remove again returns false
            assertFalse(sm.removeEntry(sessionId));
        }

        @Test
        @DisplayName("removeByOwner removes all dummies belonging to owner")
        void testRemoveByOwner() {
            StorageManager sm = new StorageManager(plugin);
            UUID owner1 = UUID.randomUUID();
            UUID owner2 = UUID.randomUUID();

            sm.addEntry(new DummyData(UUID.randomUUID(), owner1, "Steve", 1, "world", 0, 0, 0, 0, 0, 1000));
            sm.addEntry(new DummyData(UUID.randomUUID(), owner1, "Steve", 2, "world", 1, 1, 1, 0, 0, 1000));
            sm.addEntry(new DummyData(UUID.randomUUID(), owner2, "Alex", 3, "world", 2, 2, 2, 0, 0, 1000));

            assertEquals(3, sm.size());
            assertEquals(2, sm.getEntriesByOwner(owner1).size());
            assertEquals(1, sm.getEntriesByOwner(owner2).size());

            int count = sm.removeByOwner(owner1, false);
            assertEquals(2, count);
            assertEquals(1, sm.size());
            assertEquals(0, sm.getEntriesByOwner(owner1).size());
            assertEquals(1, sm.getEntriesByOwner(owner2).size());
        }

        @Test
        @DisplayName("purgeExpired removes only expired entries")
        void testPurgeExpired() {
            StorageManager sm = new StorageManager(plugin);
            UUID owner = UUID.randomUUID();

            // Expired entry
            sm.addEntry(new DummyData(UUID.randomUUID(), owner, "Expired", 1, "world", 0, 0, 0, 0, 0,
                    System.currentTimeMillis() - 10000L));
            // Active entry
            sm.addEntry(new DummyData(UUID.randomUUID(), owner, "Active", 2, "world", 0, 0, 0, 0, 0,
                    System.currentTimeMillis() + 60000L));

            assertEquals(2, sm.size());
            int purged = sm.purgeExpired();
            assertEquals(1, purged);
            assertEquals(1, sm.size());
            assertEquals("Active", sm.getAllEntries().get(0).getOwnerName());
        }

        @Test
        @DisplayName("clear removes all entries")
        void testClear() {
            StorageManager sm = new StorageManager(plugin);
            for (int i = 0; i < 10; i++) {
                sm.addEntry(new DummyData(UUID.randomUUID(), UUID.randomUUID(), "P" + i, i, "w", 0, 0, 0, 0, 0, 1000));
            }
            assertEquals(10, sm.size());
            sm.clear();
            assertEquals(0, sm.size());
            assertTrue(sm.getAllEntries().isEmpty());
        }

        @Test
        @DisplayName("updateLocation updates coordinates and saves")
        void testUpdateLocation() {
            when(plugin.isEnabled()).thenReturn(false);
            StorageManager sm = new StorageManager(plugin);
            UUID sessionId = UUID.randomUUID();
            UUID ownerUUID = UUID.randomUUID();

            DummyData data = new DummyData(sessionId, ownerUUID, "Steve", 10, "world", 0, 0, 0, 0, 0, 5000);
            sm.addEntry(data);

            org.bukkit.World mockWorld = mock(org.bukkit.World.class);
            when(mockWorld.getName()).thenReturn("world_nether");
            org.bukkit.Location newLoc = new org.bukkit.Location(mockWorld, 150.5, 72.0, -300.25, 180.0f, 45.0f);

            assertTrue(sm.updateLocation(sessionId, newLoc));

            DummyData updated = sm.getBySession(sessionId).orElseThrow();
            assertEquals("world_nether", updated.getWorldName());
            assertEquals(150.5, updated.getX());
            assertEquals(72.0, updated.getY());
            assertEquals(-300.25, updated.getZ());
            assertEquals(180.0f, updated.getYaw());
            assertEquals(45.0f, updated.getPitch());

            // Non-existent session returns false
            assertFalse(sm.updateLocation(UUID.randomUUID(), newLoc));
            // Null location returns false
            assertFalse(sm.updateLocation(sessionId, null));
        }

        @Test
        @DisplayName("updateCustomName and updateSkinName updates fields and returns true")
        void testUpdateCustomNameAndSkin() {
            StorageManager sm = new StorageManager(plugin);
            UUID sessionId = UUID.randomUUID();
            UUID ownerUUID = UUID.randomUUID();

            DummyData data = new DummyData(sessionId, ownerUUID, "Steve", 1, "world", 0, 0, 0, 0, 0, 100000L);
            sm.addEntry(data);

            assertTrue(sm.updateCustomName(sessionId, "SuperGuard"));
            assertEquals("SuperGuard", sm.getBySession(sessionId).get().getCustomName());

            assertTrue(sm.updateSkinName(sessionId, "Dinnerbone"));
            assertEquals("Dinnerbone", sm.getBySession(sessionId).get().getSkinName());

            // Non-existent session
            assertFalse(sm.updateCustomName(UUID.randomUUID(), "Test"));
            assertFalse(sm.updateSkinName(UUID.randomUUID(), "Test"));
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent additions and removals from multiple threads")
        void testConcurrentOperations() throws Exception {
            StorageManager sm = new StorageManager(plugin);
            int threads = 10;
            int opsPerThread = 50;

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            UUID sId = UUID.randomUUID();
                            UUID oId = UUID.randomUUID();
                            DummyData data = new DummyData(sId, oId, "T" + threadId + "_" + i, i, "w", 0, 0, 0, 0, 0, 1000);
                            sm.addEntry(data);
                            if (i % 2 == 0) {
                                sm.removeEntry(sId, false);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertTrue(sm.size() > 0);
            assertDoesNotThrow(sm::getAllEntries);
        }
    }
}
