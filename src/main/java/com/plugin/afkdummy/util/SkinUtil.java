package com.plugin.afkdummy.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Utility class for fetching and applying Minecraft player skins
 * from the Mojang session server API.
 * <p>
 * Skin data is cached in memory to avoid redundant API calls.
 * All network operations run asynchronously off the main server thread.
 * </p>
 */
public final class SkinUtil {

    private static final String SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ConcurrentHashMap<UUID, Property> SKIN_CACHE = new ConcurrentHashMap<>();

    private SkinUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Asynchronously fetches skin data for a player from Mojang's session server.
     * Results are cached for subsequent calls. The callback is invoked on the
     * main server thread.
     *
     * @param playerUUID the UUID of the player whose skin to fetch
     * @param callback   consumer that receives the skin Property, or null if fetch failed
     * @param plugin     the owning plugin instance for scheduling
     */
    public static void fetchSkinAsync(UUID playerUUID, Consumer<Property> callback, Plugin plugin) {
        // Check cache first
        Property cached = SKIN_CACHE.get(playerUUID);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        // Run async to avoid blocking main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Property result = fetchSkinBlocking(playerUUID, plugin);
            if (result != null) {
                SKIN_CACHE.put(playerUUID, result);
            }

            // Callback on main thread
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    /**
     * Blocking skin fetch from Mojang API. Should only be called from async context.
     */
    private static Property fetchSkinBlocking(UUID playerUUID, Plugin plugin) {
        try {
            String uuidNoDashes = playerUUID.toString().replace("-", "");
            String url = String.format(SESSION_URL, uuidNoDashes);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                plugin.getLogger().warning("Mojang API rate limit hit while fetching skin for " + playerUUID + ". Retrying later.");
                return null;
            }

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("Mojang API returned status " + response.statusCode()
                        + " for UUID " + playerUUID);
                return null;
            }

            String body = response.body();
            if (body == null || !body.trim().startsWith("{")) {
                plugin.getLogger().warning("Mojang API returned invalid response for UUID " + playerUUID);
                return null;
            }

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray properties = json.getAsJsonArray("properties");

            for (JsonElement element : properties) {
                JsonObject prop = element.getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString())) {
                    String value = prop.get("value").getAsString();
                    String signature = prop.has("signature")
                            ? prop.get("signature").getAsString()
                            : "";
                    return new Property("textures", value, signature);
                }
            }

            plugin.getLogger().warning("No textures property found for UUID " + playerUUID);
            return null;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to fetch skin for UUID " + playerUUID + ": " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Safely retrieves properties from a GameProfile using reflection
     * to support both older class-based and newer record-based authlib versions.
     */
    private static com.mojang.authlib.properties.PropertyMap getProperties(GameProfile profile) {
        try {
            try {
                return (com.mojang.authlib.properties.PropertyMap) GameProfile.class.getMethod("properties").invoke(profile);
            } catch (NoSuchMethodException e) {
                return (com.mojang.authlib.properties.PropertyMap) GameProfile.class.getMethod("getProperties").invoke(profile);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to access GameProfile properties", e);
        }
    }

    /**
     * Applies a skin texture property to a GameProfile.
     * Removes any existing "textures" property before applying the new one.
     *
     * @param profile  the GameProfile to modify
     * @param textures the skin Property to apply
     */
    public static void applySkin(GameProfile profile, Property textures) {
        if (profile == null || textures == null) return;
        com.mojang.authlib.properties.PropertyMap properties = getProperties(profile);
        properties.removeAll("textures");
        properties.put("textures", textures);
    }

    /**
     * Clears the internal skin cache.
     */
    public static void clearCache() {
        SKIN_CACHE.clear();
    }
}
