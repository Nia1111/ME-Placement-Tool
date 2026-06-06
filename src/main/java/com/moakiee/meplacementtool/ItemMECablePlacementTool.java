package com.moakiee.meplacementtool;

import appeng.api.config.Actionable;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.IGrid;
import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.util.AEColor;
import appeng.core.definitions.AEParts;
import appeng.core.definitions.ColoredItemDefinition;
import appeng.me.helpers.PlayerSource;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartHelper;
import appeng.parts.PartPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ME Cable Placement Tool - Places cables along lines, planes, or branching patterns.
 */
public class ItemMECablePlacementTool extends BasePlacementToolItem implements IMenuItem {

    /**
     * Check if a cable can be placed at the given position.
     * A cable can be placed if:
     * - The position is air, OR
     * - The position is an AE2 IPartHost without a center cable (only has side parts like panels, anchors, quartz fiber)
     */
    public static boolean canPlaceCableAt(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        
        // Air blocks are always valid
        if (state.isAir()) {
            return true;
        }
        
        // Check for AE2 cable bus with no center cable
        IPartHost host = PartHelper.getPartHost(level, pos);
        if (host != null) {
            // If there's no center cable, we can place one
            // This allows placing cables where only panels/anchors/quartz fiber exist
            return host.getPart(null) == null;
        }
        
        // Any other block - cannot place
        return false;
    }

    /**
     * Get the smart target position for cable placement.
     * When clicking on an IPartHost (cable bus with parts but no cable):
     * - If clicking on a face that HAS a part (panel front) -> use adjacent position
     * - If clicking on a face whose OPPOSITE has a part (panel back) -> use clicked position
     * - If clicking on a side face (neither has part) -> use adjacent position
     * For other blocks: use standard logic (try adjacent first, then clicked)
     */
    public static BlockPos getSmartTargetPos(Level level, BlockPos clickedPos, Direction clickedFace) {
        BlockPos adjacentPos = clickedPos.relative(clickedFace);
        
        // Check if clicked position is an AE2 part host without center cable
        IPartHost host = PartHelper.getPartHost(level, clickedPos);
        if (host != null && host.getPart(null) == null) {
            // This is a valid cable placement position (has parts but no center cable)
            
            // Check if the clicked face itself has a part (panel front)
            if (host.getPart(clickedFace) != null) {
                // Clicking on panel front face -> use adjacent position
                if (canPlaceCableAt(level, adjacentPos)) {
                    return adjacentPos;
                }
                return clickedPos;
            }
            
            // Check if the opposite face has a part (we're clicking on panel backside)
            if (host.getPart(clickedFace.getOpposite()) != null) {
                // Clicking on panel backside -> place cable in this block
                return clickedPos;
            }
            
            // Side face (no part on clicked face or opposite) -> use adjacent position
            if (canPlaceCableAt(level, adjacentPos)) {
                return adjacentPos;
            }
            return clickedPos;
        }
        
        // Standard logic for non-part-host blocks
        if (canPlaceCableAt(level, adjacentPos)) {
            return adjacentPos;
        }
        if (canPlaceCableAt(level, clickedPos)) {
            return clickedPos;
        }
        // Neither is valid, return adjacent (will be filtered later)
        return adjacentPos;
    }


    public enum PlacementMode {
        LINE,
        PLANE_FILL,
        PLANE_BRANCHING
    }

    public enum CableType {
        GLASS(AEParts.GLASS_CABLE),
        COVERED(AEParts.COVERED_CABLE),
        SMART(AEParts.SMART_CABLE),
        DENSE_COVERED(AEParts.COVERED_DENSE_CABLE),
        DENSE_SMART(AEParts.SMART_DENSE_CABLE);

        private final ColoredItemDefinition<?> definition;

        CableType(ColoredItemDefinition<?> definition) {
            this.definition = definition;
        }

        public ItemStack getStack(AEColor color) {
            return definition.stack(color);
        }
    }

    public ItemMECablePlacementTool(Item.Properties props) {
        super(() -> Config.cablePlacementToolEnergyCapacity, props);
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

    @Override
    public ItemMenuHost getMenuHost(Player player, ItemMenuHostLocator locator,
            @Nullable BlockHitResult hitResult) {
        return new PlacementToolMenuHost(this, player, locator, (p, subMenu) -> p.closeContainer());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        
        // In LINE mode, if point1 is set, allow confirming placement by right-clicking air
        PlacementMode mode = getMode(stack);
        BlockPos p1 = getPoint1(stack);
        
        if (mode == PlacementMode.LINE && p1 != null) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                // Use player look direction to determine endpoint
                BlockPos endpoint = findLine(player, p1);
                if (endpoint != null) {
                    setPoint2(stack, endpoint);
                    player.displayClientMessage(Component.translatable("message.meplacementtool.point2_set", endpoint.toShortString()), true);
                    boolean craftingTriggered = executePlacement(serverPlayer, stack, level, p1, endpoint);
                    // Only clear points if crafting was NOT triggered
                    if (!craftingTriggered) {
                        setPoint1(stack, null);
                        setPoint2(stack, null);
                        // Sync cleared points to client
                        int slot = hand == InteractionHand.OFF_HAND
                                ? Inventory.SLOT_OFFHAND
                                : player.getInventory().selected;
                        syncPointsToClient(serverPlayer, slot);
                    }
                    return InteractionResultHolder.success(stack);
                }
            }
            return InteractionResultHolder.success(stack);
        }
        
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        int slot = context.getHand() == InteractionHand.OFF_HAND
                ? Inventory.SLOT_OFFHAND
                : player.getInventory().selected;

