package com.plugin.afkdummy.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.plugin.afkdummy.AFKDummyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Asynchronous update checker for AFKDummy.
 * <p>
 * Checks whether the installed plugin version is the latest released version by
 * querying the official GitHub Releases API without blocking the server main thread.
 * </p>
 */
public class UpdateChecker {

    public static final String OFFICIAL_REPO = "subham1920/FakePlayerFarm";
    public static final String OFFICIAL_API_URL = "https://api.github.com/repos/" + OFFICIAL_REPO + "/releases/latest";
    public static final String OFFICIAL_RELEASE_URL = "https://github.com/" + OFFICIAL_REPO + "/releases/latest";

    private static final int TIMEOUT_MS = 5000;
    private static final Gson GSON = new Gson();

    private final AFKDummyPlugin plugin;
    private final String installedVersion;
    private volatile CheckResult cachedResult;

    public enum Status {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        FAILED,
        DISABLED
    }

    public record CheckResult(
            String installedVersion,
            String latestVersion,
            String downloadUrl,
            boolean updateAvailable,
            long checkedAt,
            Status status,
            String message
    ) {}

    public UpdateChecker(AFKDummyPlugin plugin) {
        this.plugin = plugin;
        this.installedVersion = plugin.getDescription().getVersion();
    }

    /**
     * Initiates an asynchronous update check if enabled in configuration.
     *
     * @return CompletableFuture resolving to the CheckResult
     */
    public CompletableFuture<CheckResult> checkForUpdatesAsync() {
        if (!plugin.getConfigManager().isUpdateCheckerEnabled()) {
            DebugLogger.log("UPDATE_CHECK status=DISABLED reason=CONFIG_DISABLED");
            CheckResult disabledResult = new CheckResult(
                    installedVersion,
                    installedVersion,
                    OFFICIAL_RELEASE_URL,
                    false,
                    System.currentTimeMillis(),
                    Status.DISABLED,
                    "Update checker disabled in configuration."
            );
            this.cachedResult = disabledResult;
            return CompletableFuture.completedFuture(disabledResult);
        }

        return CompletableFuture.supplyAsync(() -> {
            DebugLogger.log(String.format("UPDATE_CHECK_START currentVersion=%s source=GITHUB url=%s",
                    installedVersion, OFFICIAL_API_URL));

            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(OFFICIAL_API_URL).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "AFKDummy-UpdateChecker/" + installedVersion);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + responseCode + " " + conn.getResponseMessage());
                }

