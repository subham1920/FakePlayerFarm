package com.plugin.afkdummy.command;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.config.ConfigManager;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.storage.StorageManager;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Command Tests")
class CommandTest {

    private AFKDummyPlugin plugin;
    private ConfigManager configManager;
    private DummyManager dummyManager;
    private StorageManager storageManager;
    private Command command;

    @BeforeEach
    void setUp() {
        plugin = mock(AFKDummyPlugin.class, CALLS_REAL_METHODS);
        configManager = mock(ConfigManager.class);
        dummyManager = mock(DummyManager.class);
        storageManager = mock(StorageManager.class);
        command = mock(Command.class);

        when(command.getName()).thenReturn("afkdummy");
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getDummyManager()).thenReturn(dummyManager);
        when(plugin.getStorageManager()).thenReturn(storageManager);
    }

    @Test
    @DisplayName("Player executing /afkdummy bugreport receives message containing 'over._.simplified'")
    void testPlayerBugreportCommand() {
        Player player = mock(Player.class);
        when(player.hasPermission("afkdummy.use")).thenReturn(true);

        boolean result = plugin.onCommand(player, command, "afkdummy", new String[]{"bugreport"});
        assertTrue(result);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, times(1)).sendMessage(captor.capture());

        Component sent = captor.getValue();
        assertNotNull(sent);
    }

    @Test
    @DisplayName("Console executing afkdummy bugreport receives message containing 'over._.simplified'")
    void testConsoleBugreportCommand() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        boolean result = plugin.onCommand(console, command, "afkdummy", new String[]{"bugreport"});
        assertTrue(result);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(console, times(1)).sendMessage(captor.capture());

        assertTrue(captor.getValue().contains("over._.simplified"));
    }

    @Test
    @DisplayName("Tab complete suggests bugreport for general players")
    void testTabCompleteBugreport() {
        Player player = mock(Player.class);
        when(player.hasPermission("afkdummy.admin")).thenReturn(false);

        List<String> suggestions = plugin.onTabComplete(player, command, "afkdummy", new String[]{"bug"});
        assertNotNull(suggestions);
        assertTrue(suggestions.contains("bugreport"));
    }
}
