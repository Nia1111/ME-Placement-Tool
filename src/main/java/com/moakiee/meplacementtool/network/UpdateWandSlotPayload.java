package com.moakiee.meplacementtool.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.moakiee.meplacementtool.MEPlacementToolMod;
import com.moakiee.meplacementtool.ModDataComponents;
import com.moakiee.meplacementtool.WandMenu;

public record UpdateWandSlotPayload(int slotIndex, ItemStack stack) implements CustomPacketPayload {
    private static final int TOTAL_SLOTS = 18;

    public static final CustomPacketPayload.Type<UpdateWandSlotPayload> TYPE = 
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MEPlacementToolMod.MODID, "update_wand_slot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateWandSlotPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT,
        UpdateWandSlotPayload::slotIndex,
        ItemStack.OPTIONAL_STREAM_CODEC,
        UpdateWandSlotPayload::stack,
        UpdateWandSlotPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UpdateWandSlotPayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            // If the WandMenu is open, use its handler
            if (player.containerMenu instanceof WandMenu wandMenu) {
                wandMenu.handleUpdateSlot(player, payload.slotIndex(), payload.stack());
            } else {
                // Fallback: directly update the item's data component
                ItemStack mainHand = player.getMainHandItem();
                if (!mainHand.isEmpty() && payload.slotIndex() >= 0 && payload.slotIndex() < TOTAL_SLOTS) {
                    updateItemDirectly(mainHand, payload.slotIndex(), payload.stack(), player);
                }
            }
        });
    }

    private static void updateItemDirectly(ItemStack wandStack, int slotIndex, ItemStack newStack, ServerPlayer player) {
        CompoundTag existingConfig = wandStack.getOrDefault(ModDataComponents.PLACEMENT_CONFIG.get(), new CompoundTag());

        ItemStackHandler handler = new ItemStackHandler(TOTAL_SLOTS);
        if (existingConfig.contains("items")) {
            handler.deserializeNBT(player.level().registryAccess(), existingConfig.getCompound("items"));
        }

        handler.setStackInSlot(slotIndex, newStack);

        CompoundTag newConfig = new CompoundTag();
        newConfig.put("items", handler.serializeNBT(player.level().registryAccess()));

        if (existingConfig.contains("fluids")) {
            newConfig.put("fluids", existingConfig.getCompound("fluids"));
        }
        if (existingConfig.contains("SelectedSlot")) {
            newConfig.putInt("SelectedSlot", existingConfig.getInt("SelectedSlot"));
        }
        if (existingConfig.contains("PlacementCount")) {
            newConfig.putInt("PlacementCount", existingConfig.getInt("PlacementCount"));
        }
        if (existingConfig.contains("DirectionMode")) {
            newConfig.putInt("DirectionMode", existingConfig.getInt("DirectionMode"));
        }

        wandStack.set(ModDataComponents.PLACEMENT_CONFIG.get(), newConfig);
    }
}
