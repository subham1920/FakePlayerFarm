package com.plugin.afkdummy.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Utility class for inventory-based item cost transactions.
 * Provides atomic check-then-deduct operations to prevent race conditions.
 * All methods are static; this class cannot be instantiated.
 */
public final class ItemCostUtil {

    private ItemCostUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Counts the total number of items of a specific material in a player's inventory storage slots.
     *
     * @param player   the player whose inventory to scan
     * @param material the material to count
     * @return total item count across storage slots
     */
    public static int countItems(Player player, Material material) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();

        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }

        return count;
    }

    /**
     * Checks if a player has at least the specified amount of an item.
     *
     * @param player   the player to check
     * @param material the material type
     * @param required the minimum required count
     * @return true if the player has enough items
     */
    public static boolean hasEnoughItems(Player player, Material material, int required) {
        return countItems(player, material) >= required;
    }

    /**
     * Atomically verifies and removes a specified amount of items from the player's inventory.
     *
     * @param player   the player from whom to remove items
     * @param material the material type to remove
     * @param amount   the exact number of items to remove
     * @return true if the items were successfully removed, false if insufficient items
     */
    public static boolean removeItems(Player player, Material material, int amount) {
        PlayerInventory inventory = player.getInventory();

        // Atomic re-verification: check count RIGHT BEFORE removal
        if (countItems(player, material) < amount) {
            return false;
        }

        int remaining = amount;
        ItemStack[] storage = inventory.getStorageContents();

        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack item = storage[i];
            if (item == null || item.getType() != material) {
                continue;
            }

            int stackSize = item.getAmount();
            if (stackSize <= remaining) {
                remaining -= stackSize;
                storage[i] = null;
            } else {
                item.setAmount(stackSize - remaining);
                storage[i] = item;
                remaining = 0;
            }
        }

        inventory.setStorageContents(storage);
        player.updateInventory();

        return remaining == 0;
    }
}
