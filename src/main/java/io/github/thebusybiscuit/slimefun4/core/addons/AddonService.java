package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * High-level addon management workflow.
 */
public final class AddonService {

    private final Slimefun plugin;
    private final ConfigManager configManager;
    private final UpdateChecker updateChecker;
    private final DownloadService downloadService;
    private final AddonLoader addonLoader;
    private final AtomicReference<Map<String, AddonDefinition>> definitions = new AtomicReference<>(Map.of());

    public AddonService(
        @Nonnull Slimefun plugin,
        @Nonnull ConfigManager configManager,
        @Nonnull UpdateChecker updateChecker,
        @Nonnull DownloadService downloadService,
        @Nonnull AddonLoader addonLoader
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.updateChecker = updateChecker;
        this.downloadService = downloadService;
        this.addonLoader = addonLoader;
    }

    public void start() {
        refreshDefinitions();
        syncConfiguredAddons();
    }

    public void shutdown() {
        plugin.getLogger().info("AddonService shutting down.");
    }

    public void refreshDefinitions() {
        definitions.set(Map.copyOf(configManager.loadAddons()));
    }

    public @Nonnull List<ManagedAddonStatus> listAddons() {
        refreshDefinitions();
        List<ManagedAddonStatus> statuses = new ArrayList<>();

        for (AddonDefinition definition : compatibleDefinitions().stream().sorted(Comparator.comparing(AddonDefinition::key)).toList()) {
            Optional<AddonCacheEntry> cache = configManager.getCacheEntry(definition.key());
            statuses.add(new ManagedAddonStatus(
                definition,
                configManager.addonJarExists(definition),
                addonLoader.isLoaded(definition),
                cache.map(AddonCacheEntry::version).orElse(null),
                configManager.resolveAddonPath(definition)
            ));
        }

        return statuses;
    }

    public int countConfiguredAddons() {
        refreshDefinitions();
        return definitions.get().size();
    }

    public @Nonnull String getActiveVersionTag() {
        return VersionTagResolver.resolveTag(Slimefun.getMinecraftVersion());
    }

