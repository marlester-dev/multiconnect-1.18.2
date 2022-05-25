package net.earthcomputer.multiconnect.protocols.generic;

import net.earthcomputer.multiconnect.protocols.v1_14_4.SoundEvents_1_14_4;
import net.earthcomputer.multiconnect.protocols.v1_17_1.Particles_1_17_1;

public final class MulticonnectAddedRegistryEntries {
    private MulticonnectAddedRegistryEntries() {}

    public static void register() {
        SoundEvents_1_14_4.register();
        Particles_1_17_1.register();
    }

    public static void initializeClient() {
        Particles_1_17_1.registerFactories();
    }
}