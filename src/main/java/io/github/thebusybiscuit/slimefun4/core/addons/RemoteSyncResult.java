package io.github.thebusybiscuit.slimefun4.core.addons;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Result of a remote addonmanager.yml sync run.
 */
public record RemoteSyncResult(
    @Nonnull RemoteSyncOutcome outcome,
    @Nonnull String message,
    @Nullable Throwable error
) {
}
