package com.plugin.afkdummy.storage;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DummyData Tests")
class DummyDataTest {

    private final Gson gson = new Gson();

    @Test
    @DisplayName("Default constructor creates empty instance")
    void testDefaultConstructor() {
        assertNotNull(new DummyData());
    }

    @Test
    @DisplayName("Full constructor sets all fields correctly")
    void testFullConstructor() {
        UUID sessionId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        String ownerName = "Steve";
        int entityId = 123;
        String worldName = "world";
        double x = 100.5, y = 64.0, z = -200.5;
        float yaw = 90.0f, pitch = 45.0f;
        long expires = System.currentTimeMillis() + 3600_000L;

        DummyData data = new DummyData(sessionId, ownerUUID, ownerName, entityId,
                worldName, x, y, z, yaw, pitch, expires);

        assertEquals(sessionId, data.getSessionId());
        assertEquals(sessionId.toString(), data.getRawSessionId());
        assertEquals(ownerUUID, data.getOwnerUUID());
        assertEquals(ownerUUID.toString(), data.getOwnerUniqueId());
        assertEquals(ownerName, data.getOwnerName());
        assertEquals(entityId, data.getDummyEntityId());
        assertEquals(worldName, data.getWorldName());
        assertEquals(x, data.getX());
        assertEquals(y, data.getY());
        assertEquals(z, data.getZ());
        assertEquals(yaw, data.getYaw());
        assertEquals(pitch, data.getPitch());
        assertEquals(expires, data.getExpirationTimestamp());
    }

    @Nested
    @DisplayName("JSON Serialization Tests")
    class JsonSerializationTests {

        static Stream<Arguments> provideDummyDataEntries() {
            List<Arguments> list = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                UUID sId = UUID.randomUUID();
                UUID oId = UUID.randomUUID();
                DummyData data = new DummyData(sId, oId, "Player_" + i, i,
                        "world_" + (i % 3), i * 1.5, 64 + i, -i * 2.0, i * 3.6f, i * 1.8f,
                        System.currentTimeMillis() + (i * 1000L));
                list.add(Arguments.of(data));
            }
            return list.stream();
        }

        @ParameterizedTest(name = "[{index}] JSON roundtrip")
        @MethodSource("provideDummyDataEntries")
        void testJsonRoundtrip(DummyData original) {
            String json = gson.toJson(original);
            DummyData parsed = gson.fromJson(json, DummyData.class);

            assertEquals(original.getSessionId(), parsed.getSessionId());
            assertEquals(original.getOwnerUUID(), parsed.getOwnerUUID());
            assertEquals(original.getOwnerName(), parsed.getOwnerName());
            assertEquals(original.getDummyEntityId(), parsed.getDummyEntityId());
            assertEquals(original.getWorldName(), parsed.getWorldName());
            assertEquals(original.getX(), parsed.getX());
            assertEquals(original.getY(), parsed.getY());
            assertEquals(original.getZ(), parsed.getZ());
            assertEquals(original.getYaw(), parsed.getYaw());
            assertEquals(original.getPitch(), parsed.getPitch());
            assertEquals(original.getExpirationTimestamp(), parsed.getExpirationTimestamp());
        }
    }

    @Nested
    @DisplayName("Expiration and Time calculation")
    class ExpirationTests {

        @Test
        @DisplayName("isExpired returns true for past timestamps")
        void testExpiredPast() {
            DummyData data = new DummyData();
            data.setExpirationTimestamp(System.currentTimeMillis() - 1000L);
            assertTrue(data.isExpired());
            assertEquals(0, data.getRemainingTimeMs());
        }

        @Test
        @DisplayName("isExpired returns false for future timestamps")
        void testNotExpiredFuture() {
            DummyData data = new DummyData();
            data.setExpirationTimestamp(System.currentTimeMillis() + 60_000L);
            assertFalse(data.isExpired());
            assertTrue(data.getRemainingTimeMs() > 0);
        }
    }

    @Nested
    @DisplayName("Location and Strings")
    class LocationTests {

        @Test
        @DisplayName("toLocation returns null if Bukkit.getWorld returns null")
        void testToLocationWorldNull() {
            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.getWorld("missing_world")).thenReturn(null);

                DummyData data = new DummyData(UUID.randomUUID(), UUID.randomUUID(), "Steve", 1,
                        "missing_world", 10, 20, 30, 0, 0, 1000);

                assertNull(data.toLocation());
            }
        }

        @Test
        @DisplayName("toLocation returns valid Location when world exists")
        void testToLocationValid() {
            World mockWorld = mock(World.class);
            when(mockWorld.getName()).thenReturn("world");

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);

                DummyData data = new DummyData(UUID.randomUUID(), UUID.randomUUID(), "Steve", 1,
                        "world", 10.5, 64.0, -20.5, 90.0f, 45.0f, 1000);

                Location loc = data.toLocation();
                assertNotNull(loc);
                assertEquals(mockWorld, loc.getWorld());
                assertEquals(10.5, loc.getX());
                assertEquals(64.0, loc.getY());
                assertEquals(-20.5, loc.getZ());
            }
        }

        @Test
        @DisplayName("Custom name and skin name serialization and deserialization")
        void testCustomNameAndSkinSerialization() {
            UUID sessionId = UUID.randomUUID();
            UUID ownerUUID = UUID.randomUUID();
            DummyData original = new DummyData(sessionId, ownerUUID, "Steve", 100,
                    "world", 10.0, 64.0, 20.0, 0f, 0f, 50000L, "CustomGuard", "Technoblade");

            assertEquals("CustomGuard", original.getCustomName());
            assertEquals("Technoblade", original.getSkinName());

            String json = gson.toJson(original);
            DummyData loaded = gson.fromJson(json, DummyData.class);

            assertEquals("CustomGuard", loaded.getCustomName());
            assertEquals("Technoblade", loaded.getSkinName());
            assertEquals(sessionId, loaded.getSessionId());
        }
    }
}
