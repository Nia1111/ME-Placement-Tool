package com.moakiee.meplacementtool;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

import appeng.api.config.Actionable;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.me.helpers.PlayerSource;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.crafting.CraftAmountMenu;

import org.jetbrains.annotations.Nullable;

/**
 * ME Multiblock Placement Tool - extends BasePlacementToolItem for bulk placement
 */
public class ItemMultiblockPlacementTool extends BasePlacementToolItem implements IMenuItem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[] PLACEMENT_COUNTS = {1, 8, 64, 256, 1024};

    /**
     * Placement direction modes for bulk placement.
     * AUTO uses BFS across the face plane. NORTH_SOUTH / EAST_WEST / VERTICAL
     * constrain expansion to a single axis, producing a free-form line even
     * in air. Inspired by ConstructionWand's LOCK options.
     */
    public enum DirectionMode {
        AUTO,
        NORTH_SOUTH,
        EAST_WEST,
        VERTICAL;

        public static DirectionMode fromId(int id) {
            DirectionMode[] values = values();
            if (id < 0 || id >= values.length) return AUTO;
            return values[id];
        }

        public DirectionMode next() {
            DirectionMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public String translationKey() {
            return "meplacementtool.direction." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public ItemMultiblockPlacementTool(Item.Properties props) {
        super(() -> Config.multiblockPlacementToolEnergyCapacity, props);
    }

    /**
     * Open the crafting menu for an item that can be crafted.
     * @param amount The amount to pre-fill in the crafting request
     */
    private void openCraftingMenu(ServerPlayer player, ItemStack wand, AEKey whatToCraft, int amount) {
        int wandSlot = findInventorySlot(player, wand);
        if (wandSlot >= 0) {
            CraftAmountMenu.open(player, MenuLocators.forInventorySlot(wandSlot), whatToCraft, amount);
        } else if (player.getMainHandItem() == wand) {
            CraftAmountMenu.open(player, MenuLocators.forHand(player, InteractionHand.MAIN_HAND), whatToCraft, amount);
        } else if (player.getOffhandItem() == wand) {
            CraftAmountMenu.open(player, MenuLocators.forHand(player, InteractionHand.OFF_HAND), whatToCraft, amount);
        }
    }

    private int findInventorySlot(Player player, ItemStack itemStack) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i) == itemStack) {
                return i;
            }
        }
        return -1;
    }

    public static int getPlacementCount(ItemStack stack) {
        CompoundTag cfg = stack.get(ModDataComponents.PLACEMENT_CONFIG.get());
        if (cfg != null && cfg.contains("PlacementCount")) {
            int count = cfg.getInt("PlacementCount");
            for (int pc : PLACEMENT_COUNTS) {
                if (pc == count) return count;
            }
        }
        return PLACEMENT_COUNTS[0];
    }

    public static int getNextPlacementCount(ItemStack stack, boolean forward) {
        int current = getPlacementCount(stack);
        for (int i = 0; i < PLACEMENT_COUNTS.length; i++) {
            if (PLACEMENT_COUNTS[i] == current) {
                int nextIndex = forward
                        ? (i + 1) % PLACEMENT_COUNTS.length
                        : (i - 1 + PLACEMENT_COUNTS.length) % PLACEMENT_COUNTS.length;
                return PLACEMENT_COUNTS[nextIndex];
            }
        }
        return PLACEMENT_COUNTS[0];
    }

    public static DirectionMode getDirectionMode(ItemStack stack) {
        CompoundTag cfg = stack.get(ModDataComponents.PLACEMENT_CONFIG.get());
        if (cfg != null && cfg.contains("DirectionMode")) {
            return DirectionMode.fromId(cfg.getInt("DirectionMode"));
        }
        return DirectionMode.AUTO;
    }

    @Override
    public ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator,
            @Nullable BlockHitResult hitResult) {
        return new PlacementToolMenuHost(this, player, locator, (p, subMenu) -> {
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
        int placementCount = getPlacementCount(wand);
        final double ENERGY_COST = Config.multiblockPlacementToolBaseEnergyCost * placementCount;

        if (!this.hasPower(player, ENERGY_COST, wand)) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.device_not_powered"), true);
            return InteractionResult.FAIL;
        }

        var grid = this.getLinkedGrid(wand, level, player);
        if (grid == null) {
            return InteractionResult.FAIL;
        }

        // Read config from Data Component
        CompoundTag cfg = wand.get(ModDataComponents.PLACEMENT_CONFIG.get());
        if (cfg == null) {
            cfg = new CompoundTag();
        }

        int selected = cfg.getInt("SelectedSlot");
        if (selected < 0 || selected >= 18) selected = 0;

        ItemStack target = getItemFromConfig(cfg, selected);
        if (target == null || target.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.no_configured_item"), true);
            return InteractionResult.FAIL;
        }

        DirectionMode directionMode = DirectionMode.fromId(cfg.getInt("DirectionMode"));

        var storage = grid.getStorageService().getInventory();
        var src = new PlayerSource(player);

        // Check for fluid placement
        try {
            var unwrapped = GenericStack.unwrapItemStack(target);
            if (unwrapped != null && AEFluidKey.is(unwrapped.what())) {
                return handleFluidMultiPlacement(context, player, wand, storage, src,
                        (AEFluidKey) unwrapped.what(), placementCount, ENERGY_COST, directionMode);
            }
        } catch (Throwable ignored) {}

        // Check fluid in fluids config
        String fluidId = getFluidFromConfig(cfg, selected);
        if (fluidId != null) {
            return handleFluidIdMultiPlacement(context, player, wand, storage, src, fluidId, placementCount, ENERGY_COST, directionMode);
        }

        // Block placement - find all matching keys (respects NBT whitelist config)
        var matchingKeys = Config.findAllMatchingKeys(storage, target);
        if (matchingKeys.isEmpty()) {
            // Check if the item can be crafted
            var craftKey = AEItemKey.of(target);
            var craftingService = grid.getCraftingService();
            if (craftingService != null && craftKey != null && craftingService.isCraftable(craftKey)) {
                // Request crafting for the full amount needed
                openCraftingMenu(serverPlayer, wand, craftKey, placementCount);
                return InteractionResult.sidedSuccess(false);
            }
            player.displayClientMessage(Component.translatable("message.meplacementtool.network_missing", 
                    target.getHoverName()), true);
            return InteractionResult.FAIL;
        }

        // Calculate total available across all matching keys
        long totalAvailable = matchingKeys.stream().mapToLong(Map.Entry::getValue).sum();

        if (!(target.getItem() instanceof BlockItem blockItem)) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.unsupported_target"), true);
            return InteractionResult.FAIL;
        }

        BlockPos clickedPos = context.getClickedPos();
        var clickedFace = context.getClickedFace();
        var clickedState = level.getBlockState(clickedPos);

        // Generate placement positions (BFS or axis-locked based on mode)
        List<BlockPos> placePositions = calculatePlacementPositions(player, level, clickedPos, clickedFace, clickedState, placementCount, directionMode);

        if (placePositions.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
            return InteractionResult.sidedSuccess(false);
        }

        // Check if we have enough across all matching keys
        if (totalAvailable < placePositions.size()) {
            // Check if the item can be crafted
            var craftKey = AEItemKey.of(target);
            var craftingService = grid.getCraftingService();
            if (craftingService != null && craftKey != null && craftingService.isCraftable(craftKey)) {
                // Request crafting for the missing amount
                int missingAmount = (int) (placePositions.size() - totalAvailable);
                openCraftingMenu(serverPlayer, wand, craftKey, missingAmount);
                return InteractionResult.sidedSuccess(false);
            }
            player.displayClientMessage(Component.translatable("message.meplacementtool.network_missing", 
                    target.getHoverName()), true);
            return InteractionResult.FAIL;
        }

        // Check memory card resources
        if (MemoryCardHelper.hasConfiguredMemoryCard(player)) {
            var resourceCheck = MemoryCardHelper.checkResourcesForMultipleBlocks(player, grid, placePositions.size());
            if (!resourceCheck.sufficient) {
                player.displayClientMessage(Component.translatable("message.meplacementtool.missing_resources", 
                        resourceCheck.getMissingItemsMessage()), false);
                return InteractionResult.sidedSuccess(false);
            }
        }

        boolean hasMekConfigCard = ModCompat.isMekanismLoaded() && 
                MekanismConfigCardHelper.hasConfiguredConfigCard(player);

        // Save original off-hand item before placement loop (for config card application later)
        ItemStack savedOffHand = player.getOffhandItem().copy();

        // Create a mutable copy of matching keys with remaining counts
        List<Map.Entry<AEItemKey, Long>> availableKeys = new ArrayList<>();
        for (var entry : matchingKeys) {
            availableKeys.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
        }

        // Track which keys we've used and how many from each (for extraction later)
        Map<AEItemKey, Long> extractionMap = new LinkedHashMap<>();

        // Place blocks - track snapshots for undo
        int placedCount = 0;
        List<UndoHistory.PlacementSnapshot> placedSnapshots = new ArrayList<>();

        for (BlockPos placePos : placePositions) {
            // Find a key with available count
            AEItemKey currentKey = null;
            for (var entry : availableKeys) {
                if (entry.getValue() > 0) {
                    currentKey = entry.getKey();
                    entry.setValue(entry.getValue() - 1);
                    break;
                }
            }
            
            if (currentKey == null) {
                break;
            }

            var placeStack = currentKey.toStack(1);
            ItemStack origMain = player.getMainHandItem();
            ItemStack origOff = player.getOffhandItem();

            try {
                player.setItemInHand(InteractionHand.MAIN_HAND, placeStack);
                BlockPlaceContext placeContext = new BlockPlaceContext(
                        level, player, InteractionHand.MAIN_HAND, placeStack,
                        new BlockHitResult(context.getClickLocation(), context.getClickedFace(),
                                placePos, context.isInside())
                );
                var result = blockItem.place(placeContext);
                if (result.consumesAction()) {
                    placedCount++;
                    // Record for undo - store the new block state after placement
                    placedSnapshots.add(new UndoHistory.PlacementSnapshot(
                            level.getBlockState(placePos), placePos, placeStack, currentKey, 1));
                    // Track extraction
                    extractionMap.merge(currentKey, 1L, Long::sum);
                }
            } catch (Throwable t) {
                LOGGER.warn("Exception during placement at {}", placePos, t);
            } finally {
                player.setItemInHand(InteractionHand.MAIN_HAND, origMain);
                player.setItemInHand(InteractionHand.OFF_HAND, origOff);
            }
        }

        if (placedCount == 0) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
            return InteractionResult.sidedSuccess(false);
        }

        // Extract from each key we used
        long totalExtracted = 0;
        for (var entry : extractionMap.entrySet()) {
            long extracted = storage.extract(entry.getKey(), entry.getValue(), Actionable.MODULATE, src);
            totalExtracted += extracted;
        }
        
        if (totalExtracted <= 0) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
            return InteractionResult.sidedSuccess(false);
        }

        // Restore off-hand for config card application
        player.setItemInHand(InteractionHand.OFF_HAND, savedOffHand);

        // Apply memory card / config card settings to all placed blocks
        boolean configApplied = false;
        
        // AE2 Memory Card
        if (MemoryCardHelper.hasConfiguredMemoryCard(player)) {
            boolean firstBlock = true;
            for (UndoHistory.PlacementSnapshot snapshot : placedSnapshots) {
                if (MemoryCardHelper.applyMemoryCardToBlock(player, level, snapshot.pos, firstBlock, grid)) {
                    configApplied = true;
                }
                firstBlock = false;
            }
        }
        // Mekanism Configuration Card
        else if (hasMekConfigCard) {
            boolean firstBlock = true;
            for (UndoHistory.PlacementSnapshot snapshot : placedSnapshots) {
                if (MekanismConfigCardHelper.applyConfigCardToBlock(player, level, snapshot.pos, firstBlock)) {
                    configApplied = true;
                }
                firstBlock = false;
            }
        }

        // Add to undo history, marking as non-undoable if config was applied
        MEPlacementToolMod.instance.undoHistory.add(player, level, placedSnapshots, configApplied);

        // Consume power proportionally
        double actualEnergy = ENERGY_COST * placedCount / placementCount;
        this.usePower(player, actualEnergy, wand);
        
        // Play the block's own placement sound (use first placed position)
        if (!placedSnapshots.isEmpty()) {
            BlockPos soundPos = placedSnapshots.get(0).pos;
            var placedState = level.getBlockState(soundPos);
            var soundType = placedState.getSoundType(level, soundPos, player);
            level.playSound(null, soundPos, soundType.getPlaceSound(), SoundSource.BLOCKS, 
                (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
        }

        return InteractionResult.sidedSuccess(false);
    }

    private List<BlockPos> calculatePlacementPositions(Player player, Level level, BlockPos clickedPos,
            net.minecraft.core.Direction clickedFace, net.minecraft.world.level.block.state.BlockState clickedState,
            int maxCount, DirectionMode directionMode) {
        LinkedList<BlockPos> candidates = new LinkedList<>();
        HashSet<BlockPos> allCandidates = new HashSet<>();
        ArrayList<BlockPos> placePositions = new ArrayList<>();

        // MAX_CANDIDATES limit to prevent infinite loops when placeable positions are fewer than requested
        final int MAX_CANDIDATES = maxCount * 10;

        BlockPos startingPoint = clickedPos.relative(clickedFace);
        candidates.add(startingPoint);

        while (!candidates.isEmpty() && placePositions.size() < maxCount && allCandidates.size() < MAX_CANDIDATES) {
            BlockPos currentCandidate = candidates.removeFirst();
            if (!allCandidates.add(currentCandidate)) {
                continue;
            }

            // Mirrors ConstructionWand: even when a direction lock is active, the block "behind"
            // the candidate (along the opposite of the clicked face) must match the clicked block.
            BlockPos supportingPoint = currentCandidate.relative(clickedFace.getOpposite());
            var supportingState = level.getBlockState(supportingPoint);

            if (supportingState.getBlock() == clickedState.getBlock()) {
                var currentState = level.getBlockState(currentCandidate);
                boolean canPlace = level.isEmptyBlock(currentCandidate);
                if (!canPlace) {
                    try {
                        BlockPlaceContext checkContext = new BlockPlaceContext(new UseOnContext(
                                player, InteractionHand.MAIN_HAND, new BlockHitResult(
                                        player.getEyePosition(), clickedFace, currentCandidate, false)
                        ));
                        canPlace = currentState.canBeReplaced(checkContext);
                    } catch (Throwable t) {}
                }

                if (canPlace) {
                    placePositions.add(currentCandidate);
                    // Only expand candidates after successful placement (prevents cross-pit overflow)
                    addAdjacentPositions(candidates, currentCandidate, clickedFace, directionMode);
                }
            }
        }

        return placePositions;
    }

    private void addAdjacentPositions(LinkedList<BlockPos> candidates, BlockPos pos, net.minecraft.core.Direction face, DirectionMode directionMode) {
        switch (directionMode) {
            case NORTH_SOUTH -> {
                candidates.add(pos.north());
                candidates.add(pos.south());
            }
            case EAST_WEST -> {
                candidates.add(pos.east());
                candidates.add(pos.west());
            }
            case VERTICAL -> {
                candidates.add(pos.above());
                candidates.add(pos.below());
            }
            case AUTO -> addAutoAdjacentPositions(candidates, pos, face);
        }
    }

    private void addAutoAdjacentPositions(LinkedList<BlockPos> candidates, BlockPos pos, net.minecraft.core.Direction face) {
        switch (face) {
            case DOWN, UP -> {
                candidates.add(pos.north());
                candidates.add(pos.south());
                candidates.add(pos.east());
                candidates.add(pos.west());
                candidates.add(pos.north().east());
                candidates.add(pos.north().west());
                candidates.add(pos.south().east());
                candidates.add(pos.south().west());
            }
            case NORTH, SOUTH -> {
                candidates.add(pos.east());
                candidates.add(pos.west());
                candidates.add(pos.above());
                candidates.add(pos.below());
                candidates.add(pos.above().east());
                candidates.add(pos.above().west());
                candidates.add(pos.below().east());
                candidates.add(pos.below().west());
            }
            case EAST, WEST -> {
                candidates.add(pos.north());
                candidates.add(pos.south());
                candidates.add(pos.above());
                candidates.add(pos.below());
                candidates.add(pos.above().north());
                candidates.add(pos.above().south());
                candidates.add(pos.below().north());
                candidates.add(pos.below().south());
            }
        }
    }

    private InteractionResult handleFluidMultiPlacement(UseOnContext context, Player player, ItemStack wand,
            appeng.api.storage.MEStorage storage, PlayerSource src, AEFluidKey aeFluidKey,
            int placementCount, double energyCost, DirectionMode directionMode) {
        Level level = context.getLevel();
        var fluid = aeFluidKey.getFluid();

        if (!(fluid instanceof FlowingFluid)) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.unsupported_target"), true);
            return InteractionResult.FAIL;
        }

        var legacyBlock = fluid.defaultFluidState().createLegacyBlock();
        BlockPos clickedPos = context.getClickedPos();
        var clickedFace = context.getClickedFace();
        var clickedState = level.getBlockState(clickedPos);

        // Find all fluid placement positions
        List<BlockPos> placePositions = calculateFluidPlacementPositions(level, clickedPos, clickedFace,
                clickedState, fluid, legacyBlock, placementCount, directionMode);

        if (placePositions.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
            return InteractionResult.sidedSuccess(false);
        }

        long totalFluidNeeded = (long) placePositions.size() * AEFluidKey.AMOUNT_BLOCK;
        long simAvail = storage.extract(aeFluidKey, totalFluidNeeded, Actionable.SIMULATE, src);
        if (simAvail < totalFluidNeeded) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.network_missing", 
                    aeFluidKey.getDisplayName()), true);
            return InteractionResult.FAIL;
        }

        int placedCount = 0;
        for (BlockPos placePos : placePositions) {
            try {
                var stateAtPos = level.getBlockState(placePos);
                boolean isLiquidContainer = stateAtPos.getBlock() instanceof LiquidBlockContainer;
                boolean success = false;

                if (level.dimensionType().ultraWarm() && fluid.is(FluidTags.WATER)) {
                    success = true;
                } else if (isLiquidContainer && fluid == Fluids.WATER) {
                    ((LiquidBlockContainer) stateAtPos.getBlock())
                            .placeLiquid(level, placePos, stateAtPos, ((FlowingFluid) fluid).getSource(false));
                    success = true;
                } else {
                    boolean canBeReplaced = false;
                    try { canBeReplaced = stateAtPos.canBeReplaced(fluid); } catch (Throwable ignored) {}
                    if (canBeReplaced && !stateAtPos.liquid()) {
                        level.destroyBlock(placePos, true);
                    }
                    success = level.setBlock(placePos, legacyBlock, Block.UPDATE_ALL_IMMEDIATE);
                }
                if (success) placedCount++;
            } catch (Throwable t) {
                LOGGER.warn("Exception during fluid placement at {}", placePos, t);
            }
        }

        if (placedCount > 0) {
            storage.extract(aeFluidKey, (long) placedCount * AEFluidKey.AMOUNT_BLOCK, Actionable.MODULATE, src);
            this.usePower(player, energyCost * placedCount / placementCount, wand);
            level.playSound(null, clickedPos.relative(clickedFace), SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            return InteractionResult.sidedSuccess(false);
        }

        player.displayClientMessage(Component.translatable("message.meplacementtool.cannot_place"), true);
        return InteractionResult.sidedSuccess(false);
    }

    private List<BlockPos> calculateFluidPlacementPositions(Level level, BlockPos clickedPos,
            net.minecraft.core.Direction clickedFace, net.minecraft.world.level.block.state.BlockState clickedState,
            net.minecraft.world.level.material.Fluid fluid, net.minecraft.world.level.block.state.BlockState legacyBlock,
            int maxCount, DirectionMode directionMode) {
        LinkedList<BlockPos> candidates = new LinkedList<>();
        HashSet<BlockPos> allCandidates = new HashSet<>();
        ArrayList<BlockPos> placePositions = new ArrayList<>();

        // MAX_CANDIDATES limit to prevent infinite loops when placeable positions are fewer than requested
        final int MAX_CANDIDATES = maxCount * 10;

        BlockPos startingPoint = clickedPos.relative(clickedFace);
        candidates.add(startingPoint);

        while (!candidates.isEmpty() && placePositions.size() < maxCount && allCandidates.size() < MAX_CANDIDATES) {
            BlockPos currentCandidate = candidates.removeFirst();
            if (!allCandidates.add(currentCandidate)) {
                continue;
            }

            // Same supporting-block rule as the block path
            BlockPos supportingPoint = currentCandidate.relative(clickedFace.getOpposite());
            var supportingState = level.getBlockState(supportingPoint);

            if (supportingState.getBlock() == clickedState.getBlock()) {
                var stateAtPos = level.getBlockState(currentCandidate);
                boolean stateIsLegacy = stateAtPos == legacyBlock;
                boolean stateIsAir = stateAtPos.isAir();
                boolean canBeReplaced = false;
                try { canBeReplaced = stateAtPos.canBeReplaced(fluid); } catch (Throwable ignored) {}
                boolean isLiquidContainer = stateAtPos.getBlock() instanceof LiquidBlockContainer;
                boolean containerCanPlace = false;
                if (isLiquidContainer) {
                    try {
                        containerCanPlace = ((LiquidBlockContainer) stateAtPos.getBlock())
                                .canPlaceLiquid(null, level, currentCandidate, stateAtPos, fluid);
                    } catch (Throwable ignored) {}
                }

                boolean canPlace = !stateIsLegacy && (stateIsAir || canBeReplaced || (isLiquidContainer && containerCanPlace));
                if (canPlace) {
                    placePositions.add(currentCandidate);
                    // Only expand candidates after successful placement (prevents cross-pit overflow)
                    addAdjacentPositions(candidates, currentCandidate, clickedFace, directionMode);
                }
            }
        }

        return placePositions;
    }

    private InteractionResult handleFluidIdMultiPlacement(UseOnContext context, Player player, ItemStack wand,
            appeng.api.storage.MEStorage storage, PlayerSource src, String fluidId,
            int placementCount, double energyCost, DirectionMode directionMode) {
        try {
            var fid = ResourceLocation.tryParse(fluidId);
            if (fid == null) {
                player.displayClientMessage(Component.translatable("message.meplacementtool.unsupported_target"), true);
                return InteractionResult.FAIL;
            }

            var fluid = BuiltInRegistries.FLUID.get(fid);
            if (fluid == null || fluid == Fluids.EMPTY) {
                player.displayClientMessage(Component.translatable("message.meplacementtool.unsupported_target"), true);
                return InteractionResult.FAIL;
            }

            var aeFluidKey = AEFluidKey.of(fluid);
            return handleFluidMultiPlacement(context, player, wand, storage, src, aeFluidKey, placementCount, energyCost, directionMode);
        } catch (Exception e) {
            LOGGER.warn("Error resolving fluid {}", fluidId, e);
            player.displayClientMessage(Component.translatable("message.meplacementtool.unsupported_target"), true);
            return InteractionResult.FAIL;
        }
    }

    private ItemStack getItemFromConfig(CompoundTag cfg, int slot) {
        if (cfg == null) return ItemStack.EMPTY;

        CompoundTag itemsTag = cfg.contains("items") ? cfg.getCompound("items") : cfg;
        if (itemsTag.contains("Items")) {
            ListTag list = itemsTag.getList("Items", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag itemTag = list.getCompound(i);
                if (itemTag.getInt("Slot") == slot) {
                    return ItemStack.parseOptional(net.minecraft.core.HolderLookup.Provider.create(
                            java.util.stream.Stream.empty()), itemTag);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private String getFluidFromConfig(CompoundTag cfg, int slot) {
        if (cfg == null || !cfg.contains("fluids")) return null;
        var ftag = cfg.getCompound("fluids");
        String key = Integer.toString(slot);
        if (ftag.contains(key)) {
            String fluidId = ftag.getString(key);
            return fluidId.isEmpty() ? null : fluidId;
        }
        return null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        var hr = player.pick(5.0D, 0.0F, false);
        if (hr != null && hr.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            player.openMenu(new net.minecraft.world.MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.empty();
                }

                @Override
                public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, 
                        net.minecraft.world.entity.player.Inventory inv, Player p) {
                    return new WandMenu(id, inv, stack);
                }
            }, buf -> {
                CompoundTag cfg = stack.get(ModDataComponents.PLACEMENT_CONFIG.get());
                buf.writeNbt(cfg);
            });
        }

        return new InteractionResultHolder<>(InteractionResult.sidedSuccess(level.isClientSide()), stack);
    }
}
