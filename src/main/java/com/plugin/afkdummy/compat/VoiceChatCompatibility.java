package com.plugin.afkdummy.compat;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.util.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

/**
 * Isolated compatibility service for Simple Voice Chat and other third-party integrations.
 * <p>
 * Detects Simple Voice Chat dynamically, ensures fake AFK dummy entities are tagged with
 * standard Bukkit NPC metadata ("NPC", "afkdummy", "afkdummy:fake_player"), and manages
 * the safe boundary between virtual dummy connections and real client audio channels.
 * </p>
 */
public class VoiceChatCompatibility {

    public static final String METADATA_NPC = "NPC";
    public static final String METADATA_AFKDUMMY = "afkdummy";
    public static final String METADATA_FAKE_PLAYER = "afkdummy:fake_player";

    private final AFKDummyPlugin plugin;
    private final boolean voiceChatPresent;
    private final String voiceChatVersion;
    private final boolean sparkPresent;
    private final String sparkVersion;

    public VoiceChatCompatibility(AFKDummyPlugin plugin) {
        this.plugin = plugin;

        Plugin vcPlugin = null;
        Plugin spPlugin = null;
        try {
            if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
                vcPlugin = plugin.getServer().getPluginManager().getPlugin("voicechat");
                spPlugin = plugin.getServer().getPluginManager().getPlugin("spark");
            }
        } catch (Throwable ignored) {}

        this.voiceChatPresent = (vcPlugin != null && vcPlugin.isEnabled());
        this.voiceChatVersion = (this.voiceChatPresent) ? vcPlugin.getDescription().getVersion() : null;

        this.sparkPresent = (spPlugin != null && spPlugin.isEnabled());
        this.sparkVersion = (this.sparkPresent) ? spPlugin.getDescription().getVersion() : null;

        String vcStatus = voiceChatPresent ? voiceChatVersion : "not detected";
        String spStatus = sparkPresent ? sparkVersion : "not detected";

        plugin.getLogger().info(String.format("[AFKDummy] Integration detection: Simple Voice Chat = %s | spark = %s", vcStatus, spStatus));
        plugin.getLogger().info("[AFKDummy] Fake-player integration compatibility: ENABLED");

        DebugLogger.log(String.format("INTEGRATION_DETECTION voicechat=%s (version=%s) spark=%s (version=%s) status=ENABLED",
                voiceChatPresent, vcStatus, sparkPresent, spStatus));
    }

    /**
     * Tags a dummy player entity with standard Bukkit NPC metadata and AFKDummy markers.
     *
     * @param player the Player entity to tag
     */
    public void tagFakePlayer(Player player) {
        if (player == null) return;

        try {
            FixedMetadataValue val = new FixedMetadataValue(plugin, true);
            player.setMetadata(METADATA_NPC, val);
            player.setMetadata(METADATA_AFKDUMMY, val);
            player.setMetadata(METADATA_FAKE_PLAYER, val);
        } catch (Throwable t) {
            DebugLogger.log("Warning: Failed to set metadata on dummy player: " + t.getMessage());
        }
    }

    /**
     * Checks if a player is tagged as a fake/NPC dummy.
     *
     * @param player the Player to check
     * @return true if the player has NPC/AFKDummy metadata or is registered in DummyManager
     */
    public boolean isFakePlayer(Player player) {
        if (player == null) return false;
        if (player.hasMetadata(METADATA_NPC) || player.hasMetadata(METADATA_AFKDUMMY) || player.hasMetadata(METADATA_FAKE_PLAYER)) {
            return true;
        }
        return plugin.getDummyManager() != null && plugin.getDummyManager().isDummyPlayer(player);
    }

    /**
     * @return true if Simple Voice Chat is installed and detected on this server
     */
    public boolean isVoiceChatPresent() {
        return voiceChatPresent;
    }

    /**
     * @return the detected Simple Voice Chat version string, or null if absent
     */
    public String getVoiceChatVersion() {
        return voiceChatVersion;
    }

    /**
     * @return true if spark is installed and detected on this server
     */
    public boolean isSparkPresent() {
        return sparkPresent;
    }

    /**
     * @return the detected spark version string, or null if absent
     */
    public String getSparkVersion() {
        return sparkVersion;
    }
}
