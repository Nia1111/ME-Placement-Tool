package com.moakiee.meplacementtool.rei;

import com.moakiee.meplacementtool.GhostSlot;
import com.moakiee.meplacementtool.WandMenu;
import com.moakiee.meplacementtool.WandScreen;
import com.moakiee.meplacementtool.network.ModNetwork;
import com.moakiee.meplacementtool.network.UpdateWandConfigPacket;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.drag.*;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import dev.architectury.fluid.FluidStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Handles dragging items from REI into the WandScreen ghost slots.
 * Similar to JEI's WandGhostHandler but uses REI's DraggableStackVisitor API.
 */
public class WandGhostIngredientHandler implements DraggableStackVisitor<WandScreen> {

    @Override
    public <R extends Screen> boolean isHandingScreen(R screen) {
        return screen instanceof WandScreen;
    }

    @Override
    public Stream<BoundsProvider> getDraggableAcceptingBounds(DraggingContext<WandScreen> context, DraggableStack stack) {
        List<DropTarget> targets = getTargets(context, stack);
        return targets.stream().map(target -> BoundsProvider.ofRectangle(target.area()));
    }

    @Override
    public DraggedAcceptorResult acceptDraggedStack(DraggingContext<WandScreen> context, DraggableStack stack) {
        var targets = getTargets(context, stack);
        var pos = context.getCurrentPosition();

        for (var target : targets) {
            if (target.area().contains(pos)) {
                if (target.accept(stack)) {
                    return DraggedAcceptorResult.ACCEPTED;
                }
            }
        }

        return DraggedAcceptorResult.PASS;
    }

    /**
     * Convert REI EntryStack to ItemStack, handling both items and fluids.
     */
    private ItemStack wrapDraggedItem(EntryStack<?> entryStack) {
        if (entryStack.getType() == VanillaEntryTypes.ITEM) {
            return entryStack.castValue();
        } else if (entryStack.getType() == VanillaEntryTypes.FLUID) {
            // Handle fluids using AE2's GenericStack wrapper
            try {
                FluidStack fluidStack = entryStack.castValue();
                var wrappedFluid = new GenericStack(
                        AEFluidKey.of(fluidStack.getFluid(), fluidStack.getTag()),
                        fluidStack.getAmount());
                return GenericStack.wrapInItemStack(wrappedFluid);
            } catch (Exception ignored) {
                // If AE2 helper fails, fall through
            }
        }
        return ItemStack.EMPTY;
    }

    private List<DropTarget> getTargets(DraggingContext<WandScreen> context, DraggableStack stack) {
        ItemStack wrapped = wrapDraggedItem(stack.getStack());
        if (wrapped.isEmpty()) {
            return Collections.emptyList();
        }

        WandScreen screen = context.getScreen();
        WandMenu menu = screen.getMenu();
        List<DropTarget> targets = new ArrayList<>();

        List<GhostSlot> ghostSlots = menu.getGhostSlots();
        for (int i = 0; i < ghostSlots.size(); i++) {
            GhostSlot slot = ghostSlots.get(i);
            // Skip off-screen slots (not on current page)
            if (slot.x < 0 || slot.y < 0) continue;

            int x = slot.x + screen.getGuiLeft();
            int y = slot.y + screen.getGuiTop();
            Rectangle area = new Rectangle(x, y, 16, 16);

            int visualIdx = i;
            int actualIdx = menu.getActualSlotIndex(visualIdx);
            ItemStack toPlace = wrapped.copy();
            toPlace.setCount(1);

            targets.add(new DropTarget(area, actualIdx, visualIdx, toPlace, menu));
        }

        return targets;
    }

    /**
     * Represents a drop target slot in the WandScreen.
     */
    private record DropTarget(Rectangle area, int actualIdx, int visualIdx, 
                               ItemStack toPlace, WandMenu menu) {
        
        boolean accept(DraggableStack stack) {
            try {
                var handler = menu.getHandler();
                ItemStack copy = toPlace.copy();
                // Use actual index (accounts for page offset)
                handler.setStackInSlot(actualIdx, copy);
                try { 
                    var s = menu.getSlot(visualIdx); 
                    s.set(copy); 
                } catch (Exception ignored) {}
                
                // Sync to server
                CompoundTag combined = new CompoundTag();
                combined.put("items", handler.serializeNBT());
                CompoundTag ftag = new CompoundTag();
                for (int ii = 0; ii < 18; ii++) {
                    String fid = menu.getFluidSlot(ii);
                    if (fid != null) ftag.putString(Integer.toString(ii), fid);
                }
                combined.put("fluids", ftag);
                ModNetwork.CHANNEL.sendToServer(new UpdateWandConfigPacket(combined));
                return true;
            } catch (Exception t) {
                // Swallow to avoid REI breaking
                return false;
            }
        }
    }
}