        // Get the position using smart target logic
        BlockPos clickedPos = context.getClickedPos();
        Direction face = context.getClickedFace();
        BlockPos targetPos = getSmartTargetPos(level, clickedPos, face);

        PlacementMode mode = getMode(stack);
        BlockPos p1 = getPoint1(stack);
        BlockPos p2 = getPoint2(stack);

        if (mode == PlacementMode.PLANE_BRANCHING) {
            // Branching mode uses 3 points
            if (p1 == null) {
                setPoint1(stack, targetPos);
                player.displayClientMessage(Component.translatable("message.meplacementtool.branch_point1_set", targetPos.toShortString()), true);
                syncPointsToClient(serverPlayer, slot);
            } else if (p2 == null) {
                setPoint2(stack, targetPos);
                player.displayClientMessage(Component.translatable("message.meplacementtool.branch_point2_set", targetPos.toShortString()), true);
                syncPointsToClient(serverPlayer, slot);
            } else {
                setPoint3(stack, targetPos);
                player.displayClientMessage(Component.translatable("message.meplacementtool.branch_point3_set", targetPos.toShortString()), true);
                boolean craftingTriggered = executeBranchPlacement(serverPlayer, stack, level, p1, p2, targetPos);
                // Only clear points if crafting was NOT triggered
                if (!craftingTriggered) {
                    setPoint1(stack, null);
                    setPoint2(stack, null);
                    setPoint3(stack, null);
                    // Sync cleared points to client
                    syncPointsToClient(serverPlayer, slot);
                }
            }
        } else if (mode == PlacementMode.LINE) {
            // LINE mode: first click sets start, second click uses player look direction
            if (p1 == null) {
                setPoint1(stack, targetPos);
                player.displayClientMessage(Component.translatable("message.meplacementtool.point1_set", targetPos.toShortString()), true);
                syncPointsToClient(serverPlayer, slot);
            } else {
                // Use player look direction to determine endpoint
                BlockPos endpoint = findLine(player, p1);
                boolean craftingTriggered;
                if (endpoint != null) {
                    setPoint2(stack, endpoint);
                    player.displayClientMessage(Component.translatable("message.meplacementtool.point2_set", endpoint.toShortString()), true);
                    craftingTriggered = executePlacement(serverPlayer, stack, level, p1, endpoint);
                } else {
                    // Fallback: use clicked position
                    setPoint2(stack, targetPos);
                    player.displayClientMessage(Component.translatable("message.meplacementtool.point2_set", targetPos.toShortString()), true);
                    craftingTriggered = executePlacement(serverPlayer, stack, level, p1, targetPos);
                }
                // Only clear points if crafting was NOT triggered
                if (!craftingTriggered) {
                    setPoint1(stack, null);
                    setPoint2(stack, null);
                    // Sync cleared points to client
                    syncPointsToClient(serverPlayer, slot);
                }
            }
        } else {
            // PLANE_FILL uses 2 points
            if (p1 == null) {
                setPoint1(stack, targetPos);
                player.displayClientMessage(Component.translatable("message.meplacementtool.point1_set", targetPos.toShortString()), true);
                syncPointsToClient(serverPlayer, slot);
            } else {
                setPoint2(stack, targetPos);
                player.displayClientMessage(Component.translatable("message.meplacementtool.point2_set", targetPos.toShortString()), true);
                boolean craftingTriggered = executePlacement(serverPlayer, stack, level, p1, targetPos);
                // Only clear points if crafting was NOT triggered
                if (!craftingTriggered) {
                    setPoint1(stack, null);
                    setPoint2(stack, null);
                    // Sync cleared points to client
                    syncPointsToClient(serverPlayer, slot);
                }
            }
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Find an available cable key from ME storage.
     * Priority: same color > any other color
     */
    private AEItemKey findAvailableCableKey(MEStorage storage, PlayerSource src, CableType cableType, AEColor preferredColor) {
        // First try: preferred (same) color
        ItemStack preferredStack = cableType.getStack(preferredColor);
        AEItemKey preferredKey = AEItemKey.of(preferredStack);
        if (storage.extract(preferredKey, 1, Actionable.SIMULATE, src) >= 1) {
            return preferredKey;
        }
        
        // Second try: any other color
        for (AEColor c : AEColor.values()) {
            if (c == preferredColor) continue;
            ItemStack stack = cableType.getStack(c);
            AEItemKey key = AEItemKey.of(stack);
            if (storage.extract(key, 1, Actionable.SIMULATE, src) >= 1) {
                return key;
            }
        }
        
        return null;
    }

    /**
     * Get the AEColor from a cable AEItemKey.
     */
    private AEColor getColorFromCableKey(AEItemKey key, CableType cableType) {
        for (AEColor c : AEColor.values()) {
            ItemStack stack = cableType.getStack(c);
            if (AEItemKey.of(stack).equals(key)) {
                return c;
            }
        }
        return AEColor.TRANSPARENT;
    }

    /**
     * Execute cable placement.
     * @return true if crafting was triggered (points should be preserved), false otherwise
     */
    private boolean executePlacement(ServerPlayer player, ItemStack tool, Level level, BlockPos p1, BlockPos p2) {
        PlacementMode mode = getMode(tool);
        CableType cableType = getCableType(tool);

        // Determine effective color
        ColorLogicResult colorLogic = determineColorLogic(player, tool);
        AEColor color = colorLogic.color;

        List<BlockPos> positions = calculatePositions(p1, p2, mode);
        if (positions.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.no_positions"), true);
            return false;
        }

        // Check Power
        double energyCost = Config.cablePlacementToolEnergyCost * positions.size();
        if (!this.hasPower(player, energyCost, tool)) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.device_not_powered"), true);
            return false;
        }

        // Check Network
        IGrid grid = this.getLinkedGrid(tool, level, player);
        if (grid == null) return false;
        MEStorage storage = grid.getStorageService().getInventory();
        PlayerSource src = new PlayerSource(player);

        ItemStack placeCableStack = cableType.getStack(color);

        // Pre-check: Count how many positions actually need cables (filter valid positions)
        List<BlockPos> validPositions = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (canPlaceCableAt(level, pos)) {
                validPositions.add(pos);
            }
        }
        
