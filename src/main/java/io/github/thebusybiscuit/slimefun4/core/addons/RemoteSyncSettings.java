package io.github.thebusybiscuit.slimefun4.core.addons;

import javax.annotation.Nonnull;

/**
 * Cloud-sync settings for the managed addon platform.
 */
public record RemoteSyncSettings(
    boolean enabled,
    @Nonnull String url,
    long intervalMinutes,
    boolean fallbackToLocalOnEmpty,
    boolean fallbackToLocalOnFailure
) {
}
