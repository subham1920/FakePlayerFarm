package com.plugin.afkdummy.util;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
    @DisplayName("init and log create and append to debug.log")
    void testInitAndLog(@TempDir Path tempDir) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DebugLogger.init(plugin);

        File logFile = new File(tempDir.toFile(), "debug.log");
        assertTrue(logFile.exists());

        DebugLogger.log("Hello from test");
        DebugLogger.log("Second message");

        List<String> lines = Files.readAllLines(logFile.toPath());
        assertTrue(lines.size() >= 3);
        assertTrue(lines.get(0).contains("Debug Logger Initialized"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Hello from test")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Second message")));
    }

    @Test
    @DisplayName("Concurrent multi-threaded logging is thread safe")
    void testConcurrentLogging(@TempDir Path tempDir) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DebugLogger.init(plugin);

        int threads = 10;
        int logsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < logsPerThread; i++) {
                        DebugLogger.log("Thread-" + threadId + " msg " + i);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        File logFile = new File(tempDir.toFile(), "debug.log");
        List<String> lines = Files.readAllLines(logFile.toPath());
        assertEquals(1 + (threads * logsPerThread), lines.size());
    }
}
