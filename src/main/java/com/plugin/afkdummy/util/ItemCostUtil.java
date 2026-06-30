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
     * Counts the total number of items of a specific material in a player's
     * entire inventory, including main inventory, hotbar, offhand, and armor slots.
     *
     * @param player   the player whose inventory to scan
     * @param material the material to count
     * @return total item count across all inventory slots
     */
    public static int countItems(Player player, Material material) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();

        // Scan all contents (main inventory + hotbar = slots 0-35)
        for (ItemStack item : inventory.getContents()) {
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
     * <p>
     * This method re-verifies the total item count immediately before deduction to prevent
     * race conditions where a player might drop items or modify their inventory mid-transaction.
     * If the re-verification fails, the transaction is aborted and no items are removed.
     * </p>
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
        ItemStack[] contents = inventory.getContents();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) {
                continue;
            }

            int stackSize = item.getAmount();
            if (stackSize <= remaining) {
                // Take the entire stack
                remaining -= stackSize;
                inventory.setItem(i, null);
            } else {
                // Partially reduce this stack
                item.setAmount(stackSize - remaining);
                remaining = 0;
            }
        }

        // Force client inventory update to reflect changes immediately
        player.updateInventory();

        return remaining == 0;
    }
}
