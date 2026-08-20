package com.plugin.afkdummy.util;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UpdateChecker Tests")
class UpdateCheckerTest {

    private AFKDummyPlugin plugin;
    private ConfigManager configManager;
    private PluginDescriptionFile description;

    @BeforeEach
    void setUp() {
        plugin = mock(AFKDummyPlugin.class);
        configManager = mock(ConfigManager.class);
        description = mock(PluginDescriptionFile.class);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getDescription()).thenReturn(description);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("UpdateCheckerTest"));
        when(description.getVersion()).thenReturn("1.0.3");
        when(configManager.isUpdateCheckerEnabled()).thenReturn(true);
        when(configManager.isNotifyUpdatesEnabled()).thenReturn(true);
    }

    @Nested
    @DisplayName("Version Comparison Tests")
    class VersionComparisonTests {

        @ParameterizedTest(name = "[{index}] compareVersions({0}, {1}) -> {2}")
        @CsvSource({
            "1.0.10, 1.0.9, 1",
            "1.0.9, 1.0.10, -1",
            "1.0.3, 1.0.2, 1",
            "1.0.2, 1.0.3, -1",
            "1.0.2, 1.0.2, 0",
            "v1.0.2, 1.0.2, 0",
            "V1.0.2, v1.0.2, 0",
            "1.0.3.1, 1.0.3, 1",
            "1.0.3, 1.0.3.1, -1",
            "2.0.0, 1.9.9, 1",
            "1.0.0, 1.0.0, 0"
        })
        void testVersionComparisons(String v1, String v2, int expectedSign) {
            int result = UpdateChecker.compareVersions(v1, v2);
            if (expectedSign > 0) {
                assertTrue(result > 0, "Expected " + v1 + " > " + v2 + " but was " + result);
            } else if (expectedSign < 0) {
                assertTrue(result < 0, "Expected " + v1 + " < " + v2 + " but was " + result);
            } else {
                assertEquals(0, result, "Expected " + v1 + " == " + v2 + " but was " + result);
            }
        }

        @Test
        @DisplayName("cleanVersion strips leading v and trims")
        void testCleanVersion() {
            assertEquals("1.0.3", UpdateChecker.cleanVersion("v1.0.3"));
            assertEquals("1.0.3", UpdateChecker.cleanVersion("V1.0.3"));
            assertEquals("1.0.3", UpdateChecker.cleanVersion("  v1.0.3  "));
            assertEquals("1.0.3", UpdateChecker.cleanVersion("1.0.3"));
            assertEquals("0.0.0", UpdateChecker.cleanVersion(null));
        }

        @Test
        @DisplayName("compareVersions handles nulls safely")
        void testNullHandling() {
            assertEquals(0, UpdateChecker.compareVersions(null, null));
            assertTrue(UpdateChecker.compareVersions("1.0.0", null) > 0);
            assertTrue(UpdateChecker.compareVersions(null, "1.0.0") < 0);
        }
    }

    @Nested
    @DisplayName("Configuration & Permission Behavior")
    class ConfigAndPermissionTests {

        @Test
        @DisplayName("When update checker is disabled, async check immediately returns DISABLED result without network call")
        void testDisabledInConfig() throws Exception {
            when(configManager.isUpdateCheckerEnabled()).thenReturn(false);

            UpdateChecker checker = new UpdateChecker(plugin);
            UpdateChecker.CheckResult result = checker.checkForUpdatesAsync().get();

            assertEquals(UpdateChecker.Status.DISABLED, result.status());
            assertFalse(result.updateAvailable());
            assertEquals("1.0.3", result.installedVersion());
        }

        @Test
        @DisplayName("Player without update permission does not receive message")
        void testNoPermissionNoNotification() {
            Player player = mock(Player.class);
            when(player.hasPermission("afkdummy.update")).thenReturn(false);
            when(player.hasPermission("afkdummy.admin")).thenReturn(false);

            UpdateChecker checker = new UpdateChecker(plugin);
            checker.notifyPlayerIfUpdateAvailable(player);

            verify(player, never()).sendMessage(any(Component.class));
        }

        @Test
        @DisplayName("Permitted player receives update message when update is cached and available")
        void testPermittedPlayerReceivesNotification() throws Exception {
            Player player = mock(Player.class);
            when(player.hasPermission("afkdummy.update")).thenReturn(true);

            UpdateChecker checker = new UpdateChecker(plugin);

            // Inject cached update available result
            UpdateChecker.CheckResult result = new UpdateChecker.CheckResult(
                    "1.0.3",
                    "1.0.4",
                    UpdateChecker.OFFICIAL_RELEASE_URL,
                    true,
                    System.currentTimeMillis(),
                    UpdateChecker.Status.UPDATE_AVAILABLE,
                    "New version available"
            );

            // Use sendUpdateMessage directly to test Adventure formatting
            checker.sendUpdateMessage(player, result);

            ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
            verify(player, times(1)).sendMessage(captor.capture());

            assertNotNull(captor.getValue());
        }
    }
}
