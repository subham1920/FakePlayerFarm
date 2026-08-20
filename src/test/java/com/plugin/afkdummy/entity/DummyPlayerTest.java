package com.plugin.afkdummy.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DummyPlayer Static Logic Tests")
class DummyPlayerTest {

    private UUID invokeGenerateDummyUUID(UUID ownerUUID, UUID sessionId) throws Exception {
        Method method = DummyPlayer.class.getDeclaredMethod("generateDummyUUID", UUID.class, UUID.class);
        method.setAccessible(true);
        return (UUID) method.invoke(null, ownerUUID, sessionId);
    }

    private String invokeGenerateProfileName(String ownerName, UUID sessionId) throws Exception {
        Method method = DummyPlayer.class.getDeclaredMethod("generateProfileName", String.class, UUID.class);
        method.setAccessible(true);
        return (String) method.invoke(null, ownerName, sessionId);
    }

    @Nested
    @DisplayName("generateDummyUUID Tests")
    class DummyUUIDTests {

        static Stream<Arguments> provideUUIDPairs() {
            List<Arguments> list = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                list.add(Arguments.of(UUID.randomUUID(), UUID.randomUUID()));
            }
            return list.stream();
        }

        @ParameterizedTest(name = "[{index}] deterministic UUID check")
        @MethodSource("provideUUIDPairs")
        void testDeterministicUUID(UUID owner, UUID session) throws Exception {
            UUID u1 = invokeGenerateDummyUUID(owner, session);
            UUID u2 = invokeGenerateDummyUUID(owner, session);
            assertNotNull(u1);
            assertEquals(u1, u2);
        }

        @Test
        @DisplayName("Different sessionId produces different UUID")
        void testDifferentSessionId() throws Exception {
            UUID owner = UUID.randomUUID();
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();

            assertNotEquals(invokeGenerateDummyUUID(owner, s1), invokeGenerateDummyUUID(owner, s2));
        }

        @Test
        @DisplayName("Different ownerUUID produces different UUID")
        void testDifferentOwner() throws Exception {
            UUID o1 = UUID.randomUUID();
            UUID o2 = UUID.randomUUID();
            UUID session = UUID.randomUUID();

            assertNotEquals(invokeGenerateDummyUUID(o1, session), invokeGenerateDummyUUID(o2, session));
        }
    }

    @Nested
    @DisplayName("generateProfileName Tests")
    class ProfileNameTests {

        static Stream<Arguments> provideProfileNameCandidates() {
            List<Arguments> list = new ArrayList<>();
            String[] baseNames = {
                "Steve", "Alex", "Notch", "Jeb_", "Dinnerbone", "Grumm",
                "LongPlayerNameExceedingSixteenCharacters", "12345", "a", "ab",
                "abc", "Special!@#$Chars", "Player_One", "Player-Two", "Player.Three",
                "A_B_C_D_E_F", "UPPERCASE", "lowercase", "CamelCase", "MiXeD_CaSe",
                "!@#$%^&*()", "   Spaces   ", "___", "123_456_789_000_extra"
            };

            for (String name : baseNames) {
                for (int i = 0; i < 5; i++) {
                    list.add(Arguments.of(name, UUID.randomUUID()));
                }
            }
            return list.stream();
        }

        @ParameterizedTest(name = "[{index}] name={0}")
        @MethodSource("provideProfileNameCandidates")
        void testProfileNameValidity(String owner, UUID sessionId) throws Exception {
            String profileName = invokeGenerateProfileName(owner, sessionId);

            assertNotNull(profileName);
            assertTrue(profileName.length() <= 16, "Profile name length must be <= 16, but was " + profileName.length() + ": " + profileName);
            assertTrue(profileName.matches("^[a-zA-Z0-9_]+$"), "Must contain only valid Minecraft name chars: " + profileName);
        }

        @Test
        @DisplayName("Fallback on completely invalid characters")
        void testFallback() throws Exception {
            UUID session = UUID.randomUUID();
            String profileName = invokeGenerateProfileName("!@#$%", session);
            assertEquals("Dummy", profileName);
        }

        @Test
        @DisplayName("Exact names are preserved without trailing suffix")
        void testExactNamePreservation() throws Exception {
            UUID session = UUID.randomUUID();
            assertEquals("John", invokeGenerateProfileName("John", session));
            assertEquals("JustRyt", invokeGenerateProfileName("JustRyt", session));
            assertEquals("Guard", invokeGenerateProfileName("Guard", session));
            assertEquals("Farmer", invokeGenerateProfileName("Farmer", session));
        }
    }
}
