package net.earthcomputer.multiconnect.packets.latest;

import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.Handler;
import net.earthcomputer.multiconnect.ap.MessageVariant;
import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.packets.CPacketPlayerInteractBlock;
import net.earthcomputer.multiconnect.packets.CommonTypes;
import net.earthcomputer.multiconnect.packets.v1_18_2.CPacketPlayerInteractBlock_1_18_2;
import net.earthcomputer.multiconnect.protocols.v1_18_2.IPendingUpdateManager;
import net.earthcomputer.multiconnect.protocols.v1_18_2.mixin.ClientWorldAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

@MessageVariant(minVersion = Protocols.V1_19)
public class CPacketPlayerInteractBlock_Latest implements CPacketPlayerInteractBlock {
    public CommonTypes.Hand hand;
    public CommonTypes.BlockPos pos;
    public CommonTypes.Direction face;
    public float offsetX;
    public float offsetY;
    public float offsetZ;
    public boolean insideBlock;
    public int sequence;

    @SuppressWarnings("resource")
    @Handler(protocol = Protocols.V1_18_2)
    public static CPacketPlayerInteractBlock_1_18_2 handle(
            @Argument(value = "this", translate = true) CPacketPlayerInteractBlock_1_18_2 translatedThis,
            @Argument("sequence") int sequence
    ) {
        MinecraftClient.getInstance().execute(() -> {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world != null) {
                var pendingUpdateManager = (IPendingUpdateManager) ((ClientWorldAccessor) world).multiconnect_getPendingUpdateManager();
                pendingUpdateManager.multiconnect_nullifyPendingUpdatesUpTo(world, sequence);
                world.handlePlayerActionResponse(sequence);
            }
        });
        return translatedThis;
    }
}