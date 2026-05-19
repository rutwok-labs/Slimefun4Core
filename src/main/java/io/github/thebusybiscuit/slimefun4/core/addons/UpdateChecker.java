package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.JsonUtils;

/**
 * Checks remote metadata for managed addons.
 */
public final class UpdateChecker {

    private static final String USER_AGENT = "Slimefun-AddonManager";

    private final Slimefun plugin;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public UpdateChecker(@Nonnull Slimefun plugin) {
        this.plugin = plugin;
    }

    public @Nonnull RemoteAddonInfo inspect(@Nonnull AddonDefinition definition, @Nullable AddonCacheEntry cache) throws IOException, InterruptedException {
        if (definition.apiUrl() != null && definition.apiUrl().contains("api.github.com/repos/")) {
            return inspectGitHubLatest(definition, cache);
        }

        if (!definition.tracksLatestVersion()) {
            boolean updateAvailable = cache == null || cache.version() == null || !definition.version().equalsIgnoreCase(cache.version());
            String downloadUrl = definition.resolveUrlForMinecraftVersion(Slimefun.getMinecraftVersion()).orElse(definition.url());
            return new RemoteAddonInfo(
                updateAvailable,
                definition.version(),
                downloadUrl,
                normalizeSha(definition.sha256()),
                null,
                null,
                updateAvailable ? "Configured version differs from cached version." : "Cached version already matches the configured version."
            );
        }

        String metadataUrl = definition.resolveUrlForMinecraftVersion(Slimefun.getMinecraftVersion()).orElse(definition.url());
        HttpResponse<Void> response = sendMetadataRequest(metadataUrl);
        if (response.statusCode() >= 400) {
            throw new IOException("Metadata request returned HTTP " + response.statusCode() + " for " + metadataUrl);
        }

        String etag = firstHeader(response, "ETag");
        String lastModified = firstHeader(response, "Last-Modified");
        String advertisedVersion = Optional.ofNullable(firstHeader(response, "X-Addon-Version"))
            .orElseGet(() -> etag != null ? etag : lastModified != null ? lastModified : "latest");

        boolean hasRemoteMetadata = etag != null || lastModified != null;
        boolean updateAvailable = !hasRemoteMetadata
            ? cache == null || cache.sourceUrl() == null
            : cache == null || !safeEquals(cache.etag(), etag) || !safeEquals(cache.lastModified(), lastModified);

        return new RemoteAddonInfo(
            updateAvailable,
            advertisedVersion,
            metadataUrl,
            resolveExpectedChecksum(definition, response),
            etag,
            lastModified,
            updateAvailable ? "Remote metadata changed." : "Remote metadata matches the cached copy."
        );
    }

