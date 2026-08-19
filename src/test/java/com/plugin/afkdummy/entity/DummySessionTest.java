package com.plugin.afkdummy.entity;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DummySession Tests")
class DummySessionTest {

    private DummyPlayer dummyPlayer;
    private UUID ownerUUID;
    private String ownerName;

    @BeforeEach
    void setUp() {
        dummyPlayer = mock(DummyPlayer.class);
        ownerUUID = UUID.randomUUID();
        ownerName = "Steve";
    }

    @Test
    @DisplayName("Constructor with explicit sessionId")
    void testConstructorExplicitSessionId() {
        UUID sessionId = UUID.randomUUID();
        long expiration = System.currentTimeMillis() + 3600_000L;

        DummySession session = new DummySession(sessionId, dummyPlayer, ownerUUID, ownerName, expiration);

        assertEquals(sessionId, session.getSessionId());
        assertEquals(dummyPlayer, session.getDummyPlayer());
        assertEquals(ownerUUID, session.getOwnerUUID());
        assertEquals(ownerName, session.getOwnerName());
        assertEquals(expiration, session.getExpirationTimestamp());
        assertTrue(session.getCreationTimestamp() <= System.currentTimeMillis());
        assertFalse(session.isExpired());
    }

    @Test
    @DisplayName("Constructor with null sessionId generates random UUID")
    void testConstructorNullSessionId() {
        long expiration = System.currentTimeMillis() + 3600_000L;
        DummySession session = new DummySession(null, dummyPlayer, ownerUUID, ownerName, expiration);
        assertNotNull(session.getSessionId());
    }

    @Test
    @DisplayName("Constructor deriving session ID from DummyPlayer")
    void testConstructorDerivingSessionId() {
        UUID expectedId = UUID.randomUUID();
        when(dummyPlayer.getSessionId()).thenReturn(expectedId);

        DummySession session = new DummySession(dummyPlayer, ownerUUID, ownerName, 5000L);
        assertEquals(expectedId, session.getSessionId());
    }

    @Nested
    @DisplayName("Time & Expiration calculation")
    class ExpirationTests {

        @Test
        @DisplayName("Expired session reports isExpired true and remaining 0")
        void testExpiredSession() {
            long pastTime = System.currentTimeMillis() - 5000L;
            DummySession session = new DummySession(UUID.randomUUID(), dummyPlayer, ownerUUID, ownerName, pastTime);

            assertTrue(session.isExpired());
            assertEquals(0L, session.getRemainingTimeMs());
            assertEquals("00:00:00", session.getFormattedTimeRemaining());
            assertEquals("0 seconds", session.getFormattedTimeRemainingLong());
        }

        @Test
        @DisplayName("Active session reports isExpired false and positive remaining")
        void testActiveSession() {
            long futureTime = System.currentTimeMillis() + 3600_000L;
            DummySession session = new DummySession(UUID.randomUUID(), dummyPlayer, ownerUUID, ownerName, futureTime);

            assertFalse(session.isExpired());
            assertTrue(session.getRemainingTimeMs() > 0);
            assertNotEquals("00:00:00", session.getFormattedTimeRemaining());
        }

        @Test
        @DisplayName("getTotalDurationMs calculates duration from creation")
        void testTotalDuration() {
            long futureTime = System.currentTimeMillis() + 7200_000L;
            DummySession session = new DummySession(UUID.randomUUID(), dummyPlayer, ownerUUID, ownerName, futureTime);

            long totalDuration = session.getTotalDurationMs();
            assertTrue(totalDuration >= 7190_000L && totalDuration <= 7210_000L);
        }
    }

    @Nested
    @DisplayName("Delegation to DummyPlayer")
    class DelegationTests {

        @Test
        @DisplayName("getLocation delegates to DummyPlayer.getLocation")
        void testGetLocation() {
            World mockWorld = mock(World.class);
            Location loc = new Location(mockWorld, 10, 64, 20);
            when(dummyPlayer.getLocation()).thenReturn(loc);

            DummySession session = new DummySession(UUID.randomUUID(), dummyPlayer, ownerUUID, ownerName, 5000L);
            assertEquals(loc, session.getLocation());
            verify(dummyPlayer).getLocation();
        }

        @Test
        @DisplayName("despawn delegates to DummyPlayer.remove")
        void testDespawn() {
            DummySession session = new DummySession(UUID.randomUUID(), dummyPlayer, ownerUUID, ownerName, 5000L);
            session.despawn();
            verify(dummyPlayer).remove();
        }

        @Test
        @DisplayName("isSpawned delegates to DummyPlayer.isSpawned")
        void testIsSpawned() {
            when(dummyPlayer.isSpawned()).thenReturn(true);
            DummySession session = new DummySession(UUID.randomUUID(), dummyPlayer, ownerUUID, ownerName, 5000L);
            assertTrue(session.isSpawned());

            when(dummyPlayer.isSpawned()).thenReturn(false);
            assertFalse(session.isSpawned());
        }

        @Test
        @DisplayName("toString format contains key fields")
        void testToString() {
            UUID sessionId = UUID.randomUUID();
            DummySession session = new DummySession(sessionId, dummyPlayer, ownerUUID, ownerName, 5000L);
            String str = session.toString();
            assertTrue(str.contains(sessionId.toString()));
            assertTrue(str.contains(ownerName));
        }
    }
}
