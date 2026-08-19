package com.plugin.afkdummy.listener;

import com.plugin.afkdummy.gui.MenuFramework;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("GUIListener Tests")
class GUIListenerTest {

    private GUIListener listener;
    private MenuFramework mockMenu;
    private Inventory mockMenuInventory;
    private InventoryView mockView;

    @BeforeEach
    void setUp() {
        listener = new GUIListener();
        mockMenu = mock(MenuFramework.class);
        mockMenuInventory = mock(Inventory.class);
        when(mockMenuInventory.getHolder()).thenReturn(mockMenu);
        when(mockMenuInventory.getSize()).thenReturn(27);

        mockView = mock(InventoryView.class);
        when(mockView.getTopInventory()).thenReturn(mockMenuInventory);
    }

    @Test
    @DisplayName("onInventoryClick on MenuFramework cancels event and routes to handleClick")
    void testClickOnMenuFramework() {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(mockView);

        listener.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(mockMenu).handleClick(event);
    }

    @Test
    @DisplayName("onInventoryClick on non-MenuFramework inventory does not cancel")
    void testClickOnStandardInventory() {
        Inventory standardInv = mock(Inventory.class);
        when(standardInv.getHolder()).thenReturn(mock(InventoryHolder.class));
        when(mockView.getTopInventory()).thenReturn(standardInv);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(mockView);

        listener.onInventoryClick(event);

        verify(event, never()).setCancelled(true);
        verify(mockMenu, never()).handleClick(any());
    }

    @Test
    @DisplayName("onInventoryDrag with slots in menu cancels event")
    void testDragIntoMenu() {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(mockView);
        when(event.getRawSlots()).thenReturn(Set.of(5, 10, 30));

        listener.onInventoryDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("onInventoryDrag with slots only outside menu does not cancel")
    void testDragOutsideMenu() {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(mockView);
        when(event.getRawSlots()).thenReturn(Set.of(27, 28, 35));

        listener.onInventoryDrag(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("onInventoryMoveItem cancels when source or destination is MenuFramework")
    void testMoveItem() {
        Inventory srcMenu = mock(Inventory.class);
        when(srcMenu.getHolder()).thenReturn(mockMenu);
        Inventory destNormal = mock(Inventory.class);
        when(destNormal.getHolder()).thenReturn(mock(InventoryHolder.class));

        InventoryMoveItemEvent event1 = mock(InventoryMoveItemEvent.class);
        when(event1.getSource()).thenReturn(srcMenu);
        when(event1.getDestination()).thenReturn(destNormal);

        listener.onInventoryMoveItem(event1);
        verify(event1).setCancelled(true);

        InventoryMoveItemEvent event2 = mock(InventoryMoveItemEvent.class);
        when(event2.getSource()).thenReturn(destNormal);
        when(event2.getDestination()).thenReturn(srcMenu);

        listener.onInventoryMoveItem(event2);
        verify(event2).setCancelled(true);
    }
}
