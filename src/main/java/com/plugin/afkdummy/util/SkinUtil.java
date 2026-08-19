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

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/%s";
    private static final ConcurrentHashMap<String, UUID> NAME_TO_UUID_CACHE = new ConcurrentHashMap<>();

    private SkinUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Asynchronously fetches skin data for a player by their Minecraft username.
     *
     * @param username the Minecraft username
     * @param callback consumer that receives the skin Property, or null if fetch failed
     * @param plugin   the owning plugin instance for scheduling
     */
    public static void fetchSkinByNameAsync(String username, Consumer<Property> callback, Plugin plugin) {
        if (username == null || username.trim().isEmpty()) {
            callback.accept(null);
            return;
        }

        UUID cachedUUID = NAME_TO_UUID_CACHE.get(username.toLowerCase());
        if (cachedUUID != null) {
            fetchSkinAsync(cachedUUID, callback, plugin);
            return;
        }

        if (!plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID resolved = resolveUUIDByUsername(username, plugin);
            if (resolved != null) {
                NAME_TO_UUID_CACHE.put(username.toLowerCase(), resolved);
                fetchSkinAsync(resolved, callback, plugin);
            } else {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
                }
            }
        });
    }

    /**
     * Blocking call to resolve username to UUID via Mojang API.
     */
    private static UUID resolveUUIDByUsername(String username, Plugin plugin) {
        try {
            String url = String.format(PROFILE_URL, username.trim());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                if (body != null && body.trim().startsWith("{")) {
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (json.has("id")) {
                        String idStr = json.get("id").getAsString();
                        // Format UUID with dashes (8-4-4-4-12)
                        String formatted = idStr.replaceFirst(
                                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                                "$1-$2-$3-$4-$5"
                        );
                        return UUID.fromString(formatted);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to resolve UUID for username " + username + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Asynchronously fetches skin data for a player from Mojang's session server.
     * Results are cached for subsequent calls.
     *
     * @param playerUUID the UUID of the player whose skin to fetch
     * @param callback   consumer that receives the skin Property, or null if fetch failed
     * @param plugin     the owning plugin instance for scheduling
     */
    public static void fetchSkinAsync(UUID playerUUID, Consumer<Property> callback, Plugin plugin) {
        Property cached = SKIN_CACHE.get(playerUUID);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        if (!plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Property result = fetchSkinBlocking(playerUUID, plugin);
            if (result != null) {
                SKIN_CACHE.put(playerUUID, result);
            }

            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
            }
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
            if (!json.has("properties") || !json.get("properties").isJsonArray()) {
                plugin.getLogger().warning("No properties array found in Mojang response for " + playerUUID);
                return null;
            }

            JsonArray properties = json.getAsJsonArray("properties");
            for (JsonElement element : properties) {
                if (!element.isJsonObject()) continue;
                JsonObject prop = element.getAsJsonObject();
                if (prop.has("name") && "textures".equals(prop.get("name").getAsString())) {
                    String value = prop.has("value") ? prop.get("value").getAsString() : "";
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

    private static final sun.misc.Unsafe UNSAFE;
    private static final long PROPERTY_MAP_PROPERTIES_OFFSET;

    static {
        sun.misc.Unsafe unsafe = null;
        long pmOffset = -1;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe) f.get(null);
        } catch (Throwable ignored) {}

        if (unsafe != null) {
            for (java.lang.reflect.Field field : com.mojang.authlib.properties.PropertyMap.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) &&
                        ("properties".equals(field.getName()) || com.google.common.collect.Multimap.class.isAssignableFrom(field.getType()))) {
                    try {
                        pmOffset = unsafe.objectFieldOffset(field);
                    } catch (Throwable ignored) {}
                    break;
                }
            }
        }
        UNSAFE = unsafe;
        PROPERTY_MAP_PROPERTIES_OFFSET = pmOffset;
    }

    /**
     * Safely retrieves properties from a GameProfile.
     */
    private static com.mojang.authlib.properties.PropertyMap getProperties(GameProfile profile) {
        return profile.properties();
    }

    /**
     * Applies a skin texture property to a GameProfile.
     * <p>
     * Handles immutable PropertyMap instances by updating the underlying delegate
     * multimap field inside the PropertyMap via Unsafe.
     * </p>
     */
    public static void applySkin(GameProfile profile, Property textures) {
        if (profile == null || textures == null) return;
        try {
            com.mojang.authlib.properties.PropertyMap properties = getProperties(profile);
            if (properties == null) return;

            try {
                properties.removeAll("textures");
                properties.put("textures", textures);
                return;
            } catch (UnsupportedOperationException ignored) {
                // Immutable multimap in modern AuthLib, fallback to Unsafe
            }

            com.google.common.collect.Multimap<String, Property> mutable =
                    com.google.common.collect.HashMultimap.create(properties);
            mutable.removeAll("textures");
            mutable.put("textures", textures);

            if (UNSAFE != null && PROPERTY_MAP_PROPERTIES_OFFSET != -1) {
                UNSAFE.putObject(properties, PROPERTY_MAP_PROPERTIES_OFFSET, mutable);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply skin to GameProfile", e);
        }
    }

    /**
     * Clears the internal skin cache.
     */
    public static void clearCache() {
        SKIN_CACHE.clear();
        NAME_TO_UUID_CACHE.clear();
    }
}
