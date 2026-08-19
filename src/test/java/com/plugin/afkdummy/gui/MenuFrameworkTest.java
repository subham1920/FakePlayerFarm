package com.plugin.afkdummy.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("MenuFramework Tests")
class MenuFrameworkTest {

    // Concrete dummy subclass for testing
    static class TestMenu extends MenuFramework {
        public TestMenu(String title, int size) {
            super(title, size);
        }

        public void publicSetItem(int slot, ItemStack item) {
            setItem(slot, item);
        }

        public void publicSetItem(int slot, ItemStack item, java.util.function.Consumer<InventoryClickEvent> onClick) {
            setItem(slot, item, onClick);
        }

        public void publicFillEmpty(Material material) {
            fillEmpty(material);
        }

        public void publicClear() {
            clear();
        }
    }

    @Nested
    @DisplayName("Menu Behavior & Click Routing")
    class ClickRoutingTests {

        @Test
        @DisplayName("handleClick executes registered consumer and cancels event")
        void testHandleClickRouting() {
            Inventory mockInventory = mock(Inventory.class);
            when(mockInventory.getSize()).thenReturn(27);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.createInventory(any(), eq(27), any(net.kyori.adventure.text.Component.class)))
                        .thenReturn(mockInventory);

                TestMenu menu = new TestMenu("Test Menu", 27);
                assertEquals("Test Menu", menu.getTitle());
                assertEquals(mockInventory, menu.getInventory());

                AtomicBoolean clicked = new AtomicBoolean(false);
                ItemStack item = mock(ItemStack.class);
                menu.publicSetItem(10, item, e -> clicked.set(true));

                InventoryClickEvent event = mock(InventoryClickEvent.class);
                when(event.getRawSlot()).thenReturn(10);

                menu.handleClick(event);

                verify(event).setCancelled(true);
                assertTrue(clicked.get());
            }
        }

        @Test
        @DisplayName("setItem without consumer does not trigger on click")
        void testSetItemNoConsumer() {
            Inventory mockInventory = mock(Inventory.class);
            when(mockInventory.getSize()).thenReturn(27);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.createInventory(any(), eq(27), any(net.kyori.adventure.text.Component.class)))
                        .thenReturn(mockInventory);

                TestMenu menu = new TestMenu("Test Menu", 27);
                ItemStack item = mock(ItemStack.class);
                menu.publicSetItem(5, item);

                InventoryClickEvent event = mock(InventoryClickEvent.class);
                when(event.getRawSlot()).thenReturn(5);

                assertDoesNotThrow(() -> menu.handleClick(event));
                verify(event).setCancelled(true);
            }
        }

        @Test
        @DisplayName("clear clears inventory and click actions")
        void testClear() {
            Inventory mockInventory = mock(Inventory.class);
            when(mockInventory.getSize()).thenReturn(27);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.createInventory(any(), eq(27), any(net.kyori.adventure.text.Component.class)))
                        .thenReturn(mockInventory);

                TestMenu menu = new TestMenu("Test Menu", 27);
                AtomicBoolean clicked = new AtomicBoolean(false);
                menu.publicSetItem(3, mock(ItemStack.class), e -> clicked.set(true));

                menu.publicClear();
                verify(mockInventory).clear();

                InventoryClickEvent event = mock(InventoryClickEvent.class);
                when(event.getRawSlot()).thenReturn(3);

                menu.handleClick(event);
                assertFalse(clicked.get());
            }
        }

        @Test
        @DisplayName("handleClick on slot without action cancels event and does not crash")
        void testHandleClickNoAction() {
            Inventory mockInventory = mock(Inventory.class);
            when(mockInventory.getSize()).thenReturn(27);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.createInventory(any(), eq(27), any(net.kyori.adventure.text.Component.class)))
                        .thenReturn(mockInventory);

                TestMenu menu = new TestMenu("Test Menu", 27);

                InventoryClickEvent event = mock(InventoryClickEvent.class);
                when(event.getRawSlot()).thenReturn(5);

                assertDoesNotThrow(() -> menu.handleClick(event));
                verify(event).setCancelled(true);
            }
        }

        @Test
        @DisplayName("handleClick on out-of-bounds slot cancels and does not trigger action")
        void testHandleClickOutOfBounds() {
            Inventory mockInventory = mock(Inventory.class);
            when(mockInventory.getSize()).thenReturn(27);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.createInventory(any(), eq(27), any(net.kyori.adventure.text.Component.class)))
                        .thenReturn(mockInventory);

                TestMenu menu = new TestMenu("Test Menu", 27);

                InventoryClickEvent eventNeg = mock(InventoryClickEvent.class);
                when(eventNeg.getRawSlot()).thenReturn(-1);
                menu.handleClick(eventNeg);
                verify(eventNeg).setCancelled(true);

                InventoryClickEvent eventLarge = mock(InventoryClickEvent.class);
                when(eventLarge.getRawSlot()).thenReturn(30);
                menu.handleClick(eventLarge);
                verify(eventLarge).setCancelled(true);
            }
        }

        @Test
        @DisplayName("open calls player.openInventory")
        void testOpen() {
            Inventory mockInventory = mock(Inventory.class);
            when(mockInventory.getSize()).thenReturn(27);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(() -> Bukkit.createInventory(any(), eq(27), any(net.kyori.adventure.text.Component.class)))
                        .thenReturn(mockInventory);

                TestMenu menu = new TestMenu("Test Menu", 27);
                Player player = mock(Player.class);
                menu.open(player);
                verify(player).openInventory(mockInventory);
            }
        }
    }
}
