package io.github.thebusybiscuit.slimefun4.core.addons;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cached metadata for a managed addon download.
 */
public record AddonCacheEntry(
    @Nullable String version,
    @Nullable String sha256,
    @Nullable String etag,
    @Nullable String lastModified,
    @Nullable String sourceUrl,
    @Nullable Instant updatedAt,
    boolean verified
) {
    public static final AddonCacheEntry EMPTY = new AddonCacheEntry(null, null, null, null, null, null, false);

    public @Nonnull AddonCacheEntry withVersion(@Nullable String value) {
        return new AddonCacheEntry(value, sha256, etag, lastModified, sourceUrl, updatedAt, verified);
    }
}
