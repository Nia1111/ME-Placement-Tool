package com.moakiee.meplacementtool.network;

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
 * Payload for updating placement direction mode on the Multiblock Placement Tool.
 */
public record UpdateDirectionModePayload(int modeId) implements CustomPacketPayload {
    public static final Type<UpdateDirectionModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MEPlacementToolMod.MODID, "update_direction_mode"));

    public static final StreamCodec<FriendlyByteBuf, UpdateDirectionModePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT,
                    UpdateDirectionModePayload::modeId,
                    UpdateDirectionModePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UpdateDirectionModePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            // Find the multiblock tool in either hand (main first, off second).
            ItemStack stack = com.moakiee.meplacementtool.BasePlacementToolItem
                    .findHeldTool(player, ItemMultiblockPlacementTool.class);
            if (stack.isEmpty()) return;

            CompoundTag cfg = stack.get(ModDataComponents.PLACEMENT_CONFIG.get());
            cfg = (cfg == null) ? new CompoundTag() : cfg.copy();
            cfg.putInt("DirectionMode", ItemMultiblockPlacementTool.DirectionMode.fromId(payload.modeId()).ordinal());
            stack.set(ModDataComponents.PLACEMENT_CONFIG.get(), cfg);
        });
    }
}
