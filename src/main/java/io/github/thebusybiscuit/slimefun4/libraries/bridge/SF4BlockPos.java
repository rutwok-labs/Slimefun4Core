package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import javax.annotation.Nonnull;

import org.bukkit.Location;
import org.bukkit.block.Block;

import io.github.bakedlibs.dough.blocks.BlockPosition;

/**
 * Bridge for {@link BlockPosition}.
 * Re-exports factory methods for constructor call sites.
 */
public final class SF4BlockPos {

    private SF4BlockPos() {}

    /** Delegates to {@link BlockPosition#BlockPosition(Location)}. */
    public static @Nonnull BlockPosition of(@Nonnull Location location) {
        return new BlockPosition(location);
    }

    /** Delegates to {@link BlockPosition#BlockPosition(Block)}. */
    public static @Nonnull BlockPosition of(@Nonnull Block block) {
        return new BlockPosition(block);
    }
}
