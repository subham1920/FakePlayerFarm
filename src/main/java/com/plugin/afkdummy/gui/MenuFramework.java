package com.plugin.afkdummy.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Abstract framework for creating exploit-proof, interactive Chest GUI menus.
 * <p>
 * Provides a clean slot-to-action mapping system, automatic click event routing,
 * and built-in protection against item duplication and extraction exploits.
 * </p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * public class MyMenu extends MenuFramework {
 *     public MyMenu() {
 *         super("My Menu", 27);
 *         setItem(13, createItem(Material.DIAMOND, "§bClick Me", "§7Does something"), event -> {
 *             event.getWhoClicked().sendMessage("Clicked!");
 *         });
 *     }
 * }
 * </pre>
 */
public abstract class MenuFramework implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> clickActions;
    private final String title;

    /**
     * Creates a new menu with the specified title and size.
     *
     * @param title the inventory title shown to the player
     * @param size  the inventory size (must be a multiple of 9, max 54)
     */
    protected MenuFramework(String title, int size) {
        this.title = title;
        this.clickActions = new HashMap<>();
        this.inventory = Bukkit.createInventory(this, size,
                net.kyori.adventure.text.Component.text(title));
    }

    /**
     * Places an item in a specific slot with a click handler.
     *
     * @param slot    the inventory slot (0-indexed)
     * @param item    the ItemStack to display
     * @param onClick the action to execute when this slot is clicked
     */
    protected void setItem(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inventory.setItem(slot, item);
        if (onClick != null) {
            clickActions.put(slot, onClick);
        }
    }

    /**
     * Places a decorative (non-clickable) item in a specific slot.
     *
     * @param slot the inventory slot
     * @param item the ItemStack to display
     */
    protected void setItem(int slot, ItemStack item) {
        setItem(slot, item, null);
    }

    /**
     * Fills empty slots with a glass pane border/background.
     *
     * @param material the glass pane material to use
     */
    protected void fillEmpty(Material material) {
        ItemStack filler = createItem(material, " ");
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    /**
     * Creates a styled ItemStack with a display name and lore lines.
     *
     * @param material the material type
     * @param name     the display name (supports § color codes)
     * @param lore     optional lore lines
     * @return the constructed ItemStack
     */
    protected static ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(name));
            if (lore.length > 0) {
                meta.lore(Arrays.stream(lore)
                        .map(net.kyori.adventure.text.Component::text)
                        .map(c -> (net.kyori.adventure.text.Component) c)
                        .toList());
            }
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates a styled ItemStack with a display name and a pre-built lore list.
     *
     * @param material the material type
     * @param name     the display name
     * @param lore     list of lore components
     * @return the constructed ItemStack
     */
    protected static ItemStack createItem(Material material, String name,
                                          List<net.kyori.adventure.text.Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(name));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates a styled ItemStack with a custom stack count.
     *
     * @param material the material type
     * @param amount   the stack size
     * @param name     the display name
     * @param lore     optional lore lines
     * @return the constructed ItemStack
     */
    protected static ItemStack createItem(Material material, int amount, String name, String... lore) {
        ItemStack item = createItem(material, name, lore);
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return item;
    }

    /**
     * Handles a click event for this menu.
     * Routes the click to the registered action for the clicked slot.
     *
     * @param event the InventoryClickEvent
     */
    public void handleClick(InventoryClickEvent event) {
        // Always cancel to prevent item extraction
        event.setCancelled(true);

        int slot = event.getRawSlot();

        // Only process clicks within our inventory (not the player's inventory)
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        Consumer<InventoryClickEvent> action = clickActions.get(slot);
        if (action != null) {
            action.accept(event);
        }
    }

    /**
     * Opens this menu for a player.
     *
     * @param player the player to open the menu for
     */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Gets the title of this menu.
     *
     * @return the menu title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Clears all items and click actions.
     */
    protected void clear() {
        inventory.clear();
        clickActions.clear();
    }
}
