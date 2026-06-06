package com.moakiee.meplacementtool.network;

import com.moakiee.meplacementtool.BasePlacementToolItem;
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
 * Payload for updating wand configuration from client to server
 */
public record UpdateWandConfigPayload(CompoundTag tag) implements CustomPacketPayload {
    public static final Type<UpdateWandConfigPayload> TYPE = 
            new Type<>(ResourceLocation.fromNamespaceAndPath(MEPlacementToolMod.MODID, "update_wand_config"));

    public static final StreamCodec<FriendlyByteBuf, UpdateWandConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.COMPOUND_TAG,
                    UpdateWandConfigPayload::tag,
                    UpdateWandConfigPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UpdateWandConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            // Wand config applies to any placement tool subclass (ME or multiblock); allow either hand.
            ItemStack stack = BasePlacementToolItem.findHeldTool(player, BasePlacementToolItem.class);
            if (stack.isEmpty()) return;

            // Store using Data Component
            stack.set(ModDataComponents.PLACEMENT_CONFIG.get(),
                    payload.tag != null ? payload.tag : new CompoundTag());
        });
    }
}
