package com.plugin.afkdummy.entity;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.storage.StorageManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DummyManager Tests")
class DummyManagerTest {

    private AFKDummyPlugin plugin;
    private ConfigManager config;
    private StorageManager storage;
    private DummyManager manager;
    private Map<UUID, DummySession> activeSessions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        plugin = mock(AFKDummyPlugin.class);
        config = mock(ConfigManager.class);
        storage = mock(StorageManager.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DummyManagerTest"));

        when(config.getMaxDummiesPerPlayer()).thenReturn(2);
        when(config.getMaxServerWideDummies()).thenReturn(10);
        when(config.getCleanupIntervalSeconds()).thenReturn(30);

        manager = new DummyManager(plugin, config, storage);

        // Access internal activeSessions map via reflection for state verification/injection
        Field field = DummyManager.class.getDeclaredField("activeSessions");
        field.setAccessible(true);
        activeSessions = (Map<UUID, DummySession>) field.get(manager);
    }

    private DummySession createMockSession(UUID sessionId, UUID ownerUUID, String ownerName, int entityId, Location loc, boolean isSpawned) {
        DummyPlayer dp = mock(DummyPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        when(dp.getEntityId()).thenReturn(entityId);
        when(dp.getLocation()).thenReturn(loc);
        when(dp.isSpawned()).thenReturn(isSpawned);
        when(dp.getBukkitPlayer()).thenReturn(bukkitPlayer);
        when(dp.getSessionId()).thenReturn(sessionId);

        return new DummySession(sessionId, dp, ownerUUID, ownerName, System.currentTimeMillis() + 60000L);
    }

    @Nested
    @DisplayName("Query & Limit Methods")
    class QueryAndLimitTests {

        @Test
        @DisplayName("Initial state is empty")
        void testInitialEmpty() {
            assertEquals(0, manager.getActiveCount());
            assertTrue(manager.getAllSessions().isEmpty());
            assertFalse(manager.hasActiveDummy(UUID.randomUUID()));
        }

        @Test
        @DisplayName("getActiveCountByOwner and hasActiveDummy")
        void testCountByOwner() {
            UUID owner1 = UUID.randomUUID();
            UUID owner2 = UUID.randomUUID();

            activeSessions.put(UUID.randomUUID(), createMockSession(UUID.randomUUID(), owner1, "Steve", 1, null, true));
            activeSessions.put(UUID.randomUUID(), createMockSession(UUID.randomUUID(), owner1, "Steve", 2, null, true));
            activeSessions.put(UUID.randomUUID(), createMockSession(UUID.randomUUID(), owner2, "Alex", 3, null, true));

            assertEquals(3, manager.getActiveCount());
            assertEquals(2, manager.getActiveCountByOwner(owner1));
            assertEquals(1, manager.getActiveCountByOwner(owner2));
            assertTrue(manager.hasActiveDummy(owner1));
            assertTrue(manager.hasActiveDummy(owner2));
            assertFalse(manager.hasActiveDummy(UUID.randomUUID()));
        }

        @Test
        @DisplayName("canSpawnMore checks against per-player config limit")
        void testCanSpawnMore() {
            UUID owner = UUID.randomUUID();
            when(config.getMaxDummiesPerPlayer()).thenReturn(2);

            assertTrue(manager.canSpawnMore(owner));

            activeSessions.put(UUID.randomUUID(), createMockSession(UUID.randomUUID(), owner, "Steve", 1, null, true));
            assertTrue(manager.canSpawnMore(owner));

            activeSessions.put(UUID.randomUUID(), createMockSession(UUID.randomUUID(), owner, "Steve", 2, null, true));
            assertFalse(manager.canSpawnMore(owner));
        }

        @Test
        @DisplayName("getSession and getFirstSessionByOwner")
        void testGetSession() {
            UUID sId = UUID.randomUUID();
            UUID oId = UUID.randomUUID();
            DummySession session = createMockSession(sId, oId, "Steve", 1, null, true);
            activeSessions.put(sId, session);

            Optional<DummySession> opt = manager.getSession(sId);
            assertTrue(opt.isPresent());
            assertEquals(sId, opt.get().getSessionId());

            Optional<DummySession> optOwner = manager.getFirstSessionByOwner(oId);
            assertTrue(optOwner.isPresent());
            assertEquals(sId, optOwner.get().getSessionId());
        }

        @Test
        @DisplayName("isDummyEntity and getSessionByEntityId")
        void testEntityIdQueries() {
            UUID sId = UUID.randomUUID();
            DummySession session = createMockSession(sId, UUID.randomUUID(), "Steve", 123, null, true);
            activeSessions.put(sId, session);

            assertTrue(manager.isDummyEntity(123));
            assertFalse(manager.isDummyEntity(999));

            Optional<DummySession> opt = manager.getSessionByEntityId(123);
            assertTrue(opt.isPresent());
            assertEquals(sId, opt.get().getSessionId());
        }

        @Test
        @DisplayName("isDummyPlayer and getSessionByPlayer")
        void testPlayerQueries() {
            UUID sId = UUID.randomUUID();
            DummySession session = createMockSession(sId, UUID.randomUUID(), "Steve", 123, null, true);
            activeSessions.put(sId, session);

            Player dummyBukkit = session.getDummyPlayer().getBukkitPlayer();
            Player otherPlayer = mock(Player.class);

            assertTrue(manager.isDummyPlayer(dummyBukkit));
            assertFalse(manager.isDummyPlayer(otherPlayer));

            assertTrue(manager.getSessionByPlayer(dummyBukkit).isPresent());
            assertFalse(manager.getSessionByPlayer(otherPlayer).isPresent());
        }
    }

    @Nested
    @DisplayName("Despawn Operations")
    class DespawnTests {

        @Test
        @DisplayName("despawnDummy removes session and storage entry")
        void testDespawnDummy() {
            UUID sId = UUID.randomUUID();
            DummySession session = createMockSession(sId, UUID.randomUUID(), "Steve", 1, null, true);
            activeSessions.put(sId, session);

            boolean result = manager.despawnDummy(sId);
            assertTrue(result);
            assertEquals(0, manager.getActiveCount());
            verify(storage).removeEntry(sId);
            verify(session.getDummyPlayer()).remove();

            // Despawn non-existing returns false
            assertFalse(manager.despawnDummy(sId));
        }

        @Test
        @DisplayName("despawnAllForOwner removes all sessions for that owner")
        void testDespawnAllForOwner() {
            UUID owner = UUID.randomUUID();
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            activeSessions.put(s1, createMockSession(s1, owner, "Steve", 1, null, true));
            activeSessions.put(s2, createMockSession(s2, owner, "Steve", 2, null, true));

            int count = manager.despawnAllForOwner(owner);
            assertEquals(2, count);
            assertEquals(0, manager.getActiveCount());
        }

        @Test
        @DisplayName("despawnAll removes all dummies server-wide")
        void testDespawnAll() {
            for (int i = 0; i < 5; i++) {
                UUID sId = UUID.randomUUID();
                activeSessions.put(sId, createMockSession(sId, UUID.randomUUID(), "P" + i, i, null, true));
            }

            assertEquals(5, manager.getActiveCount());
            manager.despawnAll();
            assertEquals(0, manager.getActiveCount());
        }
    }

    @Nested
    @DisplayName("World Unload & Events")
    class WorldUnloadTests {

        @Test
        @DisplayName("handleWorldUnload despawns dummies in that world")
        void testWorldUnload() {
            World w1 = mock(World.class);
            when(w1.getName()).thenReturn("world_nether");
            World w2 = mock(World.class);
            when(w2.getName()).thenReturn("world");

            Location loc1 = new Location(w1, 0, 0, 0);
            Location loc2 = new Location(w2, 0, 0, 0);

            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            activeSessions.put(s1, createMockSession(s1, UUID.randomUUID(), "Steve", 1, loc1, true));
            activeSessions.put(s2, createMockSession(s2, UUID.randomUUID(), "Alex", 2, loc2, true));

            manager.handleWorldUnload("world_nether");

            assertEquals(1, manager.getActiveCount());
            assertTrue(manager.getSession(s2).isPresent());
            assertFalse(manager.getSession(s1).isPresent());
        }
    }

    @Nested
    @DisplayName("Teleport & Relocate Operations")
    class TeleportOperationsTests {

        @Test
        @DisplayName("teleportDummy moves dummy and updates storage")
        void testTeleportDummy() {
            UUID s1 = UUID.randomUUID();
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            Location oldLoc = new Location(world, 10, 64, 10);
            Location newLoc = new Location(world, 100, 70, 200);

            DummySession session = createMockSession(s1, UUID.randomUUID(), "Steve", 1, oldLoc, true);
            activeSessions.put(s1, session);

            assertTrue(manager.teleportDummy(s1, newLoc));
            verify(session.getDummyPlayer(), times(1)).teleport(newLoc);
            verify(storage, times(1)).updateLocation(s1, newLoc);
        }

        @Test
        @DisplayName("teleportDummy returns false for non-existent session or null location")
        void testTeleportDummyInvalid() {
            assertFalse(manager.teleportDummy(UUID.randomUUID(), null));
            assertFalse(manager.teleportDummy(UUID.randomUUID(), new Location(mock(World.class), 0, 0, 0)));
        }

        @Test
        @DisplayName("teleportNearestForOwner moves owner's nearest dummy to player location")
        void testTeleportNearestForOwner() {
            UUID owner = UUID.randomUUID();
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");

            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(owner);
            Location playerLoc = new Location(world, 50, 65, 50);
            when(player.getLocation()).thenReturn(playerLoc);

            UUID s1 = UUID.randomUUID();
            Location loc1 = new Location(world, 10, 64, 10);
            DummySession session = createMockSession(s1, owner, "Steve", 1, loc1, true);
            activeSessions.put(s1, session);

            assertTrue(manager.teleportNearestForOwner(player));
            verify(session.getDummyPlayer(), times(1)).teleport(playerLoc);
            verify(storage, times(1)).updateLocation(s1, playerLoc);
        }
    }
}
