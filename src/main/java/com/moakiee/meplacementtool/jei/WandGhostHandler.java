package com.moakiee.meplacementtool.jei;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.constants.VanillaTypes;
import com.moakiee.meplacementtool.WandScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

import appeng.integration.modules.jei.GenericEntryStackHelper;
import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;

public class WandGhostHandler implements IGhostIngredientHandler<WandScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(WandScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
        List<Target<I>> targets = new ArrayList<>();

        var screen = gui;
        var menu = screen.getMenu();

        // Prefer converting JEI ingredient to AE2 GenericStack, then wrap in AE2's WrappedGenericStack item
        ItemStack stack = ItemStack.EMPTY;
        var optStack = ingredient.getItemStack();
        if (!optStack.isEmpty()) {
            stack = optStack.get();
        } else {
            var gs = GenericEntryStackHelper.ingredientToStack(ingredient.getType(), ingredient.getIngredient());
            if (gs != null) {
                stack = GenericStack.wrapInItemStack(gs.what(), Math.max(1, (int) Math.min(gs.amount(), Integer.MAX_VALUE)));
            }
        }
        if (stack == null || stack.isEmpty()) return targets;

        // create targets for the visible ghost slots (only visible slots on current page)
        var ghostSlots = menu.getGhostSlots();
        for (int i = 0; i < ghostSlots.size(); i++) {
            var slot = ghostSlots.get(i);
            // Skip slots that are off-screen (not on current page)
            if (slot.x < 0 || slot.y < 0) continue;
            
            int x = slot.x + screen.getGuiLeft();
            int y = slot.y + screen.getGuiTop();
            Rect2i area = new Rect2i(x, y, 16, 16);

            int visualIdx = i;
            // Get actual handler index based on current page
            int actualIdx = menu.getActualSlotIndex(visualIdx);
            // capture a single ItemStack to place (ensure count=1)
            ItemStack toPlace = stack.copy();
            toPlace.setCount(1);

            Target<I> t = new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I ingredient) {
                    try {
                        var handler = menu.getHandler();
                            ItemStack copy = toPlace.copy();
                            // Use actual index (accounts for page offset)
                            handler.setStackInSlot(actualIdx, copy);
                            try { var s = menu.getSlot(visualIdx); s.set(copy); } catch (Exception ignored) {}
                            CompoundTag combined = new CompoundTag();
                            combined.put("items", handler.serializeNBT());
                            CompoundTag ftag = new CompoundTag();
                            for (int ii = 0; ii < 18; ii++) {
                                String fid = menu.getFluidSlot(ii);
                                if (fid != null) ftag.putString(Integer.toString(ii), fid);
                            }
                            combined.put("fluids", ftag);
                            com.moakiee.meplacementtool.network.ModNetwork.CHANNEL.sendToServer(
                                    new com.moakiee.meplacementtool.network.UpdateWandConfigPacket(combined));
                    } catch (Exception t) {
                        // swallow to avoid JEI breaking
                    }
                }
            };

            targets.add(t);
        }

        return targets;
    }

    @Override
    public void onComplete() {
        // nothing special to do
    }

    @Override
    public boolean shouldHighlightTargets() {
        return true;
    }
}
