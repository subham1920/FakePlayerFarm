package com.plugin.afkdummy.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ConfigManager Tests")
class ConfigManagerTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private JavaPlugin plugin;
    private FileConfiguration config;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigManagerTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
    }

    @Nested
    @DisplayName("Default & Validation Loading")
    class DefaultLoadingTests {

        @Test
        @DisplayName("reload loads default valid settings")
        void testDefaultSettings() {
            when(config.getInt("settings.cost-per-hour", 5)).thenReturn(5);
            when(config.getString("settings.payment-item", "DIAMOND")).thenReturn("DIAMOND");
            when(config.getInt("settings.max-dummies-per-player", 1)).thenReturn(1);
            when(config.getInt("settings.max-server-wide-dummies", 20)).thenReturn(20);
            when(config.getInt("settings.cleanup-interval-seconds", 30)).thenReturn(30);
            when(config.getInt("settings.respawn-delay-ticks", 40)).thenReturn(40);

            ConfigManager cm = new ConfigManager(plugin);

            assertEquals(5, cm.getCostPerHour());
            assertEquals(Material.DIAMOND, cm.getPaymentItem());
            assertEquals(1, cm.getMaxDummiesPerPlayer());
            assertEquals(20, cm.getMaxServerWideDummies());
            assertEquals(30, cm.getCleanupIntervalSeconds());
            assertEquals(40, cm.getRespawnDelayTicks());
            assertEquals("Diamond", cm.getPaymentItemDisplayName());
        }

        @Test
        @DisplayName("reload clamps minimum values appropriately")
        void testMinimumClamping() {
            when(config.getInt("settings.cost-per-hour", 5)).thenReturn(-10);
            when(config.getString("settings.payment-item", "DIAMOND")).thenReturn("DIAMOND");
            when(config.getInt("settings.max-dummies-per-player", 1)).thenReturn(0);
            when(config.getInt("settings.max-server-wide-dummies", 20)).thenReturn(-5);
            when(config.getInt("settings.cleanup-interval-seconds", 30)).thenReturn(2);
            when(config.getInt("settings.respawn-delay-ticks", 40)).thenReturn(0);

            ConfigManager cm = new ConfigManager(plugin);

            assertEquals(1, cm.getCostPerHour());
            assertEquals(1, cm.getMaxDummiesPerPlayer());
            assertEquals(1, cm.getMaxServerWideDummies());
            assertEquals(5, cm.getCleanupIntervalSeconds());
            assertEquals(1, cm.getRespawnDelayTicks());
        }

        @ParameterizedTest(name = "Payment item = {0} -> Expected = {1}")
        @CsvSource({
            "EMERALD, EMERALD",
            "GOLD_INGOT, GOLD_INGOT",
            "IRON_INGOT, IRON_INGOT",
            "MINECRAFT:DIAMOND, DIAMOND",
            "minecraft:gold_ingot, GOLD_INGOT",
            "MINECRAFT:EMERALD, EMERALD",
            "INVALID_ITEM_XYZ, DIAMOND"
        })
        void testPaymentItemParsing(String configVal, String expectedMat) {
            when(config.getInt(anyString(), anyInt())).thenReturn(5);
            when(config.getString("settings.payment-item", "DIAMOND")).thenReturn(configVal);

            ConfigManager cm = new ConfigManager(plugin);
            assertEquals(Material.valueOf(expectedMat), cm.getPaymentItem());
        }
    }

    @Nested
    @DisplayName("calculateCost(int hours)")
    class CalculateCostTests {

        static Stream<Arguments> provideCostCalculations() {
            List<Arguments> list = new ArrayList<>();
            for (int rate = 1; rate <= 10; rate++) {
                for (int hours = 1; hours <= 15; hours++) {
                    list.add(Arguments.of(rate, hours, rate * hours));
                }
            }
            return list.stream();
        }

        @ParameterizedTest(name = "[{index}] costRate={0}, hours={1} -> expected={2}")
        @MethodSource("provideCostCalculations")
        void testCalculateCost(int costRate, int hours, int expectedCost) {
            when(config.getString(anyString(), anyString())).thenReturn("DIAMOND");
            when(config.getInt("settings.cost-per-hour", 5)).thenReturn(costRate);

            ConfigManager cm = new ConfigManager(plugin);
            assertEquals(expectedCost, cm.calculateCost(hours));
        }
    }

    @Nested
    @DisplayName("getPaymentItemDisplayName()")
    class DisplayNameTests {

        @ParameterizedTest(name = "Material={0} -> Display={1}")
        @CsvSource({
            "DIAMOND, Diamond",
            "GOLD_INGOT, Gold Ingot",
            "IRON_INGOT, Iron Ingot",
            "NETHERITE_INGOT, Netherite Ingot",
            "REDSTONE, Redstone",
            "EMERALD, Emerald",
            "LAPIS_LAZULI, Lapis Lazuli"
        })
        void testPaymentItemDisplayNames(String matName, String expectedDisplay) {
            when(config.getString("settings.payment-item", "DIAMOND")).thenReturn(matName);
            when(config.getInt(anyString(), anyInt())).thenReturn(5);

            ConfigManager cm = new ConfigManager(plugin);
            assertEquals(expectedDisplay, cm.getPaymentItemDisplayName());
        }
    }

    @Nested
    @DisplayName("Direct config.json File Parsing & Mutation Tests")
    class JsonConfigTests {

        @Test
        @DisplayName("Loads custom values directly from config.json")
        void testLoadFromJson() throws Exception {
            String jsonContent = """
            {
              "settings": {
                "cost-per-hour": 10,
                "payment-item": "GOLD_INGOT",
                "payment-item-display-name": "Gold Ingot",
                "max-dummies-per-player": 3,
                "max-server-wide-dummies": 50,
                "cleanup-interval-seconds": 60,
                "respawn-delay-ticks": 100
              }
            }
            """;
            java.nio.file.Files.writeString(tempDir.resolve("config.json"), jsonContent);

            ConfigManager cm = new ConfigManager(plugin);

            assertEquals(10, cm.getCostPerHour());
            assertEquals(Material.GOLD_INGOT, cm.getPaymentItem());
            assertEquals("Gold Ingot", cm.getPaymentItemDisplayName());
            assertEquals(3, cm.getMaxDummiesPerPlayer());
            assertEquals(50, cm.getMaxServerWideDummies());
            assertEquals(60, cm.getCleanupIntervalSeconds());
            assertEquals(100, cm.getRespawnDelayTicks());
            assertEquals(30, cm.calculateCost(3));
        }

        @Test
        @DisplayName("Reloads new mutated values when config.json changes on disk")
        void testMutateAndReloadJson() throws Exception {
            // Initial state
            java.nio.file.Files.writeString(tempDir.resolve("config.json"), "{\"settings\": {\"max-dummies-per-player\": 1, \"payment-item\": \"IRON_INGOT\"}}");
            ConfigManager cm = new ConfigManager(plugin);
            assertEquals(1, cm.getMaxDummiesPerPlayer());
            assertEquals(Material.IRON_INGOT, cm.getPaymentItem());

            // Mutate file on disk to unusual values
            java.nio.file.Files.writeString(tempDir.resolve("config.json"), "{\"settings\": {\"max-dummies-per-player\": 7, \"payment-item\": \"EMERALD\", \"cost-per-hour\": 12}}");
            cm.reload();

            assertEquals(7, cm.getMaxDummiesPerPlayer());
            assertEquals(Material.EMERALD, cm.getPaymentItem());
            assertEquals(12, cm.getCostPerHour());
            assertEquals("Emerald", cm.getPaymentItemDisplayName());
        }

        @Test
        @DisplayName("Parses update-checker boolean flags from config.json")
        void testUpdateCheckerJson() throws Exception {
            String jsonContent = """
            {
              "settings": {
                "update-checker": {
                  "enabled": false,
                  "notify-players": false
                }
              }
            }
            """;
            java.nio.file.Files.writeString(tempDir.resolve("config.json"), jsonContent);

            ConfigManager cm = new ConfigManager(plugin);

            assertFalse(cm.isUpdateCheckerEnabled());
            assertFalse(cm.isNotifyUpdatesEnabled());
        }

        @Test
        @DisplayName("Defaults update-checker to true when omitted")
        void testUpdateCheckerDefaults() throws Exception {
            String jsonContent = "{\"settings\": {\"cost-per-hour\": 5}}";
            java.nio.file.Files.writeString(tempDir.resolve("config.json"), jsonContent);

            ConfigManager cm = new ConfigManager(plugin);

            assertTrue(cm.isUpdateCheckerEnabled());
            assertTrue(cm.isNotifyUpdatesEnabled());
        }
    }
}
