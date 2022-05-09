package net.earthcomputer.multiconnect.packets.v1_17_1;

import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.Length;
import net.earthcomputer.multiconnect.ap.MessageVariant;
import net.earthcomputer.multiconnect.ap.Registries;
import net.earthcomputer.multiconnect.ap.Registry;
import net.earthcomputer.multiconnect.ap.Type;
import net.earthcomputer.multiconnect.ap.Types;
import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.packets.ChunkData;

import java.util.BitSet;
import java.util.List;

@MessageVariant(minVersion = Protocols.V1_17, maxVersion = Protocols.V1_17_1)
public class ChunkData_1_17_1 implements ChunkData {
    @Length(compute = "computeSectionsLength")
    public List<ChunkData.Section> sections;

    public static int computeSectionsLength(
        @Argument("outer.verticalStripBitmask") BitSet verticalStripBitmask
    ) {
        return verticalStripBitmask.cardinality();
    }

    @MessageVariant(maxVersion = Protocols.V1_17_1)
    public static class ChunkSection implements ChunkData.Section {
        public short nonEmptyBlockCount;
        public ChunkData.BlockStatePalettedContainer blockStates;
    }

    @MessageVariant(maxVersion = Protocols.V1_17_1)
    public static class BlockStatePalettedContainer implements ChunkData.BlockStatePalettedContainer {
        public byte paletteSize;
        @Registry(Registries.BLOCK_STATE)
        public int[] palette;
        @Type(Types.LONG)
        public long[] data;
    }
}