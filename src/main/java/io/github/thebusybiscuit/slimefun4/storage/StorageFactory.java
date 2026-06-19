package io.github.thebusybiscuit.slimefun4.storage;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.backend.legacy.LegacyStorage;
import io.github.thebusybiscuit.slimefun4.storage.backend.sqlite.SQLiteStorage;

import javax.annotation.Nonnull;

import java.util.Locale;
import java.util.logging.Level;

/**
 * Creates configured {@link Storage} backends for Slimefun.
 */
@Beta
public final class StorageFactory {

    private StorageFactory() {
    }

    /**
     * Creates the configured storage backend wrapped in a {@link StorageCache}.
     *
     * @param plugin
     *            The current {@link Slimefun} instance
     *
     * @return The initialized storage backend
     */
    public static @Nonnull Storage create(@Nonnull Slimefun plugin) {
        Storage backend = createBackend(plugin);

        try {
            backend.initialize();
            return new StorageCache(backend);
        } catch (StorageException x) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize storage backend \"" + backend.getBackendName() + "\", falling back to legacy YAML storage", x);
            Storage fallback = new LegacyStorage();

            try {
                fallback.initialize();
            } catch (StorageException fallbackException) {
                plugin.getLogger().log(Level.SEVERE, "Legacy storage failed to initialize, continuing with best-effort fallback", fallbackException);
            }

            return new StorageCache(fallback);
        }
    }

    private static @Nonnull Storage createBackend(@Nonnull Slimefun plugin) {
        String configuredBackend = Slimefun.getCfg().getString("storage.backend");
        String backendType = configuredBackend == null ? "legacy" : configuredBackend.trim().toLowerCase(Locale.ROOT);

        return switch (backendType) {
            case "sqlite" -> new SQLiteStorage(plugin);
            case "mysql" -> {
                plugin.getLogger().log(Level.WARNING, "MySQL storage is not implemented yet, using legacy YAML storage instead");
                plugin.getLogger().log(Level.WARNING, "Server is running with LEGACY YAML storage. If this is not intended, set storage.backend to 'legacy' explicitly.");
                yield new LegacyStorage();
            }
            default -> new LegacyStorage();
        };
    }
}
