package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import org.bukkit.block.Block;

import io.github.bakedlibs.dough.blocks.Vein;

/**
 * Bridge for {@link Vein}.
 * When dough updates Vein, edit only this class.
 */
public final class SF4Vein {

    private SF4Vein() {}

    /** Delegates to {@link Vein#find(Block, int)}. */
    public static @Nonnull List<Block> find(@Nonnull Block start, int maxBlocks) {
        return Vein.find(start, maxBlocks);
    }

    /** Delegates to {@link Vein#find(Block, int, Predicate)}. */
    public static @Nonnull List<Block> find(@Nonnull Block start, int maxBlocks, @Nonnull Predicate<Block> predicate) {
        return Vein.find(start, maxBlocks, predicate);
    }
}
