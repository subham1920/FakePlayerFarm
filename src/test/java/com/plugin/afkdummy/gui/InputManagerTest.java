package com.plugin.afkdummy.gui;

import com.plugin.afkdummy.AFKDummyPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("InputManager Tests")
class InputManagerTest {

    private AFKDummyPlugin mockPlugin;
    private InputManager inputManager;
    private Player mockPlayer;
    private UUID playerUUID;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(AFKDummyPlugin.class);
        inputManager = new InputManager(mockPlugin);
        mockPlayer = mock(Player.class);
        playerUUID = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        when(mockPlayer.getUniqueId()).thenReturn(playerUUID);
        when(mockPlayer.isOnline()).thenReturn(true);
    }

    @Nested
    @DisplayName("Input Request Lifecycle")
    class RequestTests {

        @Test
        @DisplayName("requestInput sets pending state and formats message")
        void testRequestInput() {
            inputManager.requestInput(
                    mockPlayer,
                    InputManager.InputType.RENAME,
                    sessionId,
                    "Enter new name:",
                    text -> {}
            );

            assertTrue(inputManager.hasPendingInput(playerUUID));
            verify(mockPlayer, atLeastOnce()).sendMessage(anyString());
        }

        @Test
        @DisplayName("cancelForSession removes pending input for matching session")
        void testCancelForSession() {
            inputManager.requestInput(
                    mockPlayer,
                    InputManager.InputType.RENAME,
                    sessionId,
                    "Enter new name:",
                    text -> {}
            );
            assertTrue(inputManager.hasPendingInput(playerUUID));

            inputManager.cancelForSession(sessionId);
            assertFalse(inputManager.hasPendingInput(playerUUID));
        }

        @Test
        @DisplayName("Player quit removes pending input")
        void testPlayerQuit() {
            inputManager.requestInput(
                    mockPlayer,
                    InputManager.InputType.CHANGE_SKIN,
                    sessionId,
                    "Enter skin:",
                    text -> {}
            );
            assertTrue(inputManager.hasPendingInput(playerUUID));

            PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
            when(quitEvent.getPlayer()).thenReturn(mockPlayer);
            inputManager.onPlayerQuit(quitEvent);

            assertFalse(inputManager.hasPendingInput(playerUUID));
        }
    }

    @Nested
    @DisplayName("Chat Event Interception")
    class ChatInterceptionTests {

        @Test
        @DisplayName("Paper AsyncChatEvent receives input and invokes callback on main thread")
        void testPaperChatSuccess() {
            AtomicReference<String> received = new AtomicReference<>();

            inputManager.requestInput(
                    mockPlayer,
                    InputManager.InputType.RENAME,
                    sessionId,
                    "Enter name:",
                    received::set
            );

            BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
            doAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                task.run();
                return null;
            }).when(mockScheduler).runTask(eq(mockPlugin), any(Runnable.class));

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

                AsyncChatEvent chatEvent = mock(AsyncChatEvent.class);
                when(chatEvent.getPlayer()).thenReturn(mockPlayer);
                when(chatEvent.message()).thenReturn(Component.text("MyCustomGuard"));

                inputManager.onPaperChat(chatEvent);

                verify(chatEvent).setCancelled(true);
                assertEquals("MyCustomGuard", received.get());
                assertFalse(inputManager.hasPendingInput(playerUUID));
            }
        }

        @Test
        @DisplayName("Legacy AsyncPlayerChatEvent receives input and invokes callback on main thread")
        void testLegacyChatSuccess() {
            AtomicReference<String> received = new AtomicReference<>();

            inputManager.requestInput(
                    mockPlayer,
                    InputManager.InputType.CHANGE_SKIN,
                    sessionId,
                    "Enter skin:",
                    received::set
            );

            BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
            doAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                task.run();
                return null;
            }).when(mockScheduler).runTask(eq(mockPlugin), any(Runnable.class));

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

                AsyncPlayerChatEvent legacyChat = new AsyncPlayerChatEvent(
                        false,
                        mockPlayer,
                        "Notch",
                        new HashSet<>()
                );

                inputManager.onLegacyChat(legacyChat);

                assertTrue(legacyChat.isCancelled());
                assertEquals("Notch", received.get());
                assertFalse(inputManager.hasPendingInput(playerUUID));
            }
        }

        @Test
        @DisplayName("Typing 'cancel' aborts the operation")
        void testCancelInput() {
            AtomicReference<String> received = new AtomicReference<>();

            inputManager.requestInput(
                    mockPlayer,
                    InputManager.InputType.CHANGE_SKIN,
                    sessionId,
                    "Enter skin:",
                    received::set
            );

            AsyncPlayerChatEvent legacyChat = new AsyncPlayerChatEvent(
                    false,
                    mockPlayer,
                    "cancel",
                    new HashSet<>()
            );

            inputManager.onLegacyChat(legacyChat);

            assertTrue(legacyChat.isCancelled());
            assertNull(received.get());
            assertFalse(inputManager.hasPendingInput(playerUUID));
            verify(mockPlayer).sendMessage(contains("cancelled"));
        }
    }
}
