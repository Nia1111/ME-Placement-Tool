package com.moakiee.meplacementtool.network;

import com.moakiee.meplacementtool.BasePlacementToolItem;
import com.moakiee.meplacementtool.ItemMultiblockPlacementTool;
import com.moakiee.meplacementtool.MEPlacementToolMod;
import com.moakiee.meplacementtool.ModDataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Payload for updating placement count from client to server
 */
public record UpdatePlacementCountPayload(int count) implements CustomPacketPayload {
    public static final Type<UpdatePlacementCountPayload> TYPE = 
            new Type<>(ResourceLocation.fromNamespaceAndPath(MEPlacementToolMod.MODID, "update_placement_count"));

    public static final StreamCodec<FriendlyByteBuf, UpdatePlacementCountPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT,
                    UpdatePlacementCountPayload::count,
                    UpdatePlacementCountPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UpdatePlacementCountPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            // PlacementCount is only meaningful on the multiblock tool; check both hands.
            ItemStack stack = BasePlacementToolItem.findHeldTool(player, ItemMultiblockPlacementTool.class);
            if (stack.isEmpty()) return;

            // Update placement count in config
            CompoundTag cfg = stack.get(ModDataComponents.PLACEMENT_CONFIG.get());
            if (cfg == null) cfg = new CompoundTag();
            else cfg = cfg.copy(); // Copy to avoid mutating immutable component data directly if caught by wrappers

            // Use "PlacementCount" to match ItemMultiblockPlacementTool.getPlacementCount()
            cfg.putInt("PlacementCount", payload.count());
            stack.set(ModDataComponents.PLACEMENT_CONFIG.get(), cfg);
        });
    }
}
