package com.moakiee.meplacementtool;

import java.util.List;
import java.util.function.DoubleSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import appeng.api.config.Actionable;
import appeng.api.features.IGridLinkableHandler;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.core.localization.GuiText;
import appeng.core.localization.PlayerMessages;
import appeng.core.localization.Tooltips;
import appeng.items.tools.powered.powersink.AEBasePoweredItem;
import appeng.util.Platform;

/**
 * Base class for placement tools that need AE network connection and power,
 * but should NOT be recognized as a WirelessTerminalItem by other mods.
 * 
 * This extends AEBasePoweredItem directly instead of WirelessTerminalItem,
 * so other mods checking instanceof WirelessTerminalItem will not match.
 */
public abstract class BasePlacementToolItem extends AEBasePoweredItem {
    
    public static final IGridLinkableHandler LINKABLE_HANDLER = new LinkableHandler();
    
    public BasePlacementToolItem(DoubleSupplier powerCapacity, Item.Properties props) {
        super(powerCapacity, props);
    }

    /**
     * Find a held tool of the given class on the player.
     * Prefers the main hand; falls back to off hand.
     *
     * @return the matching ItemStack, or {@link ItemStack#EMPTY} if neither hand holds an instance.
     */
    public static ItemStack findHeldTool(Player player, Class<?> toolClass) {
        if (player == null) return ItemStack.EMPTY;
        ItemStack main = player.getMainHandItem();
        if (toolClass.isInstance(main.getItem())) return main;
        ItemStack off = player.getOffhandItem();
        if (toolClass.isInstance(off.getItem())) return off;
        return ItemStack.EMPTY;
    }
    
    @Override
    @OnlyIn(Dist.CLIENT)
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag advancedTooltips) {
        super.appendHoverText(stack, context, lines, advancedTooltips);
        
        // Show linked/unlinked status like WirelessTerminalItem
        if (getLinkedPosition(stack) == null) {
            lines.add(Tooltips.of(GuiText.Unlinked, Tooltips.RED));
        } else {
            lines.add(Tooltips.of(GuiText.Linked, Tooltips.GREEN));
        }
    }
    
    /**
     * Gets the position of the wireless access point that this tool is linked to.
     */
    @Nullable
    public GlobalPos getLinkedPosition(ItemStack item) {
        return item.get(ModDataComponents.LINKED_POSITION.get());
    }
    
    /**
     * Gets the AE grid that this tool is linked to.
     * 
     * @param item The tool item stack
     * @param level The current level
     * @param sendMessagesTo Optional player to send error messages to
     * @return The linked grid, or null if not linked or unavailable
     */
    @Nullable
    public IGrid getLinkedGrid(ItemStack item, net.minecraft.world.level.Level level, @Nullable Player sendMessagesTo) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        var pos = getLinkedPosition(item);
        if (pos == null) {
            if (sendMessagesTo != null) {
                sendMessagesTo.displayClientMessage(PlayerMessages.DeviceNotLinked.text(), true);
            }
            return null;
        }

        var linkedLevel = serverLevel.getServer().getLevel(pos.dimension());
        if (linkedLevel == null) {
            if (sendMessagesTo != null) {
                sendMessagesTo.displayClientMessage(PlayerMessages.LinkedNetworkNotFound.text(), true);
            }
            return null;
        }

        var be = Platform.getTickingBlockEntity(linkedLevel, pos.pos());
        if (!(be instanceof IWirelessAccessPoint accessPoint)) {
            if (sendMessagesTo != null) {
                sendMessagesTo.displayClientMessage(PlayerMessages.LinkedNetworkNotFound.text(), true);
            }
            return null;
        }

        var grid = accessPoint.getGrid();
        if (grid == null) {
            if (sendMessagesTo != null) {
                sendMessagesTo.displayClientMessage(PlayerMessages.LinkedNetworkNotFound.text(), true);
            }
        }
        return grid;
    }
    
    /**
     * Use an amount of power, in AE units.
     *
     * @param player The player using the tool
     * @param amount Power amount in AE units (5 per MJ)
     * @param is The tool item stack
     * @return true if power was successfully consumed
     */
    public boolean usePower(Player player, double amount, ItemStack is) {
        return extractAEPower(is, amount, Actionable.MODULATE) >= amount - 0.5;
    }
    
    /**
     * Gets the power status of the item.
     *
     * @param player The player holding the tool
     * @param amt The minimum power required
     * @param is The tool item stack
     * @return true if there is enough power left
     */
    public boolean hasPower(Player player, double amt, ItemStack is) {
        return getAECurrentPower(is) >= amt;
    }
    
    /**
     * Gets the charge rate for this item when being charged in an AE charger.
     *
     * @param stack The item stack
     * @return The charge rate in AE units per tick
     */
    @Override
    public double getChargeRate(ItemStack stack) {
        return 800d; // Same as WirelessTerminalItem base rate
    }
    
    /**
     * Handler for linking/unlinking the tool to wireless access points.
     */
    private static class LinkableHandler implements IGridLinkableHandler {
        @Override
        public boolean canLink(ItemStack stack) {
            return stack.getItem() instanceof BasePlacementToolItem;
        }

        @Override
        public void link(ItemStack itemStack, GlobalPos pos) {
            itemStack.set(ModDataComponents.LINKED_POSITION.get(), pos);
        }

        @Override
        public void unlink(ItemStack itemStack) {
            itemStack.remove(ModDataComponents.LINKED_POSITION.get());
        }
    }
}
