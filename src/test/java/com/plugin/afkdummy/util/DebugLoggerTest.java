package com.plugin.afkdummy.util;

import com.plugin.afkdummy.AFKDummyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DebugLogger Tests")
class DebugLoggerTest {

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException")
    void testConstructorThrows() throws Exception {
        Constructor<DebugLogger> constructor = DebugLogger.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    @DisplayName("Logging before init does not crash")
    void testLogBeforeInit() {
        assertDoesNotThrow(() -> DebugLogger.log("Test uninitialized log"));
    }

    @Test
    @DisplayName("init and log create latest-debug.txt with session header")
    void testInitAndLog(@TempDir Path tempDir) throws Exception {
        AFKDummyPlugin plugin = mock(AFKDummyPlugin.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getVersion()).thenReturn("1.0.2-debug");
        when(plugin.getDescription()).thenReturn(desc);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("TestLogger"));

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getVersion).thenReturn("Paper 1.21.4");
            mockedBukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.4-R0.1-SNAPSHOT");

            DebugLogger.init(plugin);

            File logFile = new File(tempDir.toFile(), "latest-debug.txt");
            assertTrue(logFile.exists());

            DebugLogger.log("Hello from test");
            DebugLogger.trace("DummyPlayer.java:spawn", "Spawn initiated");
            DebugLogger.command("Player1", "/afkdummy tp", "SUCCESS", "teleported to base");
            DebugLogger.close();

            List<String> lines = Files.readAllLines(logFile.toPath());
            assertTrue(lines.size() >= 5);
            assertTrue(lines.stream().anyMatch(l -> l.contains("AFKDummy DEBUG SESSION")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("Hello from test")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("Spawn initiated")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("/afkdummy tp")));
        }
    }

    @Test
    @DisplayName("Concurrent multi-threaded logging is thread safe")
    void testConcurrentLogging(@TempDir Path tempDir) throws Exception {
        AFKDummyPlugin plugin = mock(AFKDummyPlugin.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getVersion()).thenReturn("1.0.2-debug");
        when(plugin.getDescription()).thenReturn(desc);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("TestLogger"));

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getVersion).thenReturn("Paper 1.21.4");
            mockedBukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.4-R0.1-SNAPSHOT");

            DebugLogger.init(plugin);

            int threads = 10;
            int logsPerThread = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    for (int i = 0; i < logsPerThread; i++) {
                        DebugLogger.log("Thread " + threadId + " message " + i);
                    }
                    latch.countDown();
                });
            }

            latch.await();
            executor.shutdown();
            DebugLogger.close();

            File logFile = new File(tempDir.toFile(), "latest-debug.txt");
            List<String> lines = Files.readAllLines(logFile.toPath());
            assertTrue(lines.size() >= (threads * logsPerThread));
        }
    }
}