                JsonObject json;
                try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    json = GSON.fromJson(reader, JsonObject.class);
                }

                if (json == null || !json.has("tag_name")) {
                    throw new IllegalStateException("Invalid JSON response from GitHub API (missing tag_name)");
                }

                // Verify not draft / prerelease if flagged
                boolean isDraft = json.has("draft") && json.get("draft").getAsBoolean();
                boolean isPrerelease = json.has("prerelease") && json.get("prerelease").getAsBoolean();

                String rawTag = json.get("tag_name").getAsString();
                String latestVersion = cleanVersion(rawTag);

                String releaseUrl = OFFICIAL_RELEASE_URL;
                if (json.has("html_url") && json.get("html_url").isJsonPrimitive()) {
                    String candidateUrl = json.get("html_url").getAsString();
                    // Validate official domain
                    if (candidateUrl.startsWith("https://github.com/" + OFFICIAL_REPO)) {
                        releaseUrl = candidateUrl;
                    }
                }

                DebugLogger.log(String.format("UPDATE_CHECK_RESPONSE latestVersion=%s releaseUrl=%s draft=%b prerelease=%b",
                        latestVersion, releaseUrl, isDraft, isPrerelease));

                boolean updateAvailable = !isDraft && compareVersions(latestVersion, installedVersion) > 0;
                Status status = updateAvailable ? Status.UPDATE_AVAILABLE : Status.UP_TO_DATE;

                CheckResult result = new CheckResult(
                        installedVersion,
                        latestVersion,
                        releaseUrl,
                        updateAvailable,
                        System.currentTimeMillis(),
                        status,
                        updateAvailable ? "New version " + latestVersion + " is available!" : "Plugin is up to date."
                );

                this.cachedResult = result;

                if (updateAvailable) {
                    DebugLogger.log(String.format("UPDATE_AVAILABLE current=%s latest=%s url=%s",
                            installedVersion, latestVersion, releaseUrl));
                    logConsoleUpdateNotification(result);
                    notifyOnlinePermittedPlayers(result);
                } else {
                    DebugLogger.log(String.format("UPDATE_NOT_AVAILABLE current=%s latest=%s (up to date)",
                            installedVersion, latestVersion));
                }

                return result;
            } catch (Exception e) {
                DebugLogger.log("UPDATE_CHECK_FAILED reason=" + e.getMessage());
                plugin.getLogger().log(Level.FINE, "Failed to check for updates: " + e.getMessage());

                CheckResult failedResult = new CheckResult(
                        installedVersion,
                        installedVersion,
                        OFFICIAL_RELEASE_URL,
                        false,
                        System.currentTimeMillis(),
                        Status.FAILED,
                        "Failed to check for updates: " + e.getMessage()
                );
                this.cachedResult = failedResult;
                return failedResult;
            }
        });
    }

    /**
     * Strips leading 'v' or 'V' and trims whitespace.
     */
    public static String cleanVersion(String version) {
        if (version == null) return "0.0.0";
        return version.trim().replaceFirst("^[vV]", "");
    }

    /**
     * Compares two semantic version strings numerically.
     *
     * @param v1 first version string (e.g. "1.0.10" or "v1.0.10")
     * @param v2 second version string (e.g. "1.0.9" or "v1.0.9")
     * @return positive if v1 > v2, negative if v1 < v2, 0 if equal
     */
    public static int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;

        String clean1 = cleanVersion(v1);
        String clean2 = cleanVersion(v2);

        String[] parts1 = clean1.split("[.\\-_]");
        String[] parts2 = clean2.split("[.\\-_]");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";

            boolean isNum1 = p1.matches("\\d+");
            boolean isNum2 = p2.matches("\\d+");

            if (isNum1 && isNum2) {
                try {
                    int num1 = Integer.parseInt(p1);
                    int num2 = Integer.parseInt(p2);
                    if (num1 != num2) {
                        return Integer.compare(num1, num2);
                    }
                } catch (NumberFormatException ignored) {
                    int comp = p1.compareToIgnoreCase(p2);
                    if (comp != 0) return comp;
                }
            } else if (isNum1) {
                return 1; // pure numeric version is newer than snapshot/alpha
            } else if (isNum2) {
                return -1;
            } else {
                int comp = p1.compareToIgnoreCase(p2);
                if (comp != 0) return comp;
            }
        }
        return 0;
    }

    /**
     * Prints an update notification to the server console.
     */
    private void logConsoleUpdateNotification(CheckResult result) {
        plugin.getLogger().info("==================================================");
        plugin.getLogger().info("[AFKDummy] A new version is available!");
        plugin.getLogger().info("[AFKDummy] Current: " + result.installedVersion() + " -> Latest: " + result.latestVersion());
        plugin.getLogger().info("[AFKDummy] Download: " + result.downloadUrl());
        plugin.getLogger().info("==================================================");
    }

    /**
     * Broadcasts the update notification to all online permitted players.
     */
    public void notifyOnlinePermittedPlayers(CheckResult result) {
        if (!plugin.getConfigManager().isNotifyUpdatesEnabled()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (hasUpdatePermission(player)) {
                    sendUpdateMessage(player, result);
                }
            }
        });
    }

    /**
     * Notifies a specific player upon join if an update is cached and available.
     */
    public void notifyPlayerIfUpdateAvailable(Player player) {
        if (cachedResult == null || !cachedResult.updateAvailable()) return;
        if (!plugin.getConfigManager().isNotifyUpdatesEnabled()) return;
        if (!hasUpdatePermission(player)) return;

        sendUpdateMessage(player, cachedResult);
    }

    private boolean hasUpdatePermission(Player player) {
        return player.hasPermission("afkdummy.update") || player.hasPermission("afkdummy.admin");
    }

    /**
     * Sends a rich Adventure component message with a clickable download link.
     */
    public void sendUpdateMessage(Player player, CheckResult result) {
        Component message = Component.text("[AFKDummy] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("A new version is available! ", NamedTextColor.YELLOW))
                .append(Component.text("Current: ", NamedTextColor.GRAY))
                .append(Component.text(result.installedVersion(), NamedTextColor.RED))
                .append(Component.text(" | Latest: ", NamedTextColor.GRAY))
                .append(Component.text(result.latestVersion(), NamedTextColor.GREEN))
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text("[Click to Download]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(result.downloadUrl()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open official release download: " + result.downloadUrl(), NamedTextColor.GRAY))));

        player.sendMessage(message);
    }

    /**
     * Gets the latest cached update check result, if any.
     */
    public CheckResult getCachedResult() {
        return cachedResult;
    }

    /**
     * Gets the installed plugin version.
     */
    public String getInstalledVersion() {
        return installedVersion;
    }
}