        if (validPositions.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.no_positions"), true);
            return false;
        }
        
        int totalNeeded = validPositions.size();
        
        // Pre-check: Count total available cables in network (any color of this type)
        long totalAvailable = 0;
        for (AEColor c : AEColor.values()) {
            ItemStack stack = cableType.getStack(c);
            AEItemKey key = AEItemKey.of(stack);
            totalAvailable += storage.extract(key, totalNeeded, Actionable.SIMULATE, src);
            if (totalAvailable >= totalNeeded) break;
        }
        
        // If not enough cables, trigger crafting BEFORE placing anything
        if (totalAvailable < totalNeeded) {
            // Try to craft Fluix (TRANSPARENT) cable as it's the base type
            var fluixCableStack = cableType.getStack(AEColor.TRANSPARENT);
            var craftKey = AEItemKey.of(fluixCableStack);
            var craftingService = grid.getCraftingService();
            if (craftingService != null && craftKey != null && craftingService.isCraftable(craftKey)) {
                int missingAmount = (int) (totalNeeded - totalAvailable);
                openCraftingMenu(player, tool, craftKey, missingAmount);
                return true; // Crafting triggered, preserve all points
            }
            player.displayClientMessage(Component.translatable("message.meplacementtool.missing_cable", placeCableStack.getHoverName()), true);
            return false;
        }

        // Now we know we have enough cables, proceed with placement
        int placedCount = 0;
        int dyeConsumed = 0;
        List<UndoHistory.CablePlacementSnapshot> placedSnapshots = new ArrayList<>();

        for (BlockPos pos : validPositions) {
            AEItemKey keyToExtract = findAvailableCableKey(storage, src, cableType, color);
            if (keyToExtract == null) {
                player.displayClientMessage(Component.translatable("message.meplacementtool.missing_cable", placeCableStack.getHoverName()), true);
                break;
            }

            AEColor extractedColor = getColorFromCableKey(keyToExtract, cableType);
            boolean needsDyeForThis = colorLogic.needsDye && (extractedColor != color);

            if (needsDyeForThis && color != AEColor.TRANSPARENT) {
                if ((dyeConsumed == 0 || placedCount % 8 == 0) && dyeConsumed < (placedCount / 8) + 1) {
                    if (!consumeDye(player, storage, src, color, 1)) {
                        player.displayClientMessage(Component.translatable("message.meplacementtool.missing_dye", 1, DyeItem.byColor(color.dye).getDescription()), true);
                        break;
                    }
                    dyeConsumed++;
                }
            }

            if (placeCable(player, (ServerLevel) level, pos, placeCableStack)) {
                storage.extract(keyToExtract, 1, Actionable.MODULATE, src);
                placedCount++;
                placedSnapshots.add(new UndoHistory.CablePlacementSnapshot(pos, cableType, keyToExtract));
            }
        }

