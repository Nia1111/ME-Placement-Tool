package com.moakiee.meplacementtool.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

import com.moakiee.meplacementtool.ItemMultiblockPlacementTool;
import com.moakiee.meplacementtool.ItemMultiblockPlacementTool.DirectionMode;
import com.moakiee.meplacementtool.MEPlacementToolMod;

import java.util.*;

/**
 * Renders preview of multiblock placement positions
 */
public class MultiblockPreviewRenderer {
    private BlockHitResult lastRayTraceResult;
    private ItemStack lastWand;
    private Set<BlockPos> cachedPositions;
    private int lastPlacementCount;
    private DirectionMode lastDirectionMode;

    @SubscribeEvent
    public void renderBlockHighlight(RenderHighlightEvent.Block event) {
        if (event.getTarget().getType() != HitResult.Type.BLOCK) return;

        BlockHitResult rtr = event.getTarget();
        Entity entity = event.getCamera().getEntity();
        if (!(entity instanceof Player player)) return;

        ItemStack wand = player.getMainHandItem();
        if (wand.isEmpty() || wand.getItem() != MEPlacementToolMod.MULTIBLOCK_PLACEMENT_TOOL.get()) return;

        int placementCount = ItemMultiblockPlacementTool.getPlacementCount(wand);
        DirectionMode directionMode = ItemMultiblockPlacementTool.getDirectionMode(wand);

        Set<BlockPos> blocks;
        if (cachedPositions == null || !compareRTR(lastRayTraceResult, rtr) ||
                !ItemStack.matches(lastWand, wand) || lastPlacementCount != placementCount ||
                lastDirectionMode != directionMode) {
            blocks = calculatePlacementPositions(player, rtr, wand, placementCount, directionMode);
            cachedPositions = blocks;
            lastRayTraceResult = rtr;
            lastWand = wand.copy();
            lastPlacementCount = placementCount;
            lastDirectionMode = directionMode;
        } else {
            blocks = cachedPositions;
        }

        if (blocks == null || blocks.isEmpty()) return;

        PoseStack ms = event.getPoseStack();
        MultiBufferSource buffer = event.getMultiBufferSource();
        VertexConsumer lineBuilder = buffer.getBuffer(RenderType.LINES);

        Camera camera = event.getCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        for (BlockPos block : blocks) {
            AABB aabb = new AABB(block).move(-camX, -camY, -camZ);
            LevelRenderer.renderLineBox(ms, lineBuilder, aabb, 0.0F, 0.75F, 1.0F, 0.4F);
        }

        event.setCanceled(true);
    }

    private Set<BlockPos> calculatePlacementPositions(Player player, BlockHitResult rtr, ItemStack wand, int placementCount, DirectionMode directionMode) {
        Set<BlockPos> placePositions = new HashSet<>();
        if (placementCount <= 0) return placePositions;

        Level level = player.level();
        BlockPos clickedPos = rtr.getBlockPos();
        Direction clickedFace = rtr.getDirection();
        var clickedState = level.getBlockState(clickedPos);

        LinkedList<BlockPos> candidates = new LinkedList<>();
        Set<BlockPos> allCandidates = new HashSet<>();
        ArrayList<BlockPos> positions = new ArrayList<>();

        // MAX_CANDIDATES limit to prevent infinite loops when placeable positions are fewer than requested
        final int MAX_CANDIDATES = placementCount * 10;

        BlockPos startingPoint = clickedPos.relative(clickedFace);
        candidates.add(startingPoint);

        while (!candidates.isEmpty() && positions.size() < placementCount && allCandidates.size() < MAX_CANDIDATES) {
            BlockPos currentCandidate = candidates.removeFirst();
            if (!allCandidates.add(currentCandidate)) {
                continue;
            }

            // Match ConstructionWand: even when direction is locked, the supporting block
            // (opposite the clicked face) must equal the clicked block.
            BlockPos supportingPoint = currentCandidate.relative(clickedFace.getOpposite());
            var supportingState = level.getBlockState(supportingPoint);

            if (supportingState.getBlock() == clickedState.getBlock()) {
                var currentState = level.getBlockState(currentCandidate);
                boolean canPlace = level.isEmptyBlock(currentCandidate);
                if (!canPlace) {
                    try {
                        var checkContext = new BlockPlaceContext(new UseOnContext(
                                player, InteractionHand.MAIN_HAND, new BlockHitResult(
                                        rtr.getLocation(), rtr.getDirection(), currentCandidate, rtr.isInside()
                                )
                        ));
                        canPlace = currentState.canBeReplaced(checkContext);
                    } catch (Throwable t) {}
                }
                if (canPlace) {
                    positions.add(currentCandidate);
                    // Only expand candidates after successful placement (prevents cross-pit overflow)
                    addAdjacentPositions(candidates, currentCandidate, clickedFace, directionMode);
                }
            }
        }

        placePositions.addAll(positions);
        return placePositions;
    }

    private void addAdjacentPositions(LinkedList<BlockPos> candidates, BlockPos pos, Direction face, DirectionMode directionMode) {
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

    private void addAutoAdjacentPositions(LinkedList<BlockPos> candidates, BlockPos pos, Direction face) {
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
                candidates.add(pos.above());
                candidates.add(pos.below());
                candidates.add(pos.east());
                candidates.add(pos.west());
                candidates.add(pos.above().east());
                candidates.add(pos.above().west());
                candidates.add(pos.below().east());
                candidates.add(pos.below().west());
            }
            case EAST, WEST -> {
                candidates.add(pos.above());
                candidates.add(pos.below());
                candidates.add(pos.north());
                candidates.add(pos.south());
                candidates.add(pos.above().north());
                candidates.add(pos.above().south());
                candidates.add(pos.below().north());
                candidates.add(pos.below().south());
            }
        }
    }

    private static boolean compareRTR(BlockHitResult rtr1, BlockHitResult rtr2) {
        if (rtr1 == null || rtr2 == null) return false;
        return rtr1.getBlockPos().equals(rtr2.getBlockPos()) && rtr1.getDirection().equals(rtr2.getDirection());
    }

    public void reset() {
        cachedPositions = null;
        lastRayTraceResult = null;
        lastWand = null;
        lastPlacementCount = 0;
        lastDirectionMode = null;
    }
}
