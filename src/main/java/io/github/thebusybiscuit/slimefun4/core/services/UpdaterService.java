package io.github.thebusybiscuit.slimefun4.core.services;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.plugin.Plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.bakedlibs.dough.updater.BlobBuildUpdater;
import io.github.bakedlibs.dough.updater.PluginUpdater;
import io.github.bakedlibs.dough.versions.PrefixedVersion;
import io.github.thebusybiscuit.slimefun4.api.SlimefunBranch;
import io.github.thebusybiscuit.slimefun4.core.services.updater.CacheManager;
import io.github.thebusybiscuit.slimefun4.core.services.updater.CacheManager.CachedRelease;
import io.github.thebusybiscuit.slimefun4.core.services.updater.CompatibilityResolver;
import io.github.thebusybiscuit.slimefun4.core.services.updater.CompatibilityResolver.Environment;
import io.github.thebusybiscuit.slimefun4.core.services.updater.ModrinthClient;
import io.github.thebusybiscuit.slimefun4.core.services.updater.StartupLogger;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * This Class represents our {@link PluginUpdater} Service.
 * Official builds use blob.build for auto-download updates.
 * Unofficial builds use a configurable Modrinth project check with optional auto-download.
 *
 * @author TheBusyBiscuit
 */
public class UpdaterService {

    private static final String DEFAULT_MODRINTH_PROJECT = "slimefuncore";
    private static final String MODRINTH_VERSION_ENDPOINT = "https://api.modrinth.com/v2/project/%s/version?game_versions=%s&loaders=%s";
    private static final Pattern BUILD_NUMBER_PATTERN = Pattern.compile("(\\d{4,})(?=\\.jar$)");
    private static final int DEFAULT_CACHE_TTL_HOURS = 6;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_RETRIES = 2;
    private static final String WARNING_BORDER = "****************************************************";

    private final Slimefun plugin;
    private final File pluginFile;
    private final PluginUpdater<PrefixedVersion> updater;
    private final SlimefunBranch branch;
    private volatile @Nullable ReleaseInfo latestRelease;
    private volatile @Nullable String lastCheckError;
    private volatile boolean lastCheckCompleted;

    public UpdaterService(@Nonnull Slimefun plugin, @Nonnull String version, @Nonnull File file) {
        this.plugin = plugin;
        this.pluginFile = file;

        BlobBuildUpdater autoUpdater = null;

        if (version.contains("UNOFFICIAL")) {
            branch = SlimefunBranch.UNOFFICIAL;
        } else if (version.startsWith("Dev - ")) {
            try {
                autoUpdater = new BlobBuildUpdater(plugin, file, "Slimefun4", "Dev");
            } catch (Exception x) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create AutoUpdater", x);
            }

            branch = SlimefunBranch.DEVELOPMENT;
        } else if (version.startsWith("RC - ")) {
            try {
                autoUpdater = new BlobBuildUpdater(plugin, file, "Slimefun4", "RC");
            } catch (Exception x) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create AutoUpdater", x);
            }

