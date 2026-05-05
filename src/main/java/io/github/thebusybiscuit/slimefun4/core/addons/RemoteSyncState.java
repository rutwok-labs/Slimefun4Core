package io.github.thebusybiscuit.slimefun4.core.addons;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cached metadata for the remote addonmanager.yml sync source.
 */
public record RemoteSyncState(
    @Nullable String etag,
    @Nullable String lastModified,
    @Nullable String sourceUrl,
    @Nullable String lastStatus,
    @Nullable Instant lastSyncedAt
) {
    public static final @Nonnull RemoteSyncState EMPTY = new RemoteSyncState(null, null, null, null, null);
}
