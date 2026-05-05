package io.github.thebusybiscuit.slimefun4.core.addons;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.Validate;

/**
 * Immutable configuration for a managed addon.
 */
public record AddonDefinition(
    @Nonnull String key,
    @Nonnull String name,
    boolean enabled,
    boolean download,
    boolean autoUpdate,
    @Nonnull String version,
    @Nonnull String url,
    @Nullable String sha256,
    @Nullable String checksumUrl,
    @Nullable String apiUrl
) {

    public AddonDefinition {
        Validate.notEmpty(key, "Addon key must not be empty");
        Validate.notEmpty(name, "Addon name must not be empty");
        Validate.notEmpty(version, "Addon version must not be empty");
        Validate.notEmpty(url, "Addon url must not be empty");
    }

    public boolean tracksLatestVersion() {
        return "latest".equalsIgnoreCase(version);
    }

    public @Nonnull String repositoryFileName() {
        String sanitized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        return sanitized.endsWith(".jar") ? sanitized : sanitized + ".jar";
    }

    public @Nonnull AddonDefinition withEnabled(boolean value) {
        return new AddonDefinition(key, name, value, download, autoUpdate, version, url, sha256, checksumUrl, apiUrl);
    }

    public @Nonnull AddonDefinition withDownload(boolean value) {
        return new AddonDefinition(key, name, enabled, value, autoUpdate, version, url, sha256, checksumUrl, apiUrl);
    }

    public @Nonnull AddonDefinition withAutoUpdate(boolean value) {
        return new AddonDefinition(key, name, enabled, download, value, version, url, sha256, checksumUrl, apiUrl);
    }
}
