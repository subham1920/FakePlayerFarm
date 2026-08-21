package com.plugin.afkdummy.api;

import com.plugin.afkdummy.AFKDummyPlugin;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Public API for AFKDummy.
 * Allows other server plugins to identify and interact with AFK dummy entities.
 */
public final class AfkDummyAPI {

    private static AFKDummyPlugin plugin;

    private AfkDummyAPI() {}

    /**
     * Initializes the static API instance.
     *
     * @param instance the owning plugin instance
     */
    public static void init(AFKDummyPlugin instance) {
        plugin = instance;
    }

    /**
     * Checks if a given Bukkit Player entity is a managed AFK dummy.
     *
     * @param player the Player to check
     * @return true if the player is an AFK dummy
     */
    public static boolean isDummy(Player player) {
        if (player == null) return false;
        if (player.hasMetadata("NPC") || player.hasMetadata("afkdummy") || player.hasMetadata("afkdummy:fake_player")) {
            return true;
        }
        if (plugin != null && plugin.getDummyManager() != null) {
            return plugin.getDummyManager().isDummyPlayer(player);
        }
        return false;
    }

    /**
     * Checks if a given UUID belongs to an active AFK dummy.
     *
     * @param uuid the UUID to check
     * @return true if the UUID belongs to an active dummy
     */
    public static boolean isDummy(UUID uuid) {
        if (uuid == null) return false;
        if (plugin != null && plugin.getDummyManager() != null) {
            return plugin.getDummyManager().getSession(uuid).isPresent()
                    || plugin.getDummyManager().getAllSessions().values().stream()
                    .anyMatch(s -> s.getDummyPlayer() != null && s.getDummyPlayer().getHandle().getUUID().equals(uuid));
        }
        return false;
    }
}
