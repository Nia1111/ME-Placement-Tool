package com.moakiee.meplacementtool;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.function.DoubleSupplier;
import java.util.function.BiConsumer;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.menu.ISubMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;


/**
 * ME Placement Tool - extends BasePlacementToolItem to avoid being recognized as WirelessTerminalItem
 */
public class ItemMEPlacementTool extends BasePlacementToolItem implements IMenuItem {
    private static final Logger LOGGER = LogUtils.getLogger();

    public ItemMEPlacementTool(Item.Properties props) {
        super(() -> Config.mePlacementToolEnergyCapacity, props);
    }

    @Override
    public ItemMenuHost getMenuHost(Player player, int inventorySlot, ItemStack itemStack, BlockPos pos) {
        return new PlacementToolMenuHost(player, inventorySlot, itemStack, (p, subMenu) -> {
            // Close the menu directly instead of returning to a main menu
            p.closeContainer();
        });
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }

        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        ItemStack wand = player.getItemInHand(context.getHand());

        // energy cost per placement
        final double ENERGY_COST = Config.mePlacementToolEnergyCost;

        // check power
        if (!this.hasPower(player, ENERGY_COST, wand)) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.device_not_powered"), true);
            return InteractionResult.FAIL;
        }

        // get linked grid
        var grid = this.getLinkedGrid(wand, level, player);
        if (grid == null) {
            // getLinkedGrid already notifies player
            return InteractionResult.FAIL;
        }

        // read config NBT from item
        CompoundTag cfg = WandNbt.getConfig(wand);
        int selected = WandNbt.getSelectedSlot(cfg);
        var handler = WandNbt.readInventory(cfg);

        ItemStack target = handler.getStackInSlot(selected);
        if (target == null || target.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.no_configured_item"), true);
            return InteractionResult.FAIL;
        }

        // convert to AEItemKey
        // Prepare AE storage/source for later use
        var storage = grid.getStorageService().getInventory();
        var src = new appeng.me.helpers.PlayerSource(player);

        // Placement tracking (used for rollback/logging and memory card application)
        BlockPos lastPlacementPos = null;
        boolean lastPlacementWasBlock = false;
        net.minecraft.core.Direction lastPlacementSide = null;
        appeng.api.parts.IPart lastPlacedPart = null;

        // First: detect if the target is an AE wrapped GenericStack representing a fluid
        try {
            var unwrapped = appeng.api.stacks.GenericStack.unwrapItemStack(target);
            if (unwrapped != null && appeng.api.stacks.AEFluidKey.is(unwrapped.what())) {
                var aeFluidKey = (appeng.api.stacks.AEFluidKey) unwrapped.what();
                return placeFluidFromNetwork(level, player, wand, context, ENERGY_COST, storage, src, aeFluidKey, aeFluidKey.getFluid());
            }
        } catch (Exception ignored) {}

        // Check if the selected slot is a fluid (stored in placement_config.fluids)
        String fluidId = null;
        if (cfg != null && cfg.contains("fluids")) {
            var ftag = cfg.getCompound("fluids");
            if (ftag.contains(Integer.toString(selected))) {
                fluidId = ftag.getString(Integer.toString(selected));
                if (fluidId != null && fluidId.isEmpty()) fluidId = null;
            }
        }

        if (fluidId != null) {
            try {
                var fid = new net.minecraft.resources.ResourceLocation(fluidId);
                var fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(fid);
                if (fluid == null || fluid.getBucket() == net.minecraft.world.item.Items.AIR) {
                    player.displayClientMessage(Component.translatable("message.meplacementtool.unsupported_target"), true);
                    return InteractionResult.FAIL;
                }
                return placeFluidFromNetwork(level, player, wand, context, ENERGY_COST, storage, src,
                        appeng.api.stacks.AEFluidKey.of(fluid), fluid);
            } catch (Exception e) {
                LOGGER.warn("Error resolving fluid {}", fluidId, e);
                player.displayClientMessage(Component.translatable("message.meplacementtool.unsupported_target"), true);
                return InteractionResult.FAIL;
            }
        }

        // Find a matching item in the AE network (respects NBT whitelist config)
        var aeKey = Config.findMatchingKey(storage, target);
        if (aeKey == null) {
                var itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(target.getItem());
            
            // Check if the item can be crafted using a key without NBT consideration
            var craftKey = appeng.api.stacks.AEItemKey.of(target);
            var craftingService = grid.getCraftingService();
            if (craftingService != null && craftKey != null && craftingService.isCraftable(craftKey)) {
                openCraftingMenu(serverPlayer, wand, craftKey, 1);
                return InteractionResult.sidedSuccess(false);
            }
            player.displayClientMessage(Component.translatable("message.meplacementtool.network_missing", target.getHoverName()), true);
            return InteractionResult.FAIL;
        }
        
        // (debug logs removed)

        // simulate extract to ensure availability, but defer actual extraction until after successful placement
        long avail = storage.extract(aeKey, 1L, appeng.api.config.Actionable.SIMULATE, src);
        if (avail <= 0) {
            // Check if the item can be crafted
            var craftingService = grid.getCraftingService();
            if (craftingService != null && craftingService.isCraftable(aeKey)) {
                // Open crafting menu for the item
                openCraftingMenu(serverPlayer, wand, aeKey, 1);
                return InteractionResult.sidedSuccess(false);
            }
            player.displayClientMessage(Component.translatable("message.meplacementtool.network_missing", target.getHoverName()), true);
            return InteractionResult.FAIL;
        }

        // Check if we have enough resources (blank patterns, upgrades) for memory card application
        if (MemoryCardHelper.hasConfiguredMemoryCard(player)) {
            var resourceCheck = MemoryCardHelper.checkResourcesForMultipleBlocks(player, grid, 1);
            if (!resourceCheck.sufficient) {
                String missing = resourceCheck.getMissingItemsMessage();
                player.displayClientMessage(Component.translatable("message.meplacementtool.missing_resources", missing), false);
                return InteractionResult.sidedSuccess(false);
            }
        }

        // Check for Mekanism configuration card (no resource requirements)
        boolean hasMekConfigCard = ModCompat.isMekanismLoaded() && MekanismConfigCardHelper.hasConfiguredConfigCard(player);

        // create stack to place
        ItemStack placeStack = aeKey.toStack(1);
        
        // (debug logs removed)

        // attempt placement: blocks use the adjacent position, parts use the clicked block position
        BlockPos blockPlacePos = context.getClickedPos().relative(context.getClickedFace());
        BlockPos partTargetPos = context.getClickedPos();
        boolean placed = false;
        // track placement result
        // capture previous block state for the block placement position (used for possible rollback)
        var prevStateBlock = level.getBlockState(blockPlacePos);
        try {
            if (placeStack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
                // Some mods (including AE) check the player's held stack during placement.
                // Temporarily replace the player's MAIN hand with the extracted stack so placement logic sees it.
                // Also capture original main/off hand to restore afterwards.
                ItemStack origMain = player.getMainHandItem().copy();
                ItemStack origOff = player.getOffhandItem().copy();
                try {
                    player.setItemInHand(InteractionHand.MAIN_HAND, placeStack);
                    // Create BlockPlaceContext with the correct placeStack (including NBT like energy)
                    BlockPlaceContext placeContext = new BlockPlaceContext(
                        level, player, InteractionHand.MAIN_HAND, placeStack,
                        new net.minecraft.world.phys.BlockHitResult(
                            context.getClickLocation(), context.getClickedFace(),
                            context.getClickedPos(), context.isInside()
                        )
                    );
                    var result = blockItem.place(placeContext);
                    boolean consumes = result.consumesAction();
                    if (consumes) {
                        placed = true;
                        lastPlacementPos = blockPlacePos;
                        lastPlacementWasBlock = true;
                    }
                } catch (Exception t) {
                    LOGGER.warn("Exception during placement attempt for player {} at {}", player.getName().getString(), blockPlacePos, t);
                } finally {
                    // restore original items
                    player.setItemInHand(InteractionHand.MAIN_HAND, origMain);
                    player.setItemInHand(InteractionHand.OFF_HAND, origOff);
                }
            } else if (placeStack.getItem() instanceof appeng.api.parts.IPartItem<?>) {
                // AE part placement (eg. ME Smart Cable) - use AE2's own placement calculation
                ItemStack origMain = player.getMainHandItem().copy();
                ItemStack origOff = player.getOffhandItem().copy();
                try {
                    player.setItemInHand(InteractionHand.MAIN_HAND, placeStack);
                    try {
                        // Use AE2's PartPlacement.getPartPlacement to compute where AE would place this part (same as preview)
                        var placement = appeng.parts.PartPlacement.getPartPlacement(player, level, placeStack, context.getClickedPos(), context.getClickedFace(), context.getClickLocation());
                        if (placement != null) {
                            var serverLevel = (level instanceof net.minecraft.server.level.ServerLevel) ? (net.minecraft.server.level.ServerLevel) level : null;
                            if (serverLevel != null) {
                                // Use AE2's placePart which performs host creation, collision checks and settings import
                                var part = appeng.parts.PartPlacement.placePart(player, serverLevel, (appeng.api.parts.IPartItem) placeStack.getItem(), placeStack.getTag(), placement.pos(), placement.side());
                                if (part != null) {
                                    placed = true;
                                    lastPlacementPos = placement.pos();
                                    lastPlacementWasBlock = false;
                                    lastPlacementSide = placement.side();
                                    lastPlacedPart = part;
                                }
                            }
                        }
                    } catch (Exception t) {
                        LOGGER.warn("Exception while using PartPlacement for player {} at {}", player.getName().getString(), partTargetPos, t);
                    }
                } catch (Exception t) {
                    LOGGER.warn("Exception during part placement attempt for player {} at {}", player.getName().getString(), partTargetPos, t);
                } finally {
                    player.setItemInHand(InteractionHand.MAIN_HAND, origMain);
                    player.setItemInHand(InteractionHand.OFF_HAND, origOff);
                }
            } else if (placeStack.getItem() instanceof appeng.api.implementations.items.IFacadeItem) {
                // AE facade placement - use AE2's facade placement logic
                // (facade debug logs removed)
                
                ItemStack origMain = player.getMainHandItem().copy();
                ItemStack origOff = player.getOffhandItem().copy();
                try {
                    player.setItemInHand(InteractionHand.MAIN_HAND, placeStack);
                    try {
                        var facadeItem = (appeng.api.implementations.items.IFacadeItem) placeStack.getItem();
                        var facade = facadeItem.createPartFromItemStack(placeStack, context.getClickedFace());
                        if (facade != null) {
                            
                            // Use AE2's facade placement logic directly (from FacadeItem.placeFacade)
                            var host = appeng.api.parts.PartHelper.getPartHost(level, context.getClickedPos());
                            
                            if (host != null) {
                                // Check if we can place facade on this host
                                boolean canPlace = host.getPart(null) != null 
                                    && host.getFacadeContainer().canAddFacade(facade);
                                
                                if (canPlace) {
                                    boolean added = host.getFacadeContainer().addFacade(facade);
                                    
                                    if (added) {
                                        // Play placement sound
                                        var blockState = facade.getBlockState();
                                        var soundType = blockState.getSoundType();
                                        level.playSound(null, context.getClickedPos(), 
                                            soundType.getPlaceSound(), 
                                            net.minecraft.sounds.SoundSource.BLOCKS,
                                            (soundType.getVolume() + 1.0F) / 2.0F,
                                            soundType.getPitch() * 0.8F);
                                        
                                        host.markForSave();
                                        host.markForUpdate();
                                        
                                        placed = true;
                                        lastPlacementPos = context.getClickedPos();
                                        lastPlacementWasBlock = false;
                                    } else {
                                        // debug removed: addFacade returned false
                                    }
                                } else {
                                    // debug removed: cannot place facade on this host
                                }
                            } else {
                                // debug removed: no part host at position
                            }
                        } else {
                            // debug removed: failed to create facade from item stack
                        }
                    } catch (Exception t) {
                        LOGGER.error("Exception during facade placement attempt for player {} at {}",
                            player.getName().getString(), context.getClickedPos(), t);
                    }
                } catch (Exception t) {
                    LOGGER.error("Exception during facade placement for player {} at {}",
                        player.getName().getString(), context.getClickedPos(), t);
                } finally {
                    player.setItemInHand(InteractionHand.MAIN_HAND, origMain);
                    player.setItemInHand(InteractionHand.OFF_HAND, origOff);
                }
            }
        } catch (Exception ignored) {
        }

        if (placed) {
            // After successful placement, perform the actual AE extraction. If extraction fails, roll back placement.
            long extracted = storage.extract(aeKey, 1L, appeng.api.config.Actionable.MODULATE, src);
            if (extracted <= 0) {
                // Attempt rollback
                BlockPos revertPos = lastPlacementPos != null ? lastPlacementPos : blockPlacePos;
                try {
                    if (lastPlacementWasBlock) {
                        level.setBlockAndUpdate(revertPos, prevStateBlock);
                    } else {
                        // For part placements we cannot reliably revert generically; just log the situation
                        LOGGER.warn("Extraction failed after part placement at {} — manual cleanup may be required", revertPos);
                    }
                } catch (Exception t) {
                    LOGGER.warn("Failed to revert block at {}", revertPos, t);
                }
                // drop the stack as fallback
                var ent = new net.minecraft.world.entity.item.ItemEntity(level, revertPos.getX() + 0.5, revertPos.getY() + 0.5,
                        revertPos.getZ() + 0.5, placeStack);
                level.addFreshEntity(ent);
                level.playSound(null, revertPos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.0F);
            } else {
                // consume power and play placement sound
                // Re-fetch the wand from player's hand since restoring origMain may have replaced the ItemStack reference
                ItemStack actualWand = player.getItemInHand(context.getHand());
                this.usePower(player, ENERGY_COST, actualWand);
                BlockPos soundPos = lastPlacementPos != null ? lastPlacementPos : blockPlacePos;
                // Play the block's own placement sound
                var placedState = level.getBlockState(soundPos);
                var soundType = placedState.getSoundType(level, soundPos, player);
                level.playSound(null, soundPos, soundType.getPlaceSound(), SoundSource.BLOCKS, 
                    (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
                // Ensure clients receive the block update immediately
                try {
                    var finalState = level.getBlockState(soundPos);
                    level.sendBlockUpdated(soundPos, prevStateBlock, finalState, 3);
                } catch (Exception ignored) {}

                // Apply memory card / config card settings from off-hand if present
                if (MemoryCardHelper.hasConfiguredMemoryCard(player)) {
                    if (lastPlacementWasBlock) {
                        MemoryCardHelper.applyMemoryCardToBlock(player, level, soundPos, true, grid);
                    } else if (lastPlacedPart != null) {
                        MemoryCardHelper.applyMemoryCardToPart(player, lastPlacedPart, true, grid);
                    }
                } else if (hasMekConfigCard && lastPlacementWasBlock) {
                    // Mekanism Configuration Card (only for blocks, not parts)
                    MekanismConfigCardHelper.applyConfigCardToBlock(player, level, soundPos, true);
                }
            }
        } else {
            // placement did not succeed — notify player
            // debug removed: placement failure details
            player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
        }

        return InteractionResult.sidedSuccess(false);
    }

    private InteractionResult placeFluidFromNetwork(Level level, Player player, ItemStack wand, UseOnContext context,
            double energyCost,
            appeng.api.storage.MEStorage storage,
            appeng.me.helpers.PlayerSource src,
            appeng.api.stacks.AEFluidKey aeFluidKey,
            net.minecraft.world.level.material.Fluid fluid) {

        BlockPos fluidPlacePos = context.getClickedPos().relative(context.getClickedFace());
        var prevState = level.getBlockState(fluidPlacePos);

        long simAvail = storage.extract(aeFluidKey, appeng.api.stacks.AEFluidKey.AMOUNT_BLOCK, appeng.api.config.Actionable.SIMULATE, src);
        if (simAvail < appeng.api.stacks.AEFluidKey.AMOUNT_BLOCK) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.network_missing", aeFluidKey.getDisplayName()), true);
            return InteractionResult.FAIL;
        }

        boolean placedFluid = false;
        try {
            if (level instanceof net.minecraft.server.level.ServerLevel) {
                var stateAtPos = level.getBlockState(fluidPlacePos);
                boolean isFlowingFluid = fluid instanceof net.minecraft.world.level.material.FlowingFluid;
                var legacyBlock = fluid.defaultFluidState().createLegacyBlock();
                boolean stateIsLegacy = stateAtPos == legacyBlock;
                boolean stateIsAir = stateAtPos.isAir();
                boolean canBeReplaced = false;
                try { canBeReplaced = stateAtPos.canBeReplaced(fluid); } catch (Exception ignored) {}
                boolean isLiquidContainer = stateAtPos.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer;
                boolean containerCanPlace = false;
                if (isLiquidContainer) {
                    try {
                        containerCanPlace = ((net.minecraft.world.level.block.LiquidBlockContainer) stateAtPos.getBlock())
                                .canPlaceLiquid(level, fluidPlacePos, stateAtPos, fluid);
                    } catch (Exception ignored) {}
                }
                boolean hasTag = aeFluidKey.hasTag();

                boolean aeCanPlace = isFlowingFluid && !stateIsLegacy && !hasTag
                        && (stateIsAir || canBeReplaced || (isLiquidContainer && containerCanPlace));

                if (aeCanPlace) {
                    boolean success = false;
                    if (level.dimensionType().ultraWarm() && fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                        level.playSound(null, fluidPlacePos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F);
                        success = true;
                    } else if (isLiquidContainer && fluid == net.minecraft.world.level.material.Fluids.WATER) {
                        ((net.minecraft.world.level.block.LiquidBlockContainer) stateAtPos.getBlock())
                                .placeLiquid(level, fluidPlacePos, stateAtPos, ((net.minecraft.world.level.material.FlowingFluid) fluid).getSource(false));
                        success = true;
                    } else {
                        if (canBeReplaced && !stateAtPos.liquid()) {
                            level.destroyBlock(fluidPlacePos, true);
                        }
                        success = level.setBlock(fluidPlacePos, legacyBlock, net.minecraft.world.level.block.Block.UPDATE_ALL_IMMEDIATE);
                    }
                    if (success) {
                        placedFluid = true;
                    }
                }
            }
        } catch (Exception t) {
            LOGGER.warn("Exception during fluid placement for player {} at {}", player.getName().getString(), fluidPlacePos, t);
        }

        if (placedFluid) {
            long extracted = storage.extract(aeFluidKey, appeng.api.stacks.AEFluidKey.AMOUNT_BLOCK, appeng.api.config.Actionable.MODULATE, src);
            if (extracted <= 0) {
                try { level.setBlockAndUpdate(fluidPlacePos, prevState); } catch (Exception t) { LOGGER.warn("Failed to revert fluid block at {}", fluidPlacePos, t); }
                player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
                return InteractionResult.sidedSuccess(false);
            }
            this.usePower(player, energyCost, wand);
            level.playSound(null, fluidPlacePos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            return InteractionResult.sidedSuccess(false);
        }

        player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Right-click: open configuration GUI only when not targeting a block (avoid conflict with placement)
        net.minecraft.world.phys.HitResult hr = player.pick(5.0D, 0.0F, false);
        if (hr != null && hr.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            CompoundTag cfg = WandNbt.getConfig(stack);

            // create handler from existing NBT (server side)
            var handler = WandNbt.readInventory(cfg);

            NetworkHooks.openScreen(serverPlayer,
                new SimpleMenuProvider((wnd, inv, pl) -> new WandMenu(wnd, inv, handler), Component.empty()),
                    buf -> buf.writeNbt(cfg));
        }

        return new InteractionResultHolder<>(InteractionResult.sidedSuccess(level.isClientSide()), stack);
    }
}
