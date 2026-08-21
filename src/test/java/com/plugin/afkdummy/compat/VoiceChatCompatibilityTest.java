package com.plugin.afkdummy.compat;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.entity.DummyPlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("VoiceChatCompatibility Tests")
class VoiceChatCompatibilityTest {

    private AFKDummyPlugin plugin;
    private Server server;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        plugin = mock(AFKDummyPlugin.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("VoiceChatCompatTest"));
        when(server.getPluginManager()).thenReturn(pluginManager);
    }

    @Test
    @DisplayName("Detects Simple Voice Chat when plugin is present and enabled")
    void testVoiceChatDetected() {
        Plugin voiceChatPlugin = mock(Plugin.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);

        when(desc.getVersion()).thenReturn("2.6.21");
        when(voiceChatPlugin.getDescription()).thenReturn(desc);
        when(voiceChatPlugin.isEnabled()).thenReturn(true);
        when(pluginManager.getPlugin("voicechat")).thenReturn(voiceChatPlugin);

        VoiceChatCompatibility compat = new VoiceChatCompatibility(plugin);

        assertTrue(compat.isVoiceChatPresent());
        assertEquals("2.6.21", compat.getVoiceChatVersion());
    }

    @Test
    @DisplayName("Handles absence of Simple Voice Chat gracefully in standalone mode")
    void testVoiceChatAbsent() {
        when(pluginManager.getPlugin("voicechat")).thenReturn(null);

        VoiceChatCompatibility compat = new VoiceChatCompatibility(plugin);

        assertFalse(compat.isVoiceChatPresent());
        assertNull(compat.getVoiceChatVersion());
    }

    @Test
    @DisplayName("Tags fake player with standard Bukkit NPC metadata")
    void testTagFakePlayer() {
        when(pluginManager.getPlugin("voicechat")).thenReturn(null);
        VoiceChatCompatibility compat = new VoiceChatCompatibility(plugin);

        Player player = mock(Player.class);
        compat.tagFakePlayer(player);

        verify(player, times(1)).setMetadata(eq("NPC"), any(FixedMetadataValue.class));
        verify(player, times(1)).setMetadata(eq("afkdummy"), any(FixedMetadataValue.class));
        verify(player, times(1)).setMetadata(eq("afkdummy:fake_player"), any(FixedMetadataValue.class));
    }

    @Test
    @DisplayName("isFakePlayer returns true when metadata is present")
    void testIsFakePlayerWithMetadata() {
        when(pluginManager.getPlugin("voicechat")).thenReturn(null);
        VoiceChatCompatibility compat = new VoiceChatCompatibility(plugin);

        Player player = mock(Player.class);
        when(player.hasMetadata("NPC")).thenReturn(true);

        assertTrue(compat.isFakePlayer(player));
        assertTrue(DummyPlayer.isNPC(player));
    }

    @Test
    @DisplayName("isFakePlayer returns false for normal real player")
    void testIsFakePlayerForNormalPlayer() {
        when(pluginManager.getPlugin("voicechat")).thenReturn(null);
        VoiceChatCompatibility compat = new VoiceChatCompatibility(plugin);

        Player player = mock(Player.class);
        when(player.hasMetadata("NPC")).thenReturn(false);
        when(player.hasMetadata("afkdummy")).thenReturn(false);
        when(player.hasMetadata("afkdummy:fake_player")).thenReturn(false);

        DummyManager dummyManager = mock(DummyManager.class);
        when(plugin.getDummyManager()).thenReturn(dummyManager);
        when(dummyManager.isDummyPlayer(player)).thenReturn(false);

        assertFalse(compat.isFakePlayer(player));
        assertFalse(DummyPlayer.isNPC(player));
    }
}