    public @Nonnull CompletableFuture<AddonOperationResult> download(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonOperationResult validation = addonLoader.validateInstallCandidate(definition);
            if (!validation.success()) {
                return validation;
            }

            AddonDefinition updated = definition.withDownload(true);
            configManager.saveAddon(updated);
            configManager.clearAddonJarDeletionMarker(updated);
            refreshDefinitions();
            RemoteAddonInfo remoteInfo = updateChecker.inspect(updated, configManager.getCacheEntry(updated.key()).orElse(null));
            return downloadService.download(updated, remoteInfo);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> update(@Nonnull String key) {
        return withDefinition(key, definition -> {
            if (!definition.download()) {
                return AddonOperationResult.failure("Addon " + definition.name() + " is configured with download=false.", null);
            }

            AddonCacheEntry cache = configManager.getCacheEntry(definition.key()).orElse(null);
            RemoteAddonInfo remoteInfo = updateChecker.inspect(definition, cache);

            if (!remoteInfo.updateAvailable() && configManager.addonJarExists(definition)) {
                return AddonOperationResult.success("No newer version was detected for " + definition.name() + '.', false);
            }

            return downloadService.download(definition, remoteInfo);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> remove(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonDefinition updated = definition.withEnabled(false).withDownload(false);
            configManager.saveAddon(updated);
            if (configManager.addonJarExists(updated) && !addonLoader.isLoaded(updated)) {
                configManager.deleteAddonJar(updated);
                configManager.clearAddonJarDeletionMarker(updated);
            } else if (addonLoader.isLoaded(updated)) {
                configManager.markAddonJarForDeletion(updated);
                plugin.getLogger().info("Managed addon " + updated.name() + " is currently loaded. Its jar will be removed safely during the next startup pass.");
            }
            refreshDefinitions();
            return AddonOperationResult.success("Scheduled removal for " + updated.name() + ". The addon will be absent after the next restart.", true);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> enable(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonOperationResult validation = addonLoader.validateInstallCandidate(definition);
            if (!validation.success()) {
                return validation;
            }

            AddonDefinition updated = definition.withEnabled(true).withDownload(true);
            configManager.saveAddon(updated);
            configManager.clearAddonJarDeletionMarker(updated);
            refreshDefinitions();

            RemoteAddonInfo remoteInfo = updateChecker.inspect(updated, configManager.getCacheEntry(updated.key()).orElse(null));
            AddonOperationResult result = downloadService.download(updated, remoteInfo);

            if (!result.success()) {
                return result;
            }

            return AddonOperationResult.success("Downloaded and enabled " + updated.name() + " for the next restart.", true);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> disable(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonDefinition updated = definition.withEnabled(false).withDownload(false);
            configManager.saveAddon(updated);

            if (configManager.addonJarExists(updated) && !addonLoader.isLoaded(updated)) {
                configManager.deleteAddonJar(updated);
                configManager.clearAddonJarDeletionMarker(updated);
                configManager.removeCacheEntry(updated.key());
                refreshDefinitions();
                return AddonOperationResult.success("Disabled and deleted " + updated.name() + ". No restart needed.", false);
            }

            if (addonLoader.isLoaded(updated)) {
                configManager.markAddonJarForDeletion(updated);
                plugin.getLogger().info("Managed addon " + updated.name() + " is currently loaded. Its jar will be deleted safely during the next startup pass.");
            } else {
                configManager.removeCacheEntry(updated.key());
            }

            refreshDefinitions();
            return AddonOperationResult.success("Disabled and scheduled deletion for " + updated.name() + " on the next restart.", true);
        });
    }

    public void syncConfiguredAddons() {
        Slimefun.getThreadService().newThread(plugin, "AddonManager#startupReconcile", () -> {
            refreshDefinitions();
            List<AddonDefinition> toLoad = new ArrayList<>();

            for (AddonDefinition definition : compatibleDefinitions().stream().sorted(Comparator.comparing(AddonDefinition::key)).toList()) {
                try {
                    if (!definition.download()) {
                        if (configManager.addonJarExists(definition)) {
                            if (configManager.isAddonJarDeletionScheduled(definition)) {
                                configManager.deleteAddonJar(definition);
                                configManager.clearAddonJarDeletionMarker(definition);
                                configManager.removeCacheEntry(definition.key());
                                plugin.getLogger().info("Deleted managed addon jar because deletion was scheduled: " + definition.name());
                            } else {
                                configManager.appendLog("Preserved existing jar for " + definition.name() + " because download=false was not scheduled by a disable/remove command.");
                                plugin.getLogger().info("Preserved managed addon jar with download=false because no deletion marker exists: " + definition.name());
                            }
                        }
                        continue;
                    }

                    boolean present = configManager.addonJarExists(definition);
                    AddonCacheEntry cache = configManager.getCacheEntry(definition.key()).orElse(null);

                    if (!present) {
                        AddonOperationResult validation = addonLoader.validateInstallCandidate(definition);
                        if (!validation.success()) {
                            plugin.getLogger().warning(validation.message());
                            continue;
                        }

                        AddonOperationResult result = downloadService.download(definition, updateChecker.inspect(definition, cache));
                        if (!result.success()) {
                            plugin.getLogger().warning(result.message());
                            continue;
                        }
                    } else if (definition.autoUpdate()) {
                        RemoteAddonInfo remoteInfo = updateChecker.inspect(definition, cache);
                        if (remoteInfo.updateAvailable()) {
                            AddonOperationResult result = downloadService.download(definition, remoteInfo);
                            if (!result.success()) {
                                plugin.getLogger().warning(result.message());
                            }
                        }
                    }

                    if (definition.enabled() && Files.exists(configManager.resolveAddonPath(definition))) {
                        toLoad.add(definition);
                    }
                } catch (Exception x) {
                    plugin.getLogger().log(Level.WARNING, "Failed to reconcile managed addon \"" + definition.name() + "\"", x);
                }
            }

            Slimefun.runSync(() -> addonLoader.loadManagedAddons(toLoad), 1L);
        });
    }

    private @Nonnull List<AddonDefinition> compatibleDefinitions() {
        return definitions.get().values().stream()
            .filter(definition -> definition.resolveUrlForMinecraftVersion(Slimefun.getMinecraftVersion()).isPresent())
            .toList();
    }

    private @Nonnull CompletableFuture<AddonOperationResult> withDefinition(@Nonnull String key, @Nonnull AddonWork work) {
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        return Slimefun.getThreadService().supplyFuture(plugin, "AddonManager#" + normalizedKey, () -> {
            refreshDefinitions();
            AddonDefinition definition = definitions.get().get(normalizedKey);

            if (definition == null) {
                return AddonOperationResult.failure("Unknown managed addon: " + normalizedKey, null);
            }

            try {
                return work.apply(definition);
            } catch (Exception x) {
                plugin.getLogger().log(Level.WARNING, "Addon operation failed for " + definition.name(), x);
                return AddonOperationResult.failure("Addon operation failed for " + definition.name() + ": " + x.getMessage(), x);
            }
        });
    }

    @FunctionalInterface
    private interface AddonWork {
        AddonOperationResult apply(AddonDefinition definition) throws IOException, InterruptedException;
    }
}