        if (placedCount > 0) {
            this.usePower(player, Config.cablePlacementToolEnergyCost * placedCount, tool);
            player.displayClientMessage(Component.translatable("message.meplacementtool.placed_count", placedCount), true);
            
            if (!placedSnapshots.isEmpty()) {
                BlockPos soundPos = placedSnapshots.get(0).pos;
                var placedState = level.getBlockState(soundPos);
                var soundType = placedState.getSoundType(level, soundPos, player);
                level.playSound(null, soundPos, soundType.getPlaceSound(), SoundSource.BLOCKS, 
                    (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
            }
            
            // Add to undo history
            MEPlacementToolMod.instance.undoHistory.addCablePlacement(player, level, placedSnapshots);
        }
        return false; // Normal completion, can clear points
    }

    private boolean placeCable(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack cableStack) {
        try {
            IPartItem<?> partItem = (IPartItem<?>) cableStack.getItem();
            if (PartPlacement.placePart(player, level, partItem, null, pos, Direction.UP) != null) {
                return true;
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Execute branch placement using 3 points.
     * @return true if crafting was triggered (points should be preserved), false otherwise
     */
    private boolean executeBranchPlacement(ServerPlayer player, ItemStack tool, Level level, BlockPos p1, BlockPos p2, BlockPos p3) {
        CableType cableType = getCableType(tool);
        
        ColorLogicResult colorLogic = determineColorLogic(player, tool);
        AEColor color = colorLogic.color;

        List<BlockPos> positions = calculateBranchPositions(p1, p2, p3);
        if (positions.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.no_positions"), true);
            return false;
        }

        double energyCost = Config.cablePlacementToolEnergyCost * positions.size();
        if (!this.hasPower(player, energyCost, tool)) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.device_not_powered"), true);
            return false;
        }

        IGrid grid = this.getLinkedGrid(tool, level, player);
        if (grid == null) return false;
        MEStorage storage = grid.getStorageService().getInventory();
        PlayerSource src = new PlayerSource(player);

        ItemStack placeCableStack = cableType.getStack(color);

        // Pre-check: Count how many positions actually need cables (filter valid positions)
        List<BlockPos> validPositions = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (canPlaceCableAt(level, pos)) {
                validPositions.add(pos);
            }
        }
        
        if (validPositions.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.meplacementtool.no_positions"), true);
            return false;
        }
        
        int totalNeeded = validPositions.size();
        
        // Pre-check: Count total available cables in network (any color of this type)
        long totalAvailable = 0;
        for (AEColor c : AEColor.values()) {
            ItemStack stack = cableType.getStack(c);
            AEItemKey key = AEItemKey.of(stack);
            totalAvailable += storage.extract(key, totalNeeded, Actionable.SIMULATE, src);
            if (totalAvailable >= totalNeeded) break;
        }
        
        // If not enough cables, trigger crafting BEFORE placing anything
        if (totalAvailable < totalNeeded) {
            // Try to craft Fluix (TRANSPARENT) cable as it's the base type
            var fluixCableStack = cableType.getStack(AEColor.TRANSPARENT);
            var craftKey = AEItemKey.of(fluixCableStack);
            var craftingService = grid.getCraftingService();
            if (craftingService != null && craftKey != null && craftingService.isCraftable(craftKey)) {
                int missingAmount = (int) (totalNeeded - totalAvailable);
                openCraftingMenu(player, tool, craftKey, missingAmount);
                return true; // Crafting triggered, preserve all points
            }
            player.displayClientMessage(Component.translatable("message.meplacementtool.missing_cable", placeCableStack.getHoverName()), true);
            return false;
        }

        // Now we know we have enough cables, proceed with placement
        int placedCount = 0;
        int dyeConsumed = 0;
        List<UndoHistory.CablePlacementSnapshot> placedSnapshots = new ArrayList<>();
        
        for (BlockPos pos : validPositions) {
            AEItemKey keyToExtract = findAvailableCableKey(storage, src, cableType, color);
            if (keyToExtract == null) {
                player.displayClientMessage(Component.translatable("message.meplacementtool.missing_cable", placeCableStack.getHoverName()), true);
                break;
            }

            AEColor extractedColor = getColorFromCableKey(keyToExtract, cableType);
            boolean needsDyeForThis = colorLogic.needsDye && (extractedColor != color);

            if (needsDyeForThis && color != AEColor.TRANSPARENT) {
                if ((dyeConsumed == 0 || placedCount % 8 == 0) && dyeConsumed < (placedCount / 8) + 1) {
                    if (!consumeDye(player, storage, src, color, 1)) {
                        player.displayClientMessage(Component.translatable("message.meplacementtool.missing_dye", 1, DyeItem.byColor(color.dye).getDescription()), true);
                        break;
                    }
                    dyeConsumed++;
                }
            }

            if (placeCable(player, (ServerLevel) level, pos, placeCableStack)) {
                storage.extract(keyToExtract, 1, Actionable.MODULATE, src);
                placedCount++;
                placedSnapshots.add(new UndoHistory.CablePlacementSnapshot(pos, cableType, keyToExtract));
            }
        }

        if (placedCount > 0) {
            this.usePower(player, Config.cablePlacementToolEnergyCost * placedCount, tool);
            player.displayClientMessage(Component.translatable("message.meplacementtool.placed_count", placedCount), true);
            
            if (!placedSnapshots.isEmpty()) {
                BlockPos soundPos = placedSnapshots.get(0).pos;
                var placedState = level.getBlockState(soundPos);
                var soundType = placedState.getSoundType(level, soundPos, player);
                level.playSound(null, soundPos, soundType.getPlaceSound(), SoundSource.BLOCKS, 
                    (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
            }
            
            // Add to undo history
            MEPlacementToolMod.instance.undoHistory.addCablePlacement(player, level, placedSnapshots);
        }
        return false; // Normal completion, can clear points
    }

    /**
     * Calculate positions for cable placement based on the selected mode.
     */
    public static List<BlockPos> calculatePositions(BlockPos p1, BlockPos p2, PlacementMode mode) {
        List<BlockPos> list = new ArrayList<>();
        int x1 = p1.getX(), y1 = p1.getY(), z1 = p1.getZ();
        int x2 = p2.getX(), y2 = p2.getY(), z2 = p2.getZ();

        if (mode == PlacementMode.LINE) {
            return getLineBlocks(x1, y1, z1, x2, y2, z2);
        } else if (mode == PlacementMode.PLANE_FILL) {
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        list.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return list;
    }

    /**
     * Calculate branching positions using 3 points.
     */
    public static List<BlockPos> calculateBranchPositions(BlockPos p1, BlockPos p2, BlockPos p3) {
        List<BlockPos> list = new ArrayList<>();
        
        int x1 = p1.getX(), y1 = p1.getY(), z1 = p1.getZ();
        int x2 = p2.getX(), y2 = p2.getY(), z2 = p2.getZ();
        int x3 = p3.getX(), y3 = p3.getY(), z3 = p3.getZ();
        
        int dx12 = x2 - x1, dy12 = y2 - y1, dz12 = z2 - z1;
        int absDx = Math.abs(dx12), absDy = Math.abs(dy12), absDz = Math.abs(dz12);
        
        int trunkAxis, trunkDir, interval;
        
        if (absDx >= absDy && absDx >= absDz) {
            trunkAxis = 0;
            trunkDir = dx12 == 0 ? 1 : Integer.signum(dx12);
            interval = Math.max(1, absDx);
        } else if (absDy >= absDx && absDy >= absDz) {
            trunkAxis = 1;
            trunkDir = dy12 == 0 ? 1 : Integer.signum(dy12);
            interval = Math.max(1, absDy);
        } else {
            trunkAxis = 2;
            trunkDir = dz12 == 0 ? 1 : Integer.signum(dz12);
            interval = Math.max(1, absDz);
        }
        
        int dx13 = x3 - x1, dy13 = y3 - y1, dz13 = z3 - z1;
        
        int branchAxis, branchDir, trunkLength, branchLength;
        
        if (trunkAxis == 0) {
            trunkLength = Math.abs(dx13);
            if (Math.abs(dy13) >= Math.abs(dz13)) {
                branchAxis = 1;
                branchLength = Math.abs(dy13);
                branchDir = dy13 == 0 ? 1 : Integer.signum(dy13);
            } else {
                branchAxis = 2;
                branchLength = Math.abs(dz13);
                branchDir = dz13 == 0 ? 1 : Integer.signum(dz13);
            }
        } else if (trunkAxis == 1) {
            trunkLength = Math.abs(dy13);
            if (Math.abs(dx13) >= Math.abs(dz13)) {
                branchAxis = 0;
                branchLength = Math.abs(dx13);
                branchDir = dx13 == 0 ? 1 : Integer.signum(dx13);
            } else {
                branchAxis = 2;
                branchLength = Math.abs(dz13);
                branchDir = dz13 == 0 ? 1 : Integer.signum(dz13);
            }
        } else {
            trunkLength = Math.abs(dz13);
            if (Math.abs(dx13) >= Math.abs(dy13)) {
                branchAxis = 0;
                branchLength = Math.abs(dx13);
                branchDir = dx13 == 0 ? 1 : Integer.signum(dx13);
            } else {
                branchAxis = 1;
                branchLength = Math.abs(dy13);
                branchDir = dy13 == 0 ? 1 : Integer.signum(dy13);
            }
        }
        
        for (int t = 0; t <= trunkLength; t++) {
            int tx = x1, ty = y1, tz = z1;
            
            if (trunkAxis == 0) tx = x1 + t * trunkDir;
            else if (trunkAxis == 1) ty = y1 + t * trunkDir;
            else tz = z1 + t * trunkDir;
            
            list.add(new BlockPos(tx, ty, tz));
            
            if (t % interval == 0) {
                for (int b = 1; b <= branchLength; b++) {
                    int bx = tx, by = ty, bz = tz;
                    
                    if (branchAxis == 0) bx = tx + b * branchDir;
                    else if (branchAxis == 1) by = ty + b * branchDir;
                    else bz = tz + b * branchDir;
                    
                    list.add(new BlockPos(bx, by, bz));
                }
            }
        }
        
        return list;
    }

    // ==================== Network Sync ====================

    /**
     * Sync current points state to client after placement completes.
     * This ensures client has correct state even if Data Components sync is delayed.
     */
    public static void syncPointsToClient(ServerPlayer player, int slot) {
        ItemStack stack = slot == Inventory.SLOT_OFFHAND
                ? player.getOffhandItem()
                : player.getInventory().getItem(slot);
        if (stack.getItem() != MEPlacementToolMod.ME_CABLE_PLACEMENT_TOOL.get()) {
            return;
        }
        
        BlockPos p1 = getPoint1(stack);
        BlockPos p2 = getPoint2(stack);
        BlockPos p3 = getPoint3(stack);
        
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
            player,
            com.moakiee.meplacementtool.network.SyncCableToolPointsPayload.create(slot, p1, p2, p3)
        );
    }

    // ==================== Data Component Accessors ====================

    public static void setPoint1(ItemStack stack, @Nullable BlockPos pos) {
        CompoundTag tag = stack.getOrDefault(ModDataComponents.CABLE_POINTS.get(), new CompoundTag());
        if (pos == null) {
            tag.remove("Point1");
        } else {
            tag.put("Point1", NbtUtils.writeBlockPos(pos));
        }
        stack.set(ModDataComponents.CABLE_POINTS.get(), tag);
    }

    @Nullable
    public static BlockPos getPoint1(ItemStack stack) {
        CompoundTag tag = stack.get(ModDataComponents.CABLE_POINTS.get());
        if (tag != null && tag.contains("Point1")) {
            return NbtUtils.readBlockPos(tag, "Point1").orElse(null);
        }
        return null;
    }

    public static void setPoint2(ItemStack stack, @Nullable BlockPos pos) {
        CompoundTag tag = stack.getOrDefault(ModDataComponents.CABLE_POINTS.get(), new CompoundTag());
        if (pos == null) {
            tag.remove("Point2");
        } else {
            tag.put("Point2", NbtUtils.writeBlockPos(pos));
        }
        stack.set(ModDataComponents.CABLE_POINTS.get(), tag);
    }

    @Nullable
    public static BlockPos getPoint2(ItemStack stack) {
        CompoundTag tag = stack.get(ModDataComponents.CABLE_POINTS.get());
        if (tag != null && tag.contains("Point2")) {
            return NbtUtils.readBlockPos(tag, "Point2").orElse(null);
        }
        return null;
    }

    public static void setPoint3(ItemStack stack, @Nullable BlockPos pos) {
        CompoundTag tag = stack.getOrDefault(ModDataComponents.CABLE_POINTS.get(), new CompoundTag());
        if (pos == null) {
            tag.remove("Point3");
        } else {
            tag.put("Point3", NbtUtils.writeBlockPos(pos));
        }
        stack.set(ModDataComponents.CABLE_POINTS.get(), tag);
    }

    @Nullable
    public static BlockPos getPoint3(ItemStack stack) {
        CompoundTag tag = stack.get(ModDataComponents.CABLE_POINTS.get());
        if (tag != null && tag.contains("Point3")) {
            return NbtUtils.readBlockPos(tag, "Point3").orElse(null);
        }
        return null;
    }

    public static void clearAllPoints(ItemStack stack) {
        stack.remove(ModDataComponents.CABLE_POINTS.get());
    }

    public static PlacementMode getMode(ItemStack stack) {
        Integer mode = stack.get(ModDataComponents.CABLE_TOOL_MODE.get());
        if (mode != null && mode >= 0 && mode < PlacementMode.values().length) {
            return PlacementMode.values()[mode];
        }
        return PlacementMode.LINE;
    }

    public static void setMode(ItemStack stack, PlacementMode mode) {
        stack.set(ModDataComponents.CABLE_TOOL_MODE.get(), mode.ordinal());
        clearAllPoints(stack);
    }

    public static CableType getCableType(ItemStack stack) {
        Integer type = stack.get(ModDataComponents.CABLE_TYPE.get());
        if (type != null && type >= 0 && type < CableType.values().length) {
            return CableType.values()[type];
        }
        return CableType.GLASS;
    }

    public static void setCableType(ItemStack stack, CableType type) {
        stack.set(ModDataComponents.CABLE_TYPE.get(), type.ordinal());
    }

    public static AEColor getColor(ItemStack stack) {
        Integer color = stack.get(ModDataComponents.CABLE_COLOR.get());
        if (color != null && color >= 0 && color < AEColor.values().length) {
            return AEColor.values()[color];
        }
        return AEColor.TRANSPARENT;
    }

    public static void setColor(ItemStack stack, AEColor color) {
        stack.set(ModDataComponents.CABLE_COLOR.get(), color.ordinal());
    }

    public static boolean hasUpgrade(ItemStack stack) {
        Boolean has = stack.get(ModDataComponents.HAS_UPGRADE.get());
        return has != null && has;
    }

    public static void setUpgrade(ItemStack stack, boolean has) {
        stack.set(ModDataComponents.HAS_UPGRADE.get(), has);
    }

    /**
     * Get the color shortcuts array from the tool stack.
     * @return Array of 5 color indices (-1 = empty slot)
     */
    public static int[] getColorShortcuts(ItemStack stack) {
        CompoundTag tag = stack.get(ModDataComponents.COLOR_SHORTCUTS.get());
        if (tag != null && tag.contains("shortcuts")) {
            return tag.getIntArray("shortcuts");
        }
        return new int[]{-1, -1, -1, -1, -1}; // Default: all empty
    }

    /**
     * Set the color shortcuts array to the tool stack.
     * @param shortcuts Array of 5 color indices (-1 = empty slot)
     */
    public static void setColorShortcuts(ItemStack stack, int[] shortcuts) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("shortcuts", shortcuts);
        stack.set(ModDataComponents.COLOR_SHORTCUTS.get(), tag);
    }

    // ==================== Color Logic ====================

    private static class ColorLogicResult {
        AEColor color;
        boolean needsDye;

        ColorLogicResult(AEColor color, boolean needsDye) {
            this.color = color;
            this.needsDye = needsDye;
        }
    }

    @Nullable
    private AEColor getDyeColorFromStack(ItemStack stack) {
        if (!stack.isEmpty() && stack.getItem() instanceof DyeItem dyeItem) {
            return AEColor.fromDye(dyeItem.getDyeColor());
        }
        return null;
    }

    private ColorLogicResult determineColorLogic(Player player, ItemStack tool) {
        ItemStack offhandStack = player.getItemInHand(InteractionHand.OFF_HAND);
        AEColor offhandDyeColor = getDyeColorFromStack(offhandStack);
        boolean hasUpgradeItem = hasUpgrade(tool);
        AEColor selectedColor = getColor(tool);

        if (offhandDyeColor != null) {
            if (hasUpgradeItem) {
                return new ColorLogicResult(offhandDyeColor, false);
            } else {
                return new ColorLogicResult(offhandDyeColor, true);
            }
        } else {
            if (hasUpgradeItem) {
                return new ColorLogicResult(selectedColor, false);
            } else {
                return new ColorLogicResult(AEColor.TRANSPARENT, false);
            }
        }
    }

    private boolean consumeDye(Player player, MEStorage storage, PlayerSource src, AEColor color, int amount) {
        if (amount <= 0 || color == AEColor.TRANSPARENT) return true;

        DyeItem dyeItem = (DyeItem) DyeItem.byColor(color.dye);
        AEItemKey dyeKey = AEItemKey.of(dyeItem);

        int remaining = amount;
        
        long takenFromAE = storage.extract(dyeKey, remaining, Actionable.MODULATE, src);
        remaining -= takenFromAE;
        if (remaining <= 0) return true;

        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack slotStack = player.getInventory().items.get(i);
            if (getDyeColorFromStack(slotStack) == color) {
                int take = Math.min(remaining, slotStack.getCount());
                slotStack.shrink(take);
                remaining -= take;
                if (remaining <= 0) break;
            }
        }
        if (remaining <= 0) return true;

        ItemStack offhand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (getDyeColorFromStack(offhand) == color) {
             int take = Math.min(remaining, offhand.getCount());
             offhand.shrink(take);
             remaining -= take;
        }

        return remaining <= 0;
    }

    // ==================== Line Mode ====================

    public static BlockPos findLine(Player player, BlockPos firstPos) {
        Vec3 look = getPlayerLookVec(player);
        Vec3 start = new Vec3(player.getX(), player.getY() + player.getEyeHeight(), player.getZ());

        List<LineCriteria> criteriaList = new ArrayList<>(3);

        // Use block center (add 0.5) for accurate line calculation
        double centerX = firstPos.getX() + 0.5;
        double centerY = firstPos.getY() + 0.5;
        double centerZ = firstPos.getZ() + 0.5;

        Vec3 xBound = findXBound(centerX, start, look);
        criteriaList.add(new LineCriteria(xBound, firstPos, start));

        Vec3 yBound = findYBound(centerY, start, look);
        criteriaList.add(new LineCriteria(yBound, firstPos, start));

        Vec3 zBound = findZBound(centerZ, start, look);
        criteriaList.add(new LineCriteria(zBound, firstPos, start));

        int reach = 64;
        criteriaList.removeIf(c -> !c.isValid(start, look, reach));

        if (criteriaList.isEmpty()) {
            // Fallback: return a point along player's look direction from firstPos
            return findLineFallback(player, firstPos);
        }

        LineCriteria selected = criteriaList.get(0);
        if (criteriaList.size() > 1) {
            for (int i = 1; i < criteriaList.size(); i++) {
                LineCriteria criteria = criteriaList.get(i);
                if (criteria.distToLineSq < 2.0 && selected.distToLineSq < 2.0) {
                    if (criteria.distToPlayerSq < selected.distToPlayerSq)
                        selected = criteria;
                } else {
                    if (criteria.distToLineSq < selected.distToLineSq)
                        selected = criteria;
                }
            }
        }

        return BlockPos.containing(selected.lineBound);
    }

    public static List<BlockPos> getLineBlocks(int x1, int y1, int z1, int x2, int y2, int z2) {
        List<BlockPos> list = new ArrayList<>();

        if (x1 != x2) {
            for (int x = x1; x1 < x2 ? x <= x2 : x >= x2; x += x1 < x2 ? 1 : -1) {
                list.add(new BlockPos(x, y1, z1));
            }
        } else if (y1 != y2) {
            for (int y = y1; y1 < y2 ? y <= y2 : y >= y2; y += y1 < y2 ? 1 : -1) {
                list.add(new BlockPos(x1, y, z1));
            }
        } else {
            for (int z = z1; z1 < z2 ? z <= z2 : z >= z2; z += z1 < z2 ? 1 : -1) {
                list.add(new BlockPos(x1, y1, z));
            }
        }

        return list;
    }

    private static Vec3 getPlayerLookVec(Player player) {
        Vec3 lookVec = player.getLookAngle();
        double x = lookVec.x, y = lookVec.y, z = lookVec.z;

        if (Math.abs(x) < 0.0001) x = 0.0001;
        if (Math.abs(x - 1.0) < 0.0001) x = 0.9999;
        if (Math.abs(x + 1.0) < 0.0001) x = -0.9999;

        if (Math.abs(y) < 0.0001) y = 0.0001;
        if (Math.abs(y - 1.0) < 0.0001) y = 0.9999;
        if (Math.abs(y + 1.0) < 0.0001) y = -0.9999;

        if (Math.abs(z) < 0.0001) z = 0.0001;
        if (Math.abs(z - 1.0) < 0.0001) z = 0.9999;
        if (Math.abs(z + 1.0) < 0.0001) z = -0.9999;

        return new Vec3(x, y, z);
    }

    /**
     * Fallback method when normal line finding fails.
     * Uses player's dominant look direction to find a line endpoint.
     */
    private static BlockPos findLineFallback(Player player, BlockPos firstPos) {
        Vec3 look = player.getLookAngle();
        double ax = Math.abs(look.x);
        double ay = Math.abs(look.y);
        double az = Math.abs(look.z);
        
        // Find dominant axis
        int distance = 8; // Default preview distance
        if (ax >= ay && ax >= az) {
            // X is dominant
            int dir = look.x > 0 ? 1 : -1;
            return new BlockPos(firstPos.getX() + dir * distance, firstPos.getY(), firstPos.getZ());
        } else if (ay >= ax && ay >= az) {
            // Y is dominant
            int dir = look.y > 0 ? 1 : -1;
            return new BlockPos(firstPos.getX(), firstPos.getY() + dir * distance, firstPos.getZ());
        } else {
            // Z is dominant
            int dir = look.z > 0 ? 1 : -1;
            return new BlockPos(firstPos.getX(), firstPos.getY(), firstPos.getZ() + dir * distance);
        }
    }

    private static Vec3 findXBound(double x, Vec3 start, Vec3 look) {
        double y = (x - start.x) / look.x * look.y + start.y;
        double z = (x - start.x) / look.x * look.z + start.z;
        return new Vec3(x, y, z);
    }

    private static Vec3 findYBound(double y, Vec3 start, Vec3 look) {
        double x = (y - start.y) / look.y * look.x + start.x;
        double z = (y - start.y) / look.y * look.z + start.z;
        return new Vec3(x, y, z);
    }

    private static Vec3 findZBound(double z, Vec3 start, Vec3 look) {
        double x = (z - start.z) / look.z * look.x + start.x;
        double y = (z - start.z) / look.z * look.y + start.y;
        return new Vec3(x, y, z);
    }

    private static class LineCriteria {
        Vec3 planeBound;
        Vec3 lineBound;
        double distToLineSq;
        double distToPlayerSq;

        LineCriteria(Vec3 planeBound, BlockPos firstPos, Vec3 start) {
            this.planeBound = planeBound;
            this.lineBound = toLongestLine(planeBound, firstPos);
            this.distToLineSq = this.lineBound.subtract(planeBound).lengthSqr();
            this.distToPlayerSq = planeBound.subtract(start).lengthSqr();
        }

        private Vec3 toLongestLine(Vec3 boundVec, BlockPos firstPos) {
            int bx = (int) Math.floor(boundVec.x);
            int by = (int) Math.floor(boundVec.y);
            int bz = (int) Math.floor(boundVec.z);

            int dx = Math.abs(bx - firstPos.getX());
            int dy = Math.abs(by - firstPos.getY());
            int dz = Math.abs(bz - firstPos.getZ());

            int longest = Math.max(dx, Math.max(dy, dz));
            if (longest == dx && dx > 0) {
                return new Vec3(bx, firstPos.getY(), firstPos.getZ());
            }
            if (longest == dy && dy > 0) {
                return new Vec3(firstPos.getX(), by, firstPos.getZ());
            }
            if (longest == dz && dz > 0) {
                return new Vec3(firstPos.getX(), firstPos.getY(), bz);
            }
            return new Vec3(firstPos.getX(), firstPos.getY(), firstPos.getZ());
        }

        boolean isValid(Vec3 start, Vec3 look, int reach) {
            // Check if the point is in front of the player (positive dot product means in view direction)
            if (planeBound.subtract(start).dot(look) <= 0) return false;
            // Allow close range (removed distToPlayerSq < 1 check) but limit max reach
            if (distToPlayerSq > reach * reach) return false;
            return true;
        }
    }
}
