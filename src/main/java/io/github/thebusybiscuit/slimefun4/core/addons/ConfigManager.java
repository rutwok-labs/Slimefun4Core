package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.Validate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Handles addonmanager configuration, cloud-sync settings and cache persistence.
 */
public final class ConfigManager {

    private static final String DEFAULT_REMOTE_URL = "https://raw.githubusercontent.com/rutwok-labs/SF4-Addons/main/Addons/java/core/api/config/addonmanager.yml";
    private static final String DEFAULT_CONFIG_CONTENT = """
        cloud-sync:
          enabled: true
          url: "https://raw.githubusercontent.com/rutwok-labs/SF4-Addons/main/Addons/java/core/api/config/addonmanager.yml"
          version-urls:
            "26.0.2": ""
            "26.0.1": ""
            "1.21.11": "https://raw.githubusercontent.com/rutwok-labs/SF4-Addons/main/Addons/java/core/api/config/addonmanager.yml"
            "1.20.4": ""
            "1.19.4": ""
          interval-minutes: 3
          fallback-to-local-on-empty: true
          fallback-to-local-on-failure: true
        """;

    private final Slimefun plugin;
    private final Path configPath;
    private final Path remoteConfigPath;
    private final Path cachePath;
    private final Path logPath;
    private final Path repositoryDirectory;
    private final Path tempDirectory;
    private final Path backupDirectory;
    private final Path deletionQueueDirectory;

    public ConfigManager(@Nonnull Slimefun plugin) {
        this.plugin = plugin;
        this.configPath = plugin.getDataFolder().toPath().resolve("addonmanager.yml");
        this.remoteConfigPath = plugin.getDataFolder().toPath().resolve("addonmanager-remote.yml");
        this.cachePath = plugin.getDataFolder().toPath().resolve("addonmanager-cache.yml");
        this.logPath = plugin.getDataFolder().toPath().resolve("addons").resolve("addonmanager.log");
        this.repositoryDirectory = plugin.getDataFolder().toPath().resolve("addons").resolve("repository");
        this.tempDirectory = plugin.getDataFolder().toPath().resolve("addons").resolve("tmp");
        this.backupDirectory = plugin.getDataFolder().toPath().resolve("addons").resolve("backups");
        this.deletionQueueDirectory = plugin.getDataFolder().toPath().resolve("addons").resolve("delete-queue");
    }

    public synchronized void initialize() throws IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.createDirectories(repositoryDirectory);
        Files.createDirectories(tempDirectory);
        Files.createDirectories(backupDirectory);
        Files.createDirectories(deletionQueueDirectory);

        if (Files.notExists(configPath)) {
            copyBundledConfigOrCreateDefault();
        }

