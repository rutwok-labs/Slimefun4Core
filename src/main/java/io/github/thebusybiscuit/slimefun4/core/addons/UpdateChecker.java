package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Duration ADDON_METADATA_CACHE_TTL = Duration.ofMinutes(3);

    private final Slimefun plugin;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public UpdateChecker(@Nonnull Slimefun plugin) {
        this.plugin = plugin;
    }

    public @Nonnull RemoteAddonInfo inspect(@Nonnull AddonDefinition definition, @Nullable AddonCacheEntry cache) throws IOException, InterruptedException {
        RemoteAddonInfo cachedInfo = cachedRemoteInfo(definition, cache);
        if (cachedInfo != null) {
            return cachedInfo;
        }

        if (definition.apiUrl() != null && definition.apiUrl().contains("api.github.com/repos/")) {
            return inspectGitHubLatest(definition, cache);
        }

        String resolvedUrl = definition.resolveUrlForMinecraftVersion(Slimefun.getMinecraftVersion()).orElse(definition.url());
        Optional<GitHubFileSource> githubSource = parseGitHubFileSource(resolvedUrl);
        if (githubSource.isPresent()) {
            return inspectGitHubDirectory(definition, cache, githubSource.get(), resolvedUrl);
        }

        if (!definition.tracksLatestVersion()) {
            boolean updateAvailable = cache == null || cache.version() == null || !definition.version().equalsIgnoreCase(cache.version());
            return new RemoteAddonInfo(
                updateAvailable,
                definition.version(),
                resolvedUrl,
                normalizeSha(definition.sha256()),
                null,
                null,
                updateAvailable ? "Configured version differs from cached version." : "Cached version already matches the configured version."
            );
        }

        String metadataUrl = resolvedUrl;
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

    private @Nullable RemoteAddonInfo cachedRemoteInfo(@Nonnull AddonDefinition definition, @Nullable AddonCacheEntry cache) {
        if (cache == null || cache.version() == null || cache.sourceUrl() == null || cache.updatedAt() == null) {
            return null;
        }

        Duration age = Duration.between(cache.updatedAt(), Instant.now());
        if (age.isNegative() || age.compareTo(ADDON_METADATA_CACHE_TTL) > 0) {
            return null;
        }

        plugin.getLogger().fine("Using cached managed addon metadata for " + definition.name() + " (" + age.toSeconds() + "s old)");
        return new RemoteAddonInfo(
            false,
            cache.version(),
            cache.sourceUrl(),
            normalizeSha(cache.sha256()),
            cache.etag(),
            cache.lastModified(),
            "Using cached addon metadata."
        );
    }

    private @Nonnull RemoteAddonInfo inspectGitHubDirectory(
        @Nonnull AddonDefinition definition,
        @Nullable AddonCacheEntry cache,
        @Nonnull GitHubFileSource source,
        @Nonnull String fallbackUrl
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.contentsApiUrl()))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() >= 400) {
            throw new IOException("GitHub contents API returned HTTP " + response.statusCode() + " for " + source.contentsApiUrl());
        }

        JsonArray files = JsonUtils.parseString(response.body()).getAsJsonArray();
        GitHubJarAsset selectedAsset = selectHighestBuildJar(definition, files).orElse(null);

        if (selectedAsset == null) {
            String version = definition.version();
            boolean updateAvailable = cache == null || cache.version() == null || !version.equalsIgnoreCase(cache.version());
            return new RemoteAddonInfo(
                updateAvailable,
                version,
                fallbackUrl,
                normalizeSha(definition.sha256()),
                null,
                null,
                "GitHub addon repository directory did not contain a compatible jar, using configured URL."
            );
        }

        String expectedSha256 = findCompanionSha256File(files, selectedAsset.name());
        if (expectedSha256 == null && definition.sha256() != null && !definition.sha256().isBlank()) {
            expectedSha256 = normalizeSha(definition.sha256());
        }
        if (expectedSha256 == null && cache != null && cache.sha256() != null && cache.version() != null && selectedAsset.version().equalsIgnoreCase(cache.version())) {
            expectedSha256 = normalizeSha(cache.sha256());
            plugin.getLogger().fine("Using cached SHA256 for " + definition.name() + " version " + selectedAsset.version());
        }

        boolean updateAvailable = cache == null
            || cache.version() == null
            || !selectedAsset.version().equalsIgnoreCase(cache.version())
            || cache.sourceUrl() == null
            || !selectedAsset.downloadUrl().equals(cache.sourceUrl());

        return new RemoteAddonInfo(
            updateAvailable,
            selectedAsset.version(),
            selectedAsset.downloadUrl(),
            expectedSha256,
            firstHeader(response, "ETag"),
            firstHeader(response, "Last-Modified"),
            updateAvailable ? "GitHub repository has a newer managed addon jar." : "GitHub repository jar matches the cached copy."
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

    private @Nullable String findCompanionSha256File(@Nonnull JsonArray files, @Nonnull String jarAssetName) throws IOException, InterruptedException {
        String normalizedJarName = jarAssetName.toLowerCase(Locale.ROOT);
        String jarBaseName = normalizedJarName.endsWith(".jar") ? normalizedJarName.substring(0, normalizedJarName.length() - ".jar".length()) : normalizedJarName;

        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject file = element.getAsJsonObject();
            String fileName = getString(file, "name");
            String downloadUrl = getString(file, "download_url");

            if (fileName == null || downloadUrl == null || downloadUrl.isBlank()) {
                continue;
            }

            String normalizedFileName = fileName.toLowerCase(Locale.ROOT);
            boolean matchesJarSha = normalizedFileName.equals(normalizedJarName + ".sha256");
            boolean matchesBaseSha = normalizedFileName.equals(jarBaseName + ".sha256");

            if (!matchesJarSha && !matchesBaseSha) {
                continue;
            }

            try {
                HttpRequest checksumRequest = HttpRequest.newBuilder(URI.create(downloadUrl))
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
        }

        return null;
    }

    private @Nonnull Optional<GitHubJarAsset> selectHighestBuildJar(@Nonnull AddonDefinition definition, @Nonnull JsonArray files) {
        List<GitHubJarAsset> matchingAssets = new ArrayList<>();
        List<GitHubJarAsset> fallbackAssets = new ArrayList<>();
        String key = normalizeName(definition.key());
        String name = normalizeName(definition.name());

        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject file = element.getAsJsonObject();
            String fileName = getString(file, "name");
            String downloadUrl = getString(file, "download_url");

            if (fileName == null || downloadUrl == null || downloadUrl.isBlank() || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar") || fileName.endsWith("-sources.jar")) {
                continue;
            }

            Integer buildNumber = parseHighestNumber(fileName);
            GitHubJarAsset asset = new GitHubJarAsset(fileName, downloadUrl, buildNumber, buildNumber != null ? String.valueOf(buildNumber) : fileName);
            String normalizedFileName = normalizeName(fileName);

            if (normalizedFileName.contains(key) || normalizedFileName.contains(name)) {
                matchingAssets.add(asset);
            } else {
                fallbackAssets.add(asset);
            }
        }

        Optional<GitHubJarAsset> selected = matchingAssets.stream().max(jarComparator());
        return selected.isPresent() ? selected : fallbackAssets.stream().max(jarComparator());
    }

    private @Nonnull Comparator<GitHubJarAsset> jarComparator() {
        return Comparator
            .comparing((GitHubJarAsset asset) -> asset.buildNumber() != null ? asset.buildNumber() : -1)
            .thenComparing(GitHubJarAsset::name);
    }

    private @Nullable Integer parseHighestNumber(@Nonnull String fileName) {
        Matcher matcher = NUMBER_PATTERN.matcher(fileName);
        Integer highest = null;

        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (highest == null || value > highest) {
                    highest = value;
                }
            } catch (NumberFormatException ignored) {
                // Ignore oversized numeric groups.
            }
        }

        return highest;
    }

    private @Nonnull String normalizeName(@Nonnull String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private @Nonnull Optional<GitHubFileSource> parseGitHubFileSource(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();

            if (host == null || path == null) {
                return Optional.empty();
            }

            if ("raw.githubusercontent.com".equalsIgnoreCase(host)) {
                return parseRawGitHubPath(path);
            }

            if ("github.com".equalsIgnoreCase(host)) {
                return parseGitHubPath(path);
            }
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private @Nonnull Optional<GitHubFileSource> parseRawGitHubPath(@Nonnull String path) {
        String[] parts = path.startsWith("/") ? path.substring(1).split("/", 4) : path.split("/", 4);

        if (parts.length < 4) {
            return Optional.empty();
        }

        return Optional.of(GitHubFileSource.from(parts[0], parts[1], parts[2], parentPath(parts[3])));
    }

    private @Nonnull Optional<GitHubFileSource> parseGitHubPath(@Nonnull String path) {
        String[] parts = path.startsWith("/") ? path.substring(1).split("/") : path.split("/");

        if (parts.length < 5 || (!"raw".equals(parts[2]) && !"blob".equals(parts[2]))) {
            return Optional.empty();
        }

        int pathStartIndex;
        String ref;

        if ("refs".equals(parts[3]) && parts.length >= 8 && "heads".equals(parts[4])) {
            ref = parts[5];
            pathStartIndex = 6;
        } else {
            ref = parts[3];
            pathStartIndex = 4;
        }

        StringBuilder filePath = new StringBuilder();
        for (int i = pathStartIndex; i < parts.length; i++) {
            if (i > pathStartIndex) {
                filePath.append('/');
            }
            filePath.append(parts[i]);
        }

        if (filePath.length() == 0) {
            return Optional.empty();
        }

        return Optional.of(GitHubFileSource.from(parts[0], parts[1], ref, parentPath(filePath.toString())));
    }

    private @Nonnull String parentPath(@Nonnull String filePath) {
        int separator = filePath.lastIndexOf('/');
        return separator > 0 ? filePath.substring(0, separator) : "";
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

    private record GitHubJarAsset(@Nonnull String name, @Nonnull String downloadUrl, @Nullable Integer buildNumber, @Nonnull String version) {}

    private record GitHubFileSource(@Nonnull String owner, @Nonnull String repo, @Nonnull String ref, @Nonnull String directoryPath) {

        private static @Nonnull GitHubFileSource from(@Nonnull String owner, @Nonnull String repo, @Nonnull String ref, @Nonnull String directoryPath) {
            return new GitHubFileSource(owner, repo, ref, directoryPath);
        }

        private @Nonnull String contentsApiUrl() {
            return "https://api.github.com/repos/" + owner + '/' + repo + "/contents/" + directoryPath + "?ref=" + ref;
        }
    }
}
