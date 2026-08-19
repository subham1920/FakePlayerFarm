package com.plugin.afkdummy.listener;

import com.plugin.afkdummy.AFKDummyPlugin;
import com.plugin.afkdummy.entity.DummyManager;
import com.plugin.afkdummy.entity.DummySession;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PlayerListener Tests")
class PlayerListenerTest {

    private AFKDummyPlugin plugin;
    private DummyManager dummyManager;
    private PlayerListener listener;
    private Player dummyPlayer;
    private Player realPlayer;

    @BeforeEach
    void setUp() {
        plugin = mock(AFKDummyPlugin.class);
        dummyManager = mock(DummyManager.class);
        when(plugin.getDummyManager()).thenReturn(dummyManager);

        listener = new PlayerListener(plugin);

        dummyPlayer = mock(Player.class);
        realPlayer = mock(Player.class);

        when(dummyManager.isDummyPlayer(dummyPlayer)).thenReturn(true);
        when(dummyManager.isDummyPlayer(realPlayer)).thenReturn(false);
    }

    @Nested
    @DisplayName("Damage Prevention")
    class DamagePreventionTests {

        @Test
        @DisplayName("onEntityDamage cancels for dummy player")
        void testDamageDummy() {
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(dummyPlayer);

            listener.onEntityDamage(event);
            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onEntityDamage does not cancel for real player")
        void testDamageRealPlayer() {
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(realPlayer);

            listener.onEntityDamage(event);
            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("onEntityDamageByEntity cancels for dummy player")
        void testDamageByEntityDummy() {
            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getEntity()).thenReturn(dummyPlayer);

            listener.onEntityDamageByEntity(event);
            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("Entity Interaction")
    class InteractionTests {

        @Test
        @DisplayName("onPlayerInteractEntity ignores offhand click")
        void testOffhandIgnored() {
            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);

            listener.onPlayerInteractEntity(event);
            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("onPlayerInteractEntity cancels for dummy")
        void testInteractWithDummy() {
            Player clicker = mock(Player.class);
            UUID ownerUUID = UUID.randomUUID();
            when(clicker.getUniqueId()).thenReturn(UUID.randomUUID()); // not owner

            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(event.getRightClicked()).thenReturn(dummyPlayer);
            when(event.getPlayer()).thenReturn(clicker);

            DummySession session = mock(DummySession.class);
            when(session.getOwnerUUID()).thenReturn(ownerUUID);
            when(dummyManager.getSessionByPlayer(dummyPlayer)).thenReturn(Optional.of(session));

            listener.onPlayerInteractEntity(event);
            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("Target & Knockback Prevention")
    class TargetAndKnockbackTests {

        @Test
        @DisplayName("onEntityTarget cancels when target is dummy")
        void testTargetDummy() {
            EntityTargetEvent event = mock(EntityTargetEvent.class);
            when(event.getTarget()).thenReturn(dummyPlayer);

            listener.onEntityTarget(event);
            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onEntityTargetLivingEntity cancels when target is dummy")
        void testTargetLivingDummy() {
            EntityTargetLivingEntityEvent event = mock(EntityTargetLivingEntityEvent.class);
            when(event.getTarget()).thenReturn(dummyPlayer);

            listener.onEntityTargetLivingEntity(event);
            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onEntityKnockback cancels for dummy")
        void testKnockbackDummy() {
            EntityKnockbackEvent event = mock(EntityKnockbackEvent.class);
            when(event.getEntity()).thenReturn(dummyPlayer);

            listener.onEntityKnockback(event);
            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onProjectileHit cancels when hit entity is dummy")
        void testProjectileHitDummy() {
            ProjectileHitEvent event = mock(ProjectileHitEvent.class);
            when(event.getHitEntity()).thenReturn(dummyPlayer);

            listener.onProjectileHit(event);
            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("Vehicles & Portals")
    class VehicleAndPortalTests {

        @Test
        @DisplayName("onVehicleEnter cancels for dummy")
        void testVehicleEnter() {
            VehicleEnterEvent event = mock(VehicleEnterEvent.class);
            when(event.getEntered()).thenReturn(dummyPlayer);

            listener.onVehicleEnter(event);
            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("onEntityPortal and onPlayerPortal cancel for dummy")
        void testPortals() {
            EntityPortalEvent eEvent = mock(EntityPortalEvent.class);
            when(eEvent.getEntity()).thenReturn(dummyPlayer);
            listener.onEntityPortal(eEvent);
            verify(eEvent).setCancelled(true);

            PlayerPortalEvent pEvent = mock(PlayerPortalEvent.class);
            when(pEvent.getPlayer()).thenReturn(dummyPlayer);
            listener.onPlayerPortal(pEvent);
            verify(pEvent).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("World Unload")
    class WorldUnloadTests {

        @Test
        @DisplayName("onWorldUnload calls dummyManager.handleWorldUnload")
        void testWorldUnload() {
            World mockWorld = mock(World.class);
            when(mockWorld.getName()).thenReturn("custom_world");

            WorldUnloadEvent event = mock(WorldUnloadEvent.class);
            when(event.getWorld()).thenReturn(mockWorld);

            listener.onWorldUnload(event);
            verify(dummyManager).handleWorldUnload("custom_world");
        }
    }
}