        if (Files.notExists(cachePath)) {
            Files.createFile(cachePath);
        }
    }

    public synchronized @Nonnull Map<String, AddonDefinition> loadAddons() {
        YamlConfiguration localConfiguration = YamlConfiguration.loadConfiguration(configPath.toFile());
        Map<String, AddonDefinition> localAddons = parseAddons(localConfiguration);

        if (!localAddons.isEmpty()) {
            return localAddons;
        }

        RemoteSyncSettings syncSettings = parseSyncSettings(localConfiguration);
        if (!syncSettings.enabled() || Files.notExists(remoteConfigPath)) {
            return localAddons;
        }

        try {
            Map<String, AddonDefinition> remoteAddons = loadAddonsFromFile(remoteConfigPath);
            if (!remoteAddons.isEmpty() || !syncSettings.fallbackToLocalOnEmpty()) {
                return remoteAddons;
            }

            plugin.getLogger().warning("Remote addonmanager.yml is empty or invalid, falling back to the local addonmanager.yml.");
        } catch (RuntimeException x) {
            if (!syncSettings.fallbackToLocalOnFailure()) {
                throw x;
            }

            plugin.getLogger().warning("Remote addonmanager.yml failed to load, falling back to the local addonmanager.yml: " + x.getMessage());
        }

        return localAddons;
    }

    public synchronized @Nonnull Map<String, AddonDefinition> loadLocalAddons() {
        return parseAddons(YamlConfiguration.loadConfiguration(configPath.toFile()));
    }

    public synchronized @Nonnull Map<String, AddonDefinition> loadRemoteAddons() {
        if (Files.notExists(remoteConfigPath)) {
            return Map.of();
        }

        return loadAddonsFromFile(remoteConfigPath);
    }

    public synchronized @Nonnull Optional<AddonDefinition> getAddon(@Nonnull String key) {
        Validate.notEmpty(key, "Addon key must not be empty");
        return Optional.ofNullable(loadAddons().get(key.toLowerCase(Locale.ROOT)));
    }

    public synchronized void saveAddon(@Nonnull AddonDefinition definition) throws IOException {
        Validate.notNull(definition, "Addon definition must not be null");
        saveAddonToPath(configPath, definition);

        if (shouldPersistToRemoteConfig()) {
            saveAddonToPath(remoteConfigPath, definition);
        }
    }

    public synchronized @Nonnull RemoteSyncSettings getSyncSettings() {
        return parseSyncSettings(YamlConfiguration.loadConfiguration(configPath.toFile()));
    }

    public synchronized void saveRemoteConfig(@Nonnull String content) throws IOException {
        Validate.notNull(content, "Remote configuration content must not be null");
        Files.writeString(remoteConfigPath, content);
    }

    public synchronized void saveRemoteAddonsToLocal(@Nonnull Map<String, AddonDefinition> addons) throws IOException {
        Validate.notNull(addons, "Addon definitions must not be null");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configPath.toFile());
        Map<String, AddonDefinition> localAddons = parseAddons(configuration);
        Map<String, AddonDefinition> mergedAddons = new LinkedHashMap<>();

        for (AddonDefinition remoteDefinition : addons.values()) {
            AddonDefinition localDefinition = localAddons.get(remoteDefinition.key());
            mergedAddons.put(remoteDefinition.key(), mergeRemoteWithLocalState(remoteDefinition, localDefinition));
        }

        for (AddonDefinition localDefinition : localAddons.values()) {
            mergedAddons.putIfAbsent(localDefinition.key(), localDefinition);
        }

        backupLocalConfig("remote-sync");
        configuration.set("addons", null);
        ConfigurationSection addonsSection = configuration.createSection("addons");

        for (AddonDefinition definition : mergedAddons.values()) {
            saveAddonToSection(addonsSection, definition);
        }

        configuration.save(configPath.toFile());
        appendLog("Remote sync merged " + addons.size() + " remote addon(s) into addonmanager.yml while preserving local enabled/download state.");
    }

    public synchronized @Nonnull Optional<RemoteSyncState> getRemoteSyncState() {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection section = configuration.getConfigurationSection("remote-sync");

        if (section == null) {
            return Optional.empty();
        }

        String lastSyncedRaw = section.getString("last-synced");
        Instant lastSynced = lastSyncedRaw != null && !lastSyncedRaw.isBlank() ? Instant.parse(lastSyncedRaw) : null;
        return Optional.of(new RemoteSyncState(
            emptyToNull(section.getString("etag")),
            emptyToNull(section.getString("last-modified")),
            emptyToNull(section.getString("source-url")),
            emptyToNull(section.getString("last-status")),
            lastSynced
        ));
    }

    public synchronized void saveRemoteSyncState(@Nonnull RemoteSyncState state) throws IOException {
        Validate.notNull(state, "Remote sync state must not be null");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection section = configuration.getConfigurationSection("remote-sync");

        if (section == null) {
            section = configuration.createSection("remote-sync");
        }

        section.set("etag", state.etag());
        section.set("last-modified", state.lastModified());
        section.set("source-url", state.sourceUrl());
        section.set("last-status", state.lastStatus());
        section.set("last-synced", state.lastSyncedAt() != null ? state.lastSyncedAt().toString() : null);
        configuration.save(cachePath.toFile());
    }

    public synchronized @Nonnull Optional<AddonCacheEntry> getCacheEntry(@Nonnull String key) {
        Validate.notEmpty(key, "Addon key must not be empty");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection section = configuration.getConfigurationSection("addons." + key);

        if (section == null) {
            return Optional.empty();
        }

        String updatedAt = section.getString("updated-at");
        Instant timestamp = updatedAt != null && !updatedAt.isBlank() ? Instant.parse(updatedAt) : null;
        return Optional.of(new AddonCacheEntry(
            emptyToNull(section.getString("version")),
            emptyToNull(section.getString("sha256")),
            emptyToNull(section.getString("etag")),
            emptyToNull(section.getString("last-modified")),
            emptyToNull(section.getString("source-url")),
            timestamp,
            section.getBoolean("verified", false)
        ));
    }

    public synchronized void saveCacheEntry(@Nonnull String key, @Nonnull AddonCacheEntry entry) throws IOException {
        Validate.notEmpty(key, "Addon key must not be empty");
        Validate.notNull(entry, "Addon cache entry must not be null");

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection root = configuration.getConfigurationSection("addons");

        if (root == null) {
            root = configuration.createSection("addons");
        }

        ConfigurationSection section = root.getConfigurationSection(key);

        if (section == null) {
            section = root.createSection(key);
        }

        section.set("version", entry.version());
        section.set("sha256", entry.sha256());
        section.set("etag", entry.etag());
        section.set("last-modified", entry.lastModified());
        section.set("source-url", entry.sourceUrl());
        section.set("updated-at", entry.updatedAt() != null ? entry.updatedAt().toString() : null);
        section.set("verified", entry.verified());
        configuration.save(cachePath.toFile());
    }

    public synchronized void removeCacheEntry(@Nonnull String key) throws IOException {
        Validate.notEmpty(key, "Addon key must not be empty");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        configuration.set("addons." + key, null);
        configuration.save(cachePath.toFile());
    }

    public @Nonnull Path getRepositoryDirectory() {
        return repositoryDirectory;
    }

    public @Nonnull Path getTempDirectory() {
        return tempDirectory;
    }

    public @Nonnull Path getBackupDirectory() {
        return backupDirectory;
    }

    public @Nonnull Path getRemoteConfigPath() {
        return remoteConfigPath;
    }

    public @Nonnull Path getLogPath() {
        return logPath;
    }

    public synchronized void markAddonJarForDeletion(@Nonnull AddonDefinition definition) throws IOException {
        Validate.notNull(definition, "Addon definition must not be null");
        Files.createDirectories(deletionQueueDirectory);
        Files.writeString(resolveDeletionMarker(definition), definition.name(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        appendLog("Scheduled managed addon jar deletion for " + definition.name());
    }

    public synchronized boolean isAddonJarDeletionScheduled(@Nonnull AddonDefinition definition) {
        Validate.notNull(definition, "Addon definition must not be null");
        return Files.exists(resolveDeletionMarker(definition));
    }

    public synchronized void clearAddonJarDeletionMarker(@Nonnull AddonDefinition definition) throws IOException {
        Validate.notNull(definition, "Addon definition must not be null");
        Files.deleteIfExists(resolveDeletionMarker(definition));
    }

    public @Nonnull Path resolveAddonPath(@Nonnull AddonDefinition definition) {
        return repositoryDirectory.resolve(definition.repositoryFileName());
    }

    public synchronized boolean addonJarExists(@Nonnull AddonDefinition definition) {
        return Files.exists(resolveAddonPath(definition));
    }

    public synchronized void deleteAddonJar(@Nonnull AddonDefinition definition) throws IOException {
        appendLog("Deleting managed addon jar for " + definition.name() + " at " + resolveAddonPath(definition));
        Files.deleteIfExists(resolveAddonPath(definition));
    }

    public synchronized @Nonnull Collection<File> getRepositoryJars() throws IOException {
        try (var stream = Files.list(repositoryDirectory)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .map(Path::toFile)
                .toList();
        }
    }

    private @Nonnull Map<String, AddonDefinition> loadAddonsFromFile(@Nonnull Path file) {
        return parseAddons(YamlConfiguration.loadConfiguration(file.toFile()));
    }

    private @Nonnull Map<String, AddonDefinition> parseAddons(@Nonnull YamlConfiguration configuration) {
        ConfigurationSection addonsSection = configuration.getConfigurationSection("addons");
        Map<String, AddonDefinition> addons = new LinkedHashMap<>();

        if (addonsSection == null) {
            return addons;
        }

        collectAddons(addonsSection, addons);

        return addons;
    }

    private void collectAddons(@Nonnull ConfigurationSection parent, @Nonnull Map<String, AddonDefinition> addons) {
        for (String key : parent.getKeys(false)) {
            ConfigurationSection section = parent.getConfigurationSection(key);

            if (section == null) {
                continue;
            }

            if (isAddonSection(section)) {
                AddonDefinition definition = toAddonDefinition(key, section);

                if (definition != null) {
                    addons.put(definition.key(), definition);
                }

                collectNestedAddonSections(section, addons);
                continue;
            }

            collectAddons(section, addons);
        }
    }

    private void collectNestedAddonSections(@Nonnull ConfigurationSection parent, @Nonnull Map<String, AddonDefinition> addons) {
        for (String key : parent.getKeys(false)) {
            ConfigurationSection section = parent.getConfigurationSection(key);

            if (section == null) {
                continue;
            }

            if (isAddonSection(section) && hasAddonMetadata(section)) {
                AddonDefinition definition = toAddonDefinition(key, section);

                if (definition != null) {
                    addons.put(definition.key(), definition);
                }

                collectNestedAddonSections(section, addons);
            }
        }
    }

    private boolean isAddonSection(@Nonnull ConfigurationSection section) {
        String directUrl = section.getString("download-url");
        String legacyUrl = section.getString("url");
        return section.isConfigurationSection("versions") || (directUrl != null && !directUrl.isBlank()) || (legacyUrl != null && !legacyUrl.isBlank());
    }

    private boolean hasAddonMetadata(@Nonnull ConfigurationSection section) {
        return section.contains("name")
            || section.contains("version")
            || section.contains("enabled")
            || section.contains("download")
            || section.contains("auto-update")
            || section.contains("versions")
            || section.contains("sha256")
            || section.contains("checksum-url")
            || section.contains("api-url");
    }

    private @Nullable AddonDefinition toAddonDefinition(@Nonnull String key, @Nonnull ConfigurationSection section) {
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        String name = section.getString("name", key);
        Map<String, String> versionUrls = readVersionUrls(section);

        if (versionUrls.isEmpty()) {
            plugin.getLogger().warning("Managed addon \"" + normalizedKey + "\" is missing version URLs and will be skipped.");
            return null;
        }

        return new AddonDefinition(
            normalizedKey,
            name,
            section.getBoolean("enabled", true),
            section.getBoolean("download", true),
            section.getBoolean("auto-update", false),
            versionUrls,
            emptyToNull(section.getString("sha256")),
            emptyToNull(section.getString("checksum-url")),
            emptyToNull(section.getString("api-url"))
        );
    }

    private @Nonnull Map<String, String> readVersionUrls(@Nonnull ConfigurationSection section) {
        Map<String, String> versionUrls = new LinkedHashMap<>();
        ConfigurationSection versionsSection = section.getConfigurationSection("versions");

        if (versionsSection != null) {
            readDottedKeyStringMap(versionsSection, "", versionUrls);
        }

        if (versionUrls.isEmpty()) {
            String version = section.getString("version", "latest");
            String url = section.getString("download-url", section.getString("url", ""));

            if (!url.isBlank()) {
                versionUrls.put(version, url);
            }
        }

        return versionUrls;
    }

    private @Nonnull RemoteSyncSettings parseSyncSettings(@Nonnull YamlConfiguration configuration) {
        ConfigurationSection section = configuration.getConfigurationSection("cloud-sync");

        if (section == null) {
            return new RemoteSyncSettings(false, DEFAULT_REMOTE_URL, Map.of(), 3L, true, true);
        }

        String configuredUrl = section.getString("url", DEFAULT_REMOTE_URL);
        return new RemoteSyncSettings(
            section.getBoolean("enabled", true),
            normalizeRemoteUrl(configuredUrl),
            readSyncVersionUrls(section),
            Math.max(1L, section.getLong("interval-minutes", 3L)),
            section.getBoolean("fallback-to-local-on-empty", true),
            section.getBoolean("fallback-to-local-on-failure", true)
        );
    }

    private @Nonnull Map<String, String> readSyncVersionUrls(@Nonnull ConfigurationSection section) {
        ConfigurationSection urlsSection = section.getConfigurationSection("version-urls");
        Map<String, String> urls = new LinkedHashMap<>();

        if (urlsSection == null) {
            return urls;
        }

        Map<String, String> rawUrls = new LinkedHashMap<>();
        readDottedKeyStringMap(urlsSection, "", rawUrls);

        for (Map.Entry<String, String> entry : rawUrls.entrySet()) {
            String configuredUrl = entry.getValue();
            urls.put(entry.getKey(), configuredUrl == null || configuredUrl.isBlank() ? "" : normalizeRemoteUrl(configuredUrl));
        }

        return urls;
    }

    private void readDottedKeyStringMap(@Nonnull ConfigurationSection section, @Nonnull String prefix, @Nonnull Map<String, String> values) {
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + '.' + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof ConfigurationSection nestedSection) {
                readDottedKeyStringMap(nestedSection, key, values);
            } else {
                values.put(key, value == null ? "" : value.toString());
            }
        }
    }

    private @Nonnull String normalizeRemoteUrl(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_REMOTE_URL;
        }

        String trimmed = url.trim();
        String marker = "github.com/";
        String blobMarker = "/blob/";

        if (trimmed.contains(marker) && trimmed.contains(blobMarker)) {
            String repositoryPath = trimmed.substring(trimmed.indexOf(marker) + marker.length());
            String[] split = repositoryPath.split("/blob/", 2);

            if (split.length == 2) {
                return "https://raw.githubusercontent.com/" + split[0] + "/" + split[1];
            }
        }

        return trimmed;
    }

    private @Nullable String emptyToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private @Nonnull AddonDefinition mergeRemoteWithLocalState(@Nonnull AddonDefinition remoteDefinition, @Nullable AddonDefinition localDefinition) {
        if (localDefinition == null) {
            return remoteDefinition;
        }

        return new AddonDefinition(
            remoteDefinition.key(),
            remoteDefinition.name(),
            localDefinition.enabled(),
            localDefinition.download(),
            localDefinition.autoUpdate(),
            remoteDefinition.versionUrls(),
            remoteDefinition.sha256(),
            remoteDefinition.checksumUrl(),
            remoteDefinition.apiUrl()
        );
    }

    private void backupLocalConfig(@Nonnull String reason) throws IOException {
        if (Files.notExists(configPath)) {
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        Path backup = backupDirectory.resolve("addonmanager-" + reason + '-' + timestamp + ".yml");
        Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
        appendLog("Backed up addonmanager.yml to " + backup);
    }

    public synchronized void appendLog(@Nonnull String message) {
        Validate.notNull(message, "Log message cannot be null");

        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(
                logPath,
                Instant.now() + " " + message + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException x) {
            plugin.getLogger().warning("Could not write AddonManager log: " + x.getMessage());
        }
    }

    private boolean shouldPersistToRemoteConfig() {
        return getSyncSettings().enabled() && Files.exists(remoteConfigPath);
    }

    private void saveAddonToPath(@Nonnull Path path, @Nonnull AddonDefinition definition) throws IOException {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(path.toFile());
        ConfigurationSection addonsSection = configuration.getConfigurationSection("addons");

        if (addonsSection == null) {
            addonsSection = configuration.createSection("addons");
        }

        saveAddonToSection(addonsSection, definition);
        configuration.save(path.toFile());
    }

    private @Nonnull Path resolveDeletionMarker(@Nonnull AddonDefinition definition) {
        return deletionQueueDirectory.resolve(definition.key() + ".delete");
    }

    private void saveAddonToSection(@Nonnull ConfigurationSection addonsSection, @Nonnull AddonDefinition definition) {
        ConfigurationSection section = addonsSection.getConfigurationSection(definition.key());

        if (section == null) {
            section = addonsSection.createSection(definition.key());
        }

        section.set("name", definition.name());
        section.set("enabled", definition.enabled());
        section.set("download", definition.download());
        section.set("auto-update", definition.autoUpdate());
        section.set("sha256", definition.sha256());
        section.set("checksum-url", definition.checksumUrl());
        section.set("api-url", definition.apiUrl());
        section.set("version", null);
        section.set("download-url", null);
        section.set("url", null);
        section.set("versions", null);

        ConfigurationSection versionsSection = section.createSection("versions");
        for (Map.Entry<String, String> entry : definition.versionUrls().entrySet()) {
            versionsSection.set(entry.getKey(), entry.getValue());
        }
    }

    private void copyBundledConfigOrCreateDefault() throws IOException {
        try (InputStream stream = plugin.getResource("addonmanager.yml")) {
            if (stream != null) {
                Files.copy(stream, configPath, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }

        Files.writeString(configPath, DEFAULT_CONFIG_CONTENT);
        plugin.getLogger().warning("addonmanager.yml was not bundled inside the jar, a safe default file has been created instead.");
    }
}