    private @Nonnull RemoteAddonInfo inspectGitHubLatest(@Nonnull AddonDefinition definition, @Nullable AddonCacheEntry cache) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(definition.apiUrl()))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() >= 400) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode() + " for " + definition.apiUrl());
        }

        JsonObject payload = JsonUtils.parseString(response.body()).getAsJsonObject();
        String version = getString(payload, "tag_name");
        if (version == null || version.isBlank()) {
            version = getString(payload, "name");
        }
        if (version == null || version.isBlank()) {
            version = "latest";
        }

        String downloadUrl = definition.resolveUrlForMinecraftVersion(Slimefun.getMinecraftVersion()).orElse(definition.url());
        String jarAssetName = null;
        JsonArray assets = payload.has("assets") && payload.get("assets").isJsonArray() ? payload.getAsJsonArray("assets") : null;

        if (assets != null) {
            JsonObject selectedAsset = null;
            int bestDownloadCount = -1;
            String key = definition.key().toLowerCase(Locale.ROOT);
            String name = definition.name().toLowerCase(Locale.ROOT);

            for (JsonElement element : assets) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject asset = element.getAsJsonObject();
                String assetName = getString(asset, "name");

                if (assetName == null || !assetName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }

                String normalizedAssetName = assetName.toLowerCase(Locale.ROOT);
                if (normalizedAssetName.contains(key) || normalizedAssetName.contains(name)) {
                    selectedAsset = asset;
                    break;
                }

                int downloadCount = asset.has("download_count") ? asset.get("download_count").getAsInt() : 0;

                if (downloadCount > bestDownloadCount) {
                    bestDownloadCount = downloadCount;
                    selectedAsset = asset;
                }
            }

            if (selectedAsset != null) {
                jarAssetName = getString(selectedAsset, "name");
                String browserDownloadUrl = getString(selectedAsset, "browser_download_url");
                if (browserDownloadUrl != null && !browserDownloadUrl.isBlank()) {
                    downloadUrl = browserDownloadUrl;
                }
            }
        }

        String expectedSha256 = assets != null && jarAssetName != null ? findCompanionSha256Asset(assets, jarAssetName) : null;
        if (expectedSha256 == null && definition.sha256() != null && !definition.sha256().isBlank()) {
            expectedSha256 = normalizeSha(definition.sha256());
        }
        if (expectedSha256 == null && cache != null && cache.sha256() != null && cache.version() != null && version.equalsIgnoreCase(cache.version())) {
            expectedSha256 = normalizeSha(cache.sha256());
            plugin.getLogger().fine("Using cached SHA256 for " + definition.name() + " version " + version);
        }

        boolean updateAvailable = cache == null || cache.version() == null || !version.equalsIgnoreCase(cache.version());
        return new RemoteAddonInfo(
            updateAvailable,
            version,
            downloadUrl,
            expectedSha256,
            firstHeader(response, "ETag"),
            firstHeader(response, "Last-Modified"),
            updateAvailable ? "GitHub latest release differs from the cached version." : "GitHub latest release matches the cached version."
        );
    }

    /**
     * Finds a SHA256 checksum asset that belongs to the selected jar asset and returns the checksum content.
     *
     * @param assets
     *            The GitHub release assets to scan
     * @param jarAssetName
     *            The selected jar asset name
     *
     * @return The normalized checksum from a companion asset, or null if none was found/readable
     */
    private @Nullable String findCompanionSha256Asset(@Nonnull JsonArray assets, @Nonnull String jarAssetName) throws IOException, InterruptedException {
        String normalizedJarName = jarAssetName.toLowerCase(Locale.ROOT);
        String jarBaseName = normalizedJarName.endsWith(".jar") ? normalizedJarName.substring(0, normalizedJarName.length() - ".jar".length()) : normalizedJarName;

        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject asset = element.getAsJsonObject();
            String assetName = getString(asset, "name");

            if (assetName == null) {
                continue;
            }

            String normalizedAssetName = assetName.toLowerCase(Locale.ROOT);
            boolean matchesJarSha = normalizedAssetName.equals(normalizedJarName + ".sha256");
            boolean matchesBaseSha = normalizedAssetName.equals(jarBaseName + ".sha256");

            if (!matchesJarSha && !matchesBaseSha) {
                continue;
            }

            String checksumUrl = getString(asset, "browser_download_url");
            if (checksumUrl == null || checksumUrl.isBlank()) {
                return null;
            }

            try {
                HttpRequest checksumRequest = HttpRequest.newBuilder(URI.create(checksumUrl))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
                HttpResponse<String> checksumResponse = client.send(checksumRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (checksumResponse.statusCode() >= 200 && checksumResponse.statusCode() < 300) {
                    String body = checksumResponse.body() != null ? checksumResponse.body().trim() : "";
                    if (!body.isBlank()) {
                        return normalizeSha(body.split("\\s+")[0]);
                    }
                }
            } catch (IOException | IllegalArgumentException x) {
                return null;
            }

            return null;
        }

        return null;
    }

    private @Nonnull HttpResponse<Void> sendMetadataRequest(@Nonnull String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        if (response.statusCode() == 405 || response.statusCode() == 501) {
            HttpRequest fallback = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            return client.send(fallback, HttpResponse.BodyHandlers.discarding());
        }

        return response;
    }

    private @Nullable String resolveExpectedChecksum(@Nonnull AddonDefinition definition, @Nonnull HttpResponse<?> response) throws IOException, InterruptedException {
        if (definition.sha256() != null && !definition.sha256().isBlank()) {
            return normalizeSha(definition.sha256());
        }

        String header = firstHeader(response, "X-Checksum-Sha256");
        if (header != null && !header.isBlank()) {
            return normalizeSha(header);
        }

        String checksumUrl = definition.checksumUrl();
        if (checksumUrl == null || checksumUrl.isBlank()) {
            checksumUrl = definition.resolveUrlForMinecraftVersion(Slimefun.getMinecraftVersion()).orElse(definition.url()) + ".sha256";
        }

        try {
            HttpRequest checksumRequest = HttpRequest.newBuilder(URI.create(checksumUrl))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpResponse<String> checksumResponse = client.send(checksumRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (checksumResponse.statusCode() >= 200 && checksumResponse.statusCode() < 300) {
                String body = checksumResponse.body().trim();
                if (!body.isBlank()) {
                    return normalizeSha(body.split("\\s+")[0]);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed checksum URL overrides.
        }

        return null;
    }

    private @Nullable String firstHeader(@Nonnull HttpResponse<?> response, @Nonnull String key) {
        return response.headers().firstValue(key).orElse(null);
    }

    private boolean safeEquals(@Nullable String left, @Nullable String right) {
        if (left == null) {
            return right == null;
        }

        return left.equals(right);
    }

    private @Nullable String getString(@Nonnull JsonObject object, @Nonnull String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }

        return object.get(key).getAsString();
    }

    private @Nullable String normalizeSha(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
