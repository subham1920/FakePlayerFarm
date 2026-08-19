package com.plugin.afkdummy.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ImmutableMultimap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SkinUtil Tests")
class SkinUtilTest {

    @BeforeEach
    void setUp() {
        SkinUtil.clearCache();
    }

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException")
    void testConstructorThrows() throws Exception {
        Constructor<SkinUtil> constructor = SkinUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    @DisplayName("clearCache does not throw")
    void testClearCache() {
        assertDoesNotThrow(SkinUtil::clearCache);
    }

    @Test
    @DisplayName("applySkin with null profile or null texture does not throw")
    void testApplySkinNulls() {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TestPlayer");
        Property prop = new Property("textures", "value123", "sig123");

        assertDoesNotThrow(() -> SkinUtil.applySkin(null, prop));
        assertDoesNotThrow(() -> SkinUtil.applySkin(profile, null));
        assertDoesNotThrow(() -> SkinUtil.applySkin(null, null));
    }

    @Test
    @DisplayName("applySkin applies skin texture on GameProfile with standard PropertyMap")
    void testApplySkinStandard() {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TestPlayer");
        Property texture = new Property("textures", "base64TextureValue", "signatureValue");

        SkinUtil.applySkin(profile, texture);

        PropertyMap properties = profile.properties();
        assertNotNull(properties);
        assertTrue(properties.containsKey("textures"));
        assertEquals("base64TextureValue", properties.get("textures").iterator().next().value());
    }

    @Test
    @DisplayName("applySkin handles immutable PropertyMap without throwing UnsupportedOperationException")
    void testApplySkinImmutableMultimap() {
        Property customProp = new Property("custom_key", "custom_val", "custom_sig");
        Property oldTexture = new Property("textures", "oldTexture", "oldSig");
        
        ImmutableMultimap<String, Property> immutableMultimap = ImmutableMultimap.<String, Property>builder()
                .put("custom_key", customProp)
                .put("textures", oldTexture)
                .build();
                
        PropertyMap immutableMap = new PropertyMap(immutableMultimap);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TestPlayer", immutableMap);

        // Apply new skin
        Property newTexture = new Property("textures", "newTextureValue", "newSignatureValue");
        assertDoesNotThrow(() -> SkinUtil.applySkin(profile, newTexture));

        // Verify textures updated and custom properties preserved
        PropertyMap updated = profile.properties();
        assertTrue(updated.containsKey("textures"));
        assertEquals("newTextureValue", updated.get("textures").iterator().next().value());
        assertTrue(updated.containsKey("custom_key"));
        assertEquals("custom_val", updated.get("custom_key").iterator().next().value());
    }

    @Test
    @DisplayName("Repeated skin applications correctly override previous skin")
    void testMultipleSkinApplications() {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TestPlayer");
        
        for (int i = 1; i <= 10; i++) {
            Property texture = new Property("textures", "texture_" + i, "sig_" + i);
            SkinUtil.applySkin(profile, texture);
            PropertyMap properties = profile.properties();
            assertEquals("texture_" + i, properties.get("textures").iterator().next().value());
        }
    }
}
