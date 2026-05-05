package io.github.thebusybiscuit.slimefun4.core.addons;

import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Snapshot of the current state of a managed addon.
 */
public record ManagedAddonStatus(
    @Nonnull AddonDefinition definition,
    boolean jarPresent,
    boolean loaded,
    @Nullable String cachedVersion,
    @Nullable Path file
) {
}
