package io.github.thebusybiscuit.slimefun4.core.addons;

import java.util.Map;
import java.util.Optional;

import javax.annotation.Nonnull;

/**
 * Cloud-sync settings for the managed addon platform.
 */
public record RemoteSyncSettings(
    boolean enabled,
    @Nonnull String url,
    @Nonnull Map<String, String> versionUrls,
    long intervalMinutes,
    boolean fallbackToLocalOnEmpty,
    boolean fallbackToLocalOnFailure
) {

    public @Nonnull Optional<String> urlForVersion(@Nonnull String versionTag) {
        String resolvedUrl = versionUrls.get(versionTag);
        return resolvedUrl == null || resolvedUrl.isBlank() ? Optional.empty() : Optional.of(resolvedUrl);
    }
}
