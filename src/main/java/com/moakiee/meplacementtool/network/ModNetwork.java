package com.moakiee.meplacementtool.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import com.mojang.serialization.Codec;

public class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("meplacementtool", "network"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(id++, UpdateWandConfigPacket.class, UpdateWandConfigPacket::encode,
                UpdateWandConfigPacket::decode, UpdateWandConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdatePlacementCountPacket.class, UpdatePlacementCountPacket::encode,
                UpdatePlacementCountPacket::decode, UpdatePlacementCountPacket::handle);
        CHANNEL.registerMessage(id++, UpdateDirectionModePacket.class, UpdateDirectionModePacket::encode,
                UpdateDirectionModePacket::decode, UpdateDirectionModePacket::handle);
        CHANNEL.registerMessage(id++, UndoPacket.class, UndoPacket::encode,
                UndoPacket::decode, UndoPacket.Handler::handle);
        CHANNEL.registerMessage(id++, SyncPagePacket.class, SyncPagePacket::encode,
                SyncPagePacket::decode, SyncPagePacket::handle);
        CHANNEL.registerMessage(id++, UpdateCableToolPacket.class, UpdateCableToolPacket::encode,
                UpdateCableToolPacket::decode, UpdateCableToolPacket::handle);
        CHANNEL.registerMessage(id++, OpenCableToolGuiPacket.class, OpenCableToolGuiPacket::encode,
                OpenCableToolGuiPacket::decode, OpenCableToolGuiPacket::handle);
        CHANNEL.registerMessage(id++, ClearCableToolPointsPacket.class, ClearCableToolPointsPacket::encode,
                ClearCableToolPointsPacket::decode, ClearCableToolPointsPacket::handle);
    }
}
