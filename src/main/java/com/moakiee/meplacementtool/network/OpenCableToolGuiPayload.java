package com.moakiee.meplacementtool.network;

import com.moakiee.meplacementtool.CableToolMenu;
import com.moakiee.meplacementtool.ItemMECablePlacementTool;
import com.moakiee.meplacementtool.MEPlacementToolMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.network.chat.Component;

/**
 * Payload to request opening the Cable Tool GUI from client
 */
public record OpenCableToolGuiPayload() implements CustomPacketPayload {
    public static final Type<OpenCableToolGuiPayload> TYPE = 
            new Type<>(ResourceLocation.fromNamespaceAndPath(MEPlacementToolMod.MODID, "open_cable_tool_gui"));

    public static final StreamCodec<FriendlyByteBuf, OpenCableToolGuiPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenCableToolGuiPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenCableToolGuiPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemStack main = player.getMainHandItem();
                ItemStack off = player.getOffhandItem();
                
                ItemStack tool = null;
                int slot = -1;
                
                if (main.getItem() instanceof ItemMECablePlacementTool) {
                    tool = main;
                    slot = player.getInventory().selected;
                } else if (off.getItem() instanceof ItemMECablePlacementTool) {
                    tool = off;
                    slot = 40; // Offhand slot
                }
                
                if (tool != null) {
                    final ItemStack finalTool = tool;
                    final int finalSlot = slot;
                    player.openMenu(new SimpleMenuProvider(
                            (id, inv, p) -> new CableToolMenu(id, inv, finalTool, finalSlot),
                            Component.translatable("gui.meplacementtool.cable_tool")
                    ), buf -> buf.writeInt(finalSlot));
                }
            }
        });
    }
}