            branch = SlimefunBranch.STABLE;
        } else {
            branch = SlimefunBranch.UNKNOWN;
        }

        this.updater = autoUpdater;
    }

    public @Nonnull SlimefunBranch getBranch() {
        return branch;
    }

    public int getBuildNumber() {
        if (updater != null) {
            PrefixedVersion version = updater.getCurrentVersion();
            return version.getVersionNumber();
        }

        return -1;
    }

    public int getLatestVersion() {
        if (updater != null && updater.getLatestVersion().isDone()) {
            try {
                PrefixedVersion version = updater.getLatestVersion().get();
                return version.getVersionNumber();
            } catch (InterruptedException | ExecutionException e) {
                return -1;
            }
        }

        return -1;
    }

    public boolean isLatestVersion() {
        if (branch == SlimefunBranch.UNOFFICIAL) {
            Integer currentBuild = parseBuildNumber(pluginFile.getName());
            Integer latestBuild = latestRelease != null ? latestRelease.buildNumber() : null;
            return currentBuild == null || latestBuild == null || currentBuild >= latestBuild;
        }

        if (getBuildNumber() == -1 || getLatestVersion() == -1) {
            return true;
        }

        return getBuildNumber() == getLatestVersion();
    }

    /**
     * Start updater logic.
     * Official builds use blob.build.
     * Unofficial builds query Modrinth and optionally stage the new jar into /plugins/update.
     */
    public void start() {
        if (updater != null) {
            updater.start();
            return;
        }

        printBorder();
        plugin.getLogger().log(Level.WARNING, "It looks like you are using an unofficially modified build of Slimefun!");
        plugin.getLogger().log(Level.WARNING, "Modrinth release checks are enabled for this unofficial build.");
        plugin.getLogger().log(Level.WARNING, "Project: {0}", getModrinthProject());
        plugin.getLogger().log(Level.WARNING, "Auto-download updates: {0}", isAutoDownloadEnabled() ? "enabled" : "disabled");
        plugin.getLogger().log(Level.WARNING, "Do not report bugs encountered in this Version of Slimefun to any official sources.");
        printBorder();

        checkUnofficialReleaseAsync();
    }

    public boolean isEnabled() {
        return Slimefun.getCfg().getBoolean("options.auto-update");
    }

    public void disable() {
        printBorder();
        plugin.getLogger().log(Level.WARNING, "It looks like you have disabled update checks for Slimefun!");
        plugin.getLogger().log(Level.WARNING, "Update checks help you stay current with fixes and releases.");
        plugin.getLogger().log(Level.WARNING, "We respect your decision.");

        if (branch != SlimefunBranch.STABLE) {
            plugin.getLogger().log(Level.WARNING, "If you are just scared of Slimefun breaking, then please consider using a \"stable\" build instead of disabling update checks.");
        }

        printBorder();
    }

    private void printBorder() {
        plugin.getLogger().log(Level.WARNING, WARNING_BORDER);
    }

    private void checkUnofficialReleaseAsync() {
        Slimefun.getThreadService().newThread(plugin, "UpdaterService#startupCheck", () -> checkUnofficialRelease(true));
    }

    public void checkNow(@Nullable Consumer<UpdateStatus> callback) {
        if (updater != null) {
            UpdateStatus status = getStatus();

            if (callback != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(status));
            }

            return;
        }

        Slimefun.getThreadService().newThread(plugin, "UpdaterService#checkNow", () -> {
            checkUnofficialRelease(false);
            UpdateStatus status = getStatus();

            if (callback != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(status));
            }
        });
    }

    public @Nonnull UpdateStatus getStatus() {
        Integer currentBuild = parseBuildNumber(pluginFile.getName());
        Integer latestBuild = latestRelease != null ? latestRelease.buildNumber() : null;
        boolean unofficialUpdateAvailable = currentBuild != null && latestBuild != null && latestBuild > currentBuild;

        String currentVersion = branch == SlimefunBranch.UNOFFICIAL ? pluginFile.getName() : String.valueOf(getBuildNumber());
        String latestVersion = branch == SlimefunBranch.UNOFFICIAL
            ? latestRelease != null ? latestRelease.versionNumber() : null
            : getLatestVersion() == -1 ? null : String.valueOf(getLatestVersion());

        boolean updateAvailable = branch == SlimefunBranch.UNOFFICIAL
            ? unofficialUpdateAvailable
            : (getBuildNumber() != -1 && getLatestVersion() != -1 && getLatestVersion() > getBuildNumber());

        return new UpdateStatus(
            branch,
            isEnabled(),
            isAutoDownloadEnabled(),
            lastCheckCompleted || updater != null,
            updateAvailable,
            currentVersion,
            latestVersion,
            latestRelease != null ? latestRelease.assetName() : null,
            latestRelease != null ? latestRelease.projectUrl() : null,
            lastCheckError
        );
    }

    private void checkUnofficialRelease(boolean announceToConsole) {
        StartupLogger startupLogger = new StartupLogger(plugin.getLogger());
        CompatibilityResolver compatibilityResolver = new CompatibilityResolver();
        Environment environment = compatibilityResolver.detect(plugin);

        try {
            logEnvironment(startupLogger, environment);

            CacheManager cacheManager = new CacheManager(Duration.ofHours(getCacheTtlHours()), startupLogger);
            ModrinthClient client = createModrinthClient();
            ReleaseInfo latestRelease = fetchLatestModrinthRelease(startupLogger, compatibilityResolver, cacheManager, client, environment);
            this.latestRelease = latestRelease;
            this.lastCheckCompleted = true;
            this.lastCheckError = null;
            Integer currentBuild = parseBuildNumber(pluginFile.getName());

            if (latestRelease == null) {
                startupLogger.warning("No compatible build found for " + environment.minecraftVersion());
                startupLogger.warning("Plugin loading continued safely.");
                return;
            }

            startupLogger.info("Compatible build found: " + latestRelease.versionNumber());
            startupLogger.info("Supports: " + latestRelease.gameVersion());
            startupLogger.info("Compatibility validation passed.");
            startupLogger.info("Loading compatible core...");

            if (currentBuild == null || latestRelease.buildNumber() == null) {
                if (announceToConsole) {
                    plugin.getLogger().log(Level.INFO, "Could not compare build numbers for automatic updates.");
                    plugin.getLogger().log(Level.INFO, "Current jar: {0}", pluginFile.getName());
                    plugin.getLogger().log(Level.INFO, "Latest Modrinth version: {0}", latestRelease.versionNumber());
                    plugin.getLogger().log(Level.INFO, "Project page: {0}", latestRelease.projectUrl());
                }
                return;
            }

            if (latestRelease.buildNumber() > currentBuild) {
                if (announceToConsole) {
                    printBorder();
                    plugin.getLogger().log(Level.WARNING, "A newer SlimefunCore release is available on Modrinth.");
                    plugin.getLogger().log(Level.WARNING, "Current build: {0}", pluginFile.getName());
                    plugin.getLogger().log(Level.WARNING, "Latest asset: {0}", latestRelease.assetName());
                    plugin.getLogger().log(Level.WARNING, "Latest version: {0}", latestRelease.versionNumber());
                    plugin.getLogger().log(Level.WARNING, "Published: {0}", latestRelease.publishedAt());
                    plugin.getLogger().log(Level.WARNING, "Project page: {0}", latestRelease.projectUrl());
                }

                if (isAutoDownloadEnabled()) {
                    if (downloadLatestRelease(latestRelease) && announceToConsole) {
                        plugin.getLogger().log(Level.WARNING, "The new jar was downloaded into the update folder and will apply on the next restart.");
                    }
                } else if (announceToConsole) {
                    plugin.getLogger().log(Level.WARNING, "Auto-download is disabled. Enable options.auto-download-update to stage updates automatically.");
                }

                if (announceToConsole) {
                    printBorder();
                }
                return;
            }

            if (announceToConsole) {
                if (currentBuild > latestRelease.buildNumber()) {
                    plugin.getLogger().log(Level.INFO, "SlimefunCore is newer than the latest compatible Modrinth release.");
                    plugin.getLogger().log(Level.INFO, "Current build: {0}", pluginFile.getName());
                    plugin.getLogger().log(Level.INFO, "Latest asset: {0}", latestRelease.assetName());
                    plugin.getLogger().log(Level.INFO, "Latest version: {0}", latestRelease.versionNumber());
                    return;
                }

                plugin.getLogger().log(Level.INFO, "SlimefunCore is already on the latest Modrinth release.");
                plugin.getLogger().log(Level.INFO, "Current build: {0}", pluginFile.getName());
                plugin.getLogger().log(Level.INFO, "Latest asset: {0}", latestRelease.assetName());
                plugin.getLogger().log(Level.INFO, "Latest version: {0}", latestRelease.versionNumber());
            }
        } catch (IOException | InterruptedException x) {
            this.lastCheckCompleted = true;
            this.lastCheckError = x.getMessage();
            if (announceToConsole) {
                startupLogger.warning("Failed to check unofficial Modrinth releases: " + x.getMessage());
                startupLogger.warning("Plugin loading continued safely.");
            }

            if (x instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void logEnvironment(@Nonnull StartupLogger logger, @Nonnull Environment environment) {
        logger.info("Detecting server environment...");
        logger.info("Platform: " + environment.platform());
        logger.info("Minecraft: " + environment.minecraftVersion() + "  Java: " + environment.javaVersion());
    }

    private @Nullable ReleaseInfo fetchLatestModrinthRelease(
        @Nonnull StartupLogger logger,
        @Nonnull CompatibilityResolver resolver,
        @Nonnull CacheManager cacheManager,
        @Nonnull ModrinthClient client,
        @Nonnull Environment environment
    ) throws IOException, InterruptedException {
        try {
            logger.info("Querying Modrinth API...");
            JsonArray versions = client.fetchVersions(createModrinthVersionUri(environment.minecraftVersion(), environment.queryLoaders()));
            logger.info("Modrinth check: live API");
            ReleaseInfo release = selectCompatibleRelease(versions, resolver, environment);

            if (release != null) {
                cacheManager.save(toCachedRelease(release));
            }

            return release;
        } catch (IOException x) {
            logger.warning("Live Modrinth API check failed: " + x.getMessage());
            return cacheManager.load(environment.minecraftVersion()).map(this::fromCachedRelease).orElseThrow(() -> x);
        }
    }

    private @Nullable ReleaseInfo selectCompatibleRelease(
        @Nonnull JsonArray versions,
        @Nonnull CompatibilityResolver resolver,
        @Nonnull Environment environment
    ) {
        List<ReleaseInfo> compatible = new ArrayList<>();

        for (JsonElement element : versions) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject version = element.getAsJsonObject();
            String versionNumber = getAsString(version, "version_number");
            String versionId = getAsString(version, "id");
            String publishedAtRaw = getAsString(version, "date_published");
            JsonArray gameVersions = getAsArray(version, "game_versions");
            JsonArray loaders = getAsArray(version, "loaders");
            JsonArray files = getAsArray(version, "files");

            if (versionNumber == null || versionId == null || publishedAtRaw == null || gameVersions == null || loaders == null || files == null) {
                continue;
            }

            if (!resolver.supportsGameVersion(gameVersions, environment.minecraftVersion()) || !resolver.supportsLoader(loaders, environment.compatibleLoaders())) {
                continue;
            }

            JsonObject selectedFile = selectPrimaryModrinthJar(files);
            if (selectedFile == null) {
                continue;
            }

            String assetName = getAsString(selectedFile, "filename");
            String downloadUrl = getAsString(selectedFile, "url");

            if (assetName == null || downloadUrl == null || !isSafeJarName(assetName)) {
                continue;
            }

            Instant publishedAt;
            try {
                publishedAt = Instant.parse(publishedAtRaw);
            } catch (RuntimeException ignored) {
                continue;
            }

            Integer buildNumber = parseBuildNumber(assetName);
            String projectUrl = "https://modrinth.com/plugin/" + getModrinthProject() + "/version/" + versionId;
            compatible.add(new ReleaseInfo(versionNumber, projectUrl, publishedAt, assetName, downloadUrl, buildNumber, environment.minecraftVersion()));
        }

        return compatible.stream()
            .max(Comparator.comparing(ReleaseInfo::publishedAt))
            .orElse(null);
    }

    private @Nullable JsonObject selectPrimaryModrinthJar(@Nonnull JsonArray files) {
        for (JsonElement fileElement : files) {
            if (!fileElement.isJsonObject()) {
                continue;
            }

            JsonObject file = fileElement.getAsJsonObject();
            String fileName = getAsString(file, "filename");

            if (fileName == null || !isSafeJarName(fileName) || fileName.endsWith("-sources.jar")) {
                continue;
            }

            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                return file;
            }
        }

        return null;
    }

    private @Nonnull CachedRelease toCachedRelease(@Nonnull ReleaseInfo release) {
        return new CachedRelease(
            release.versionNumber(),
            release.assetName(),
            release.downloadUrl(),
            System.currentTimeMillis(),
            release.gameVersion(),
            release.projectUrl(),
            release.publishedAt(),
            release.buildNumber()
        );
    }

    private @Nonnull ReleaseInfo fromCachedRelease(@Nonnull CachedRelease release) {
        return new ReleaseInfo(
            release.version(),
            release.projectUrl(),
            release.publishedAt(),
            release.assetName(),
            release.downloadUrl(),
            release.buildNumber(),
            release.gameVersion()
        );
    }

    private @Nullable Integer parseBuildNumber(@Nonnull String fileName) {
        Matcher matcher = BUILD_NUMBER_PATTERN.matcher(fileName);

        if (!matcher.find()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException x) {
            return null;
        }
    }

    private @Nullable String getAsString(@Nonnull JsonObject object, @Nonnull String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }

        return object.get(key).getAsString();
    }

    private @Nullable JsonArray getAsArray(@Nonnull JsonObject object, @Nonnull String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private @Nonnull URI createModrinthVersionUri(@Nonnull String gameVersion, @Nonnull List<String> loaders) {
        String project = getModrinthProject();
        String gameVersions = encodeJsonArray(List.of(gameVersion));
        return URI.create(String.format(MODRINTH_VERSION_ENDPOINT, project, gameVersions, encodeJsonArray(loaders)));
    }

    private @Nonnull String encodeJsonArray(@Nonnull List<String> values) {
        StringBuilder builder = new StringBuilder("[");

        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }

            builder.append('"').append(values.get(i)).append('"');
        }

        builder.append(']');
        return URLEncoder.encode(builder.toString(), StandardCharsets.UTF_8);
    }

    private @Nonnull String getModrinthProject() {
        String project = Slimefun.getCfg().getString("options.modrinth-project");
        return project == null || project.isBlank() ? DEFAULT_MODRINTH_PROJECT : project.trim();
    }

    private boolean isAutoDownloadEnabled() {
        return Slimefun.getCfg().getBoolean("options.auto-download-update");
    }

    private int getCacheTtlHours() {
        int ttl = Slimefun.getCfg().getInt("options.modrinth-cache-ttl-hours");
        return ttl > 0 ? ttl : DEFAULT_CACHE_TTL_HOURS;
    }

    private int getTimeoutSeconds() {
        int timeout = Slimefun.getCfg().getInt("options.modrinth-timeout-seconds");
        return timeout > 0 ? timeout : DEFAULT_TIMEOUT_SECONDS;
    }

    private int getRetries() {
        int retries = Slimefun.getCfg().getInt("options.modrinth-retries");
        return retries > 0 ? Math.min(DEFAULT_RETRIES, retries) : DEFAULT_RETRIES;
    }

    private @Nonnull ModrinthClient createModrinthClient() {
        return new ModrinthClient(Duration.ofSeconds(getTimeoutSeconds()), getRetries());
    }

    private boolean downloadLatestRelease(@Nonnull ReleaseInfo latestRelease) {
        Path temporary = null;

        try {
            if (!isSafeJarName(latestRelease.assetName())) {
                plugin.getLogger().log(Level.WARNING, "Rejected Modrinth asset with unsafe jar name: {0}", latestRelease.assetName());
                return false;
            }

            File updateFolder = plugin.getServer().getUpdateFolderFile();

            if (!updateFolder.exists() && !updateFolder.mkdirs()) {
                plugin.getLogger().log(Level.WARNING, "Could not create the server update folder: {0}", updateFolder.getAbsolutePath());
                return false;
            }

            Path updatePath = updateFolder.toPath();
            Path target = updatePath.resolve(latestRelease.assetName());
            temporary = Files.createTempFile(updatePath, "slimefun-update-", ".jar.tmp");
            createModrinthClient().downloadFile(URI.create(latestRelease.downloadUrl()), temporary);

            if (!Files.isRegularFile(temporary) || Files.size(temporary) <= 0L) {
                plugin.getLogger().log(Level.WARNING, "Rejected downloaded Modrinth asset because the temporary jar is empty or missing.");
                return false;
            }

            moveAtomically(temporary, target);
            temporary = null;
            return true;
        } catch (IOException | InterruptedException | IllegalArgumentException x) {
            plugin.getLogger().log(Level.WARNING, "Failed to download the latest unofficial Modrinth release: " + x.getMessage());

            if (x instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The next startup can safely overwrite a stale temp file.
                }
            }
        }
    }

    private void moveAtomically(@Nonnull Path source, @Nonnull Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean isSafeJarName(@Nonnull String fileName) {
        String lowerCase = fileName.toLowerCase();
        return lowerCase.endsWith(".jar") && !fileName.contains("/") && !fileName.contains("\\");
    }

    private record ReleaseInfo(@Nonnull String versionNumber, @Nonnull String projectUrl, @Nonnull Instant publishedAt,
                               @Nonnull String assetName, @Nonnull String downloadUrl, @Nullable Integer buildNumber,
                               @Nonnull String gameVersion) {}

    public record UpdateStatus(
        @Nonnull SlimefunBranch branch,
        boolean checksEnabled,
        boolean autoDownloadEnabled,
        boolean checked,
        boolean updateAvailable,
        @Nonnull String currentVersion,
        @Nullable String latestVersion,
        @Nullable String latestAsset,
        @Nullable String projectUrl,
        @Nullable String lastError
    ) {}
}
