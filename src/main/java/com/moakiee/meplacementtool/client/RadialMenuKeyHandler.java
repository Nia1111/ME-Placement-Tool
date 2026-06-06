package com.moakiee.meplacementtool.client;

import com.moakiee.meplacementtool.BasePlacementToolItem;
import com.moakiee.meplacementtool.ItemMECablePlacementTool;
import com.moakiee.meplacementtool.ItemMultiblockPlacementTool;
import com.moakiee.meplacementtool.network.OpenCableToolGuiPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Handler for radial menu and GUI key functionality.
 * Opens the radial menu when G is pressed while holding ME Placement Tool or Multiblock Placement Tool.
 * Opens the GUI screen when G is pressed while holding ME Cable Placement Tool.
 *
 * Uses key input events instead of tick-based checking to properly handle
 * the same key being used to both open and close the GUI.
 */
public class RadialMenuKeyHandler {

    @SubscribeEvent
    public void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Only respond to key press (not release or repeat)
        if (event.getAction() != InputConstants.PRESS) return;

        // Don't open if another screen is already open
        if (mc.screen != null) return;

        // Resolve the held placement tool from main hand first, off hand second.
        var held = BasePlacementToolItem.findHeldTool(mc.player, BasePlacementToolItem.class);
        if (held.isEmpty()) return;

        // Handle Cable Placement Tool - opens GUI directly
        if (held.getItem() instanceof ItemMECablePlacementTool) {
            if (ModKeyBindings.OPEN_CABLE_TOOL_GUI.matches(event.getKey(), event.getScanCode())) {
                // Send packet to server to open the menu
                PacketDistributor.sendToServer(new OpenCableToolGuiPayload());
                return;
            }
            // Cable tool has its own GUI; do not trigger radial menu for it.
            return;
        }

        if (!ModKeyBindings.OPEN_RADIAL_MENU.matches(event.getKey(), event.getScanCode())) {
            return;
        }

        // Open radial menu based on tool type
        if (held.getItem() instanceof ItemMultiblockPlacementTool) {
            mc.setScreen(new DualLayerRadialMenuScreen());
        } else {
            mc.setScreen(new RadialMenuScreen());
        }
    }
}
