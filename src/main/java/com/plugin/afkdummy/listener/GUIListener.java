package com.plugin.afkdummy.listener;

import com.plugin.afkdummy.gui.MenuFramework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;

/**
 * Event listener for the custom GUI system.
 * <p>
 * Handles all inventory interaction events for menus extending {@link MenuFramework}.
 * Provides comprehensive exploit protection by cancelling all unauthorized
 * item movements within custom menus.
 * </p>
 */
public class GUIListener implements Listener {

    /**
     * Handles click events in custom menus.
     * Identifies MenuFramework holders, cancels the event to prevent item extraction,
     * and routes the click to the appropriate menu handler.
     *
     * @param event the InventoryClickEvent
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();

        if (topInventory.getHolder() instanceof MenuFramework menu) {
            // Cancel the event unconditionally to prevent any item movement
            event.setCancelled(true);

            // Route the click to the menu's handler
            menu.handleClick(event);
        }
    }

    /**
     * Prevents item dragging in custom menus.
     * This prevents a common exploit where players drag items into/out of custom GUIs.
     *
     * @param event the InventoryDragEvent
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();

        if (topInventory.getHolder() instanceof MenuFramework) {
            // Check if any of the dragged slots are in the custom menu
            int menuSize = topInventory.getSize();
            for (int slot : event.getRawSlots()) {
                if (slot < menuSize) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    /**
     * Prevents hopper/dropper item movement into custom menus.
     * This prevents automated item extraction from custom GUI inventories.
     *
     * @param event the InventoryMoveItemEvent
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof MenuFramework
                || event.getDestination().getHolder() instanceof MenuFramework) {
            event.setCancelled(true);
        }
    }
}
