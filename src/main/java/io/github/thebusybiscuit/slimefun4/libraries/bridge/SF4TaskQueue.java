package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import javax.annotation.Nonnull;

import io.github.bakedlibs.dough.scheduling.TaskQueue;

/**
 * Bridge for {@link TaskQueue}.
 * Call sites use SF4TaskQueue.create() instead of new TaskQueue().
 */
public final class SF4TaskQueue {

    private SF4TaskQueue() {}

    /** Factory replacement for {@code new TaskQueue()}. */
    public static @Nonnull TaskQueue create() {
        return new TaskQueue();
    }
}
