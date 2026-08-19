package com.plugin.afkdummy.gui;

import com.plugin.afkdummy.AFKDummyPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages thread-safe player chat input prompts for GUI actions (e.g. Rename and Change Skin).
 * <p>
 * Features:
 * <ul>
 *   <li>One active prompt per player at any time</li>
 *   <li>Configurable timeout (default 30 seconds) with periodic cleanup</li>
 *   <li>Cancellation support via typing 'cancel' or 'abort'</li>
 *   <li>Automatic player disconnect and dummy despawn cleanup</li>
 *   <li>Thread-safe callback dispatch returning to Bukkit main server thread</li>
 * </ul>
 * </p>
 */
public class InputManager implements Listener {

    public enum InputType {
        RENAME("Rename Dummy"),
        CHANGE_SKIN("Change Skin");

        private final String displayName;

        InputType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public record PendingInput(
            InputType type,
            UUID playerUUID,
            UUID sessionId,
            long expiryTimeMs,
            Consumer<String> callback
    ) {}

    private final AFKDummyPlugin plugin;
    private final Map<UUID, PendingInput> pendingInputs;
    private BukkitTask cleanupTask;

    public InputManager(AFKDummyPlugin plugin) {
        this.plugin = plugin;
        this.pendingInputs = new ConcurrentHashMap<>();
    }

    /**
     * Starts the periodic expiry cleanup task.
     */
    public void start() {
        // Run expiry check every 5 seconds (100 ticks)
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpired, 100L, 100L);
    }

    /**
     * Shuts down the input manager and cancels all pending prompts.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        pendingInputs.clear();
    }

    /**
     * Requests text input from a player with an interactive chat prompt.
     *
     * @param player      the player to prompt
     * @param type        the type of input being requested
     * @param sessionId   the target dummy session ID
     * @param instruction user-facing instruction message
     * @param callback    main-thread consumer receiving the validated input string
     */
    public void requestInput(Player player, InputType type, UUID sessionId, String instruction, Consumer<String> callback) {
        if (player == null || !player.isOnline()) return;

        long expiryTimeMs = System.currentTimeMillis() + 30_000L; // 30 seconds timeout
        PendingInput pending = new PendingInput(type, player.getUniqueId(), sessionId, expiryTimeMs, callback);
        pendingInputs.put(player.getUniqueId(), pending);

        // Send formatted chat prompt
        player.sendMessage("§7§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage("§5§l✦ §d§lAFK Dummy §8— §e§l" + type.getDisplayName() + " §5§l✦");
        player.sendMessage("§f " + instruction);
        player.sendMessage("§7 Type §c'cancel'§7 in chat to abort. §8(30s timeout)");
        player.sendMessage("§7§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
        } catch (Throwable ignored) {}
    }

    /**
     * Handles Paper Modern AsyncChatEvent.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending == null) return;

        event.setCancelled(true);
        String rawText = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        processInput(player, pending, rawText);
    }

    /**
     * Handles Bukkit Legacy AsyncPlayerChatEvent as fallback.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending == null) return;

        event.setCancelled(true);
        String rawText = event.getMessage().trim();
        processInput(player, pending, rawText);
    }

    /**
     * Validates and routes player input to the main server thread.
     */
    private void processInput(Player player, PendingInput pending, String rawText) {
        if (System.currentTimeMillis() > pending.expiryTimeMs()) {
            player.sendMessage("§c§l✕ §cInput prompt has timed out. Please try again.");
            return;
        }

        if (rawText.equalsIgnoreCase("cancel") || rawText.equalsIgnoreCase("abort")) {
            player.sendMessage("§e§l✕ §eOperation cancelled.");
            try {
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            } catch (Throwable ignored) {}
            return;
        }

        if (rawText.isEmpty()) {
            player.sendMessage("§c§l✕ §cInput cannot be empty. Operation cancelled.");
            return;
        }

        // Dispatch callback on the main Bukkit server thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                pending.callback().accept(rawText);
            }
        });
    }

    /**
     * Cleans up pending prompt on player quit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingInputs.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Cancels any pending input targeting a specific dummy session.
     */
    public void cancelForSession(UUID sessionId) {
        if (sessionId == null) return;
        pendingInputs.values().removeIf(p -> sessionId.equals(p.sessionId()));
    }

    /**
     * Checks if a player has an active input prompt.
     */
    public boolean hasPendingInput(UUID playerUUID) {
        return pendingInputs.containsKey(playerUUID);
    }

    /**
     * Periodic task to clean up expired prompts.
     */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingInput>> it = pendingInputs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingInput> entry = it.next();
            if (now > entry.getValue().expiryTimeMs()) {
                it.remove();
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.sendMessage("§7§o[AFK Dummy] Input prompt timed out.");
                }
            }
        }
    }
}
