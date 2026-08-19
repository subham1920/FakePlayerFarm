package com.plugin.afkdummy.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ItemCostUtil Tests")
class ItemCostUtilTest {

    private Player player;
    private PlayerInventory inventory;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
    }

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException")
    void testConstructorThrows() throws Exception {
        Constructor<ItemCostUtil> constructor = ItemCostUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
    }

    private ItemStack mockItem(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        final int[] amt = new int[]{amount};
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenAnswer(inv -> amt[0]);
        doAnswer(inv -> {
            amt[0] = inv.getArgument(0);
            return null;
        }).when(item).setAmount(anyInt());
        return item;
    }

    @Nested
    @DisplayName("countItems(Player player, Material material)")
    class CountItemsTests {

        @Test
        @DisplayName("Empty inventory returns 0")
        void testEmptyInventory() {
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
            assertEquals(0, ItemCostUtil.countItems(player, Material.DIAMOND));
        }

        @Test
        @DisplayName("Single stack with matching item returns stack amount")
        void testSingleStack() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 10);
            when(inventory.getStorageContents()).thenReturn(contents);

            assertEquals(10, ItemCostUtil.countItems(player, Material.DIAMOND));
            assertEquals(0, ItemCostUtil.countItems(player, Material.EMERALD));
        }

        @Test
        @DisplayName("Multiple stacks with matching item sums amounts correctly")
        void testMultipleStacks() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 64);
            contents[5] = mockItem(Material.DIAMOND, 32);
            contents[10] = mockItem(Material.GOLD_INGOT, 16);
            contents[20] = mockItem(Material.DIAMOND, 4);
            when(inventory.getStorageContents()).thenReturn(contents);

            assertEquals(100, ItemCostUtil.countItems(player, Material.DIAMOND));
            assertEquals(16, ItemCostUtil.countItems(player, Material.GOLD_INGOT));
            assertEquals(0, ItemCostUtil.countItems(player, Material.IRON_INGOT));
        }

        @Test
        @DisplayName("Inventory with all null slots returns 0")
        void testAllNullSlots() {
            ItemStack[] contents = new ItemStack[36];
            when(inventory.getStorageContents()).thenReturn(contents);
            assertEquals(0, ItemCostUtil.countItems(player, Material.DIAMOND));
        }

        @ParameterizedTest(name = "Stack amount = {0}")
        @ValueSource(ints = {1, 2, 5, 10, 16, 32, 64})
        void testVariousStackSizes(int amount) {
            ItemStack[] contents = new ItemStack[36];
            contents[3] = mockItem(Material.EMERALD, amount);
            when(inventory.getStorageContents()).thenReturn(contents);

            assertEquals(amount, ItemCostUtil.countItems(player, Material.EMERALD));
        }

        @Test
        @DisplayName("Test 36 slots filled with 1 item each")
        void testManySlots() {
            ItemStack[] contents = new ItemStack[36];
            for (int i = 0; i < 36; i++) {
                contents[i] = mockItem(Material.DIAMOND, 1);
            }
            when(inventory.getStorageContents()).thenReturn(contents);
            assertEquals(36, ItemCostUtil.countItems(player, Material.DIAMOND));
        }
    }

    @Nested
    @DisplayName("hasEnoughItems(Player player, Material material, int required)")
    class HasEnoughItemsTests {

        @Test
        @DisplayName("hasEnoughItems returns true when count >= required")
        void testHasEnough() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 10);
            when(inventory.getStorageContents()).thenReturn(contents);

            assertTrue(ItemCostUtil.hasEnoughItems(player, Material.DIAMOND, 5));
            assertTrue(ItemCostUtil.hasEnoughItems(player, Material.DIAMOND, 10));
            assertTrue(ItemCostUtil.hasEnoughItems(player, Material.DIAMOND, 0));
            assertFalse(ItemCostUtil.hasEnoughItems(player, Material.DIAMOND, 11));
            assertFalse(ItemCostUtil.hasEnoughItems(player, Material.EMERALD, 1));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 5, 10, 20, 50, 100})
        void testHasEnoughParametrized(int req) {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, req);
            when(inventory.getStorageContents()).thenReturn(contents);

            assertTrue(ItemCostUtil.hasEnoughItems(player, Material.DIAMOND, req));
            assertTrue(ItemCostUtil.hasEnoughItems(player, Material.DIAMOND, req - 1));
            assertFalse(ItemCostUtil.hasEnoughItems(player, Material.DIAMOND, req + 1));
        }
    }

    @Nested
    @DisplayName("removeItems(Player player, Material material, int amount)")
    class RemoveItemsTests {

        @Test
        @DisplayName("removeItems fails if player does not have enough items")
        void testRemoveInsufficient() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 5);
            when(inventory.getStorageContents()).thenReturn(contents);

            boolean result = ItemCostUtil.removeItems(player, Material.DIAMOND, 10);
            assertFalse(result);
            verify(player, never()).updateInventory();
        }

        @Test
        @DisplayName("removeItems removes exact amount from single stack")
        void testRemoveExactSingleStack() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 10);
            when(inventory.getStorageContents()).thenReturn(contents);

            boolean result = ItemCostUtil.removeItems(player, Material.DIAMOND, 10);
            assertTrue(result);
            assertNull(contents[0]);
            verify(inventory).setStorageContents(contents);
            verify(player).updateInventory();
        }

        @Test
        @DisplayName("removeItems removes partial amount from single stack")
        void testRemovePartialSingleStack() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 10);
            when(inventory.getStorageContents()).thenReturn(contents);

            boolean result = ItemCostUtil.removeItems(player, Material.DIAMOND, 4);
            assertTrue(result);
            assertNotNull(contents[0]);
            assertEquals(6, contents[0].getAmount());
            verify(inventory).setStorageContents(contents);
            verify(player).updateInventory();
        }

        @Test
        @DisplayName("removeItems removes across multiple stacks")
        void testRemoveAcrossMultipleStacks() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 5);
            contents[1] = mockItem(Material.GOLD_INGOT, 10);
            contents[2] = mockItem(Material.DIAMOND, 10);
            when(inventory.getStorageContents()).thenReturn(contents);

            boolean result = ItemCostUtil.removeItems(player, Material.DIAMOND, 12);
            assertTrue(result);
            assertNull(contents[0]);
            assertEquals(10, contents[1].getAmount());
            assertNotNull(contents[2]);
            assertEquals(3, contents[2].getAmount());
            verify(inventory).setStorageContents(contents);
            verify(player).updateInventory();
        }

        @Test
        @DisplayName("removeItems with 0 amount succeeds")
        void testRemoveZero() {
            ItemStack[] contents = new ItemStack[36];
            contents[0] = mockItem(Material.DIAMOND, 5);
            when(inventory.getStorageContents()).thenReturn(contents);

            boolean result = ItemCostUtil.removeItems(player, Material.DIAMOND, 0);
            assertTrue(result);
            assertEquals(5, contents[0].getAmount());
        }
    }
}
