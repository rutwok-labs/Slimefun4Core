package io.github.thebusybiscuit.slimefun4.core.addons;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Remote metadata returned by the update checker.
 */
public record RemoteAddonInfo(
    boolean updateAvailable,
    @Nonnull String resolvedVersion,
    @Nonnull String downloadUrl,
    @Nullable String expectedSha256,
    @Nullable String etag,
    @Nullable String lastModified,
    @Nonnull String message
) {
}
