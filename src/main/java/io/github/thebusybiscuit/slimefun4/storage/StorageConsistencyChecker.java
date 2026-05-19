package io.github.thebusybiscuit.slimefun4.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Config;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.data.PlayerData;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nonnull;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Performs asynchronous read-only consistency checks against player and block storage.
 */
@Beta
public class StorageConsistencyChecker {

    private final Slimefun plugin;
    private final Storage storage;
    private final Storage backendForChecks;
    private final AtomicReference<BukkitTask> scheduledTask = new AtomicReference<>();

    /**
     * Creates a new {@link StorageConsistencyChecker} and schedules periodic checks.
     *
     * @param plugin
     *            The current {@link Slimefun} instance
     * @param storage
     *            The storage backend to inspect
     */
    public StorageConsistencyChecker(@Nonnull Slimefun plugin, @Nonnull Storage storage) {
        Validate.notNull(plugin, "Plugin must not be null");
        Validate.notNull(storage, "Storage must not be null");
        this.plugin = plugin;
        this.storage = storage;
        this.backendForChecks = storage instanceof StorageCache cache ? cache.getBackend() : storage;
        startScheduler();
    }

    /**
     * Starts a new asynchronous consistency check.
     *
     * @return A future with the resulting {@link ConsistencyReport}
     */
    public @Nonnull CompletableFuture<ConsistencyReport> runCheck() {
        return Slimefun.getThreadService().supplyFuture(plugin, "StorageConsistencyChecker#runCheck", this::runCheckSynchronously);
    }

    /**
     * Stops the scheduled consistency task.
     */
    public void shutdown() {
        BukkitTask task = scheduledTask.getAndSet(null);
        if (task != null) {
            task.cancel();
        }
    }

    private void startScheduler() {
        long intervalMinutes = Math.max(1L, Slimefun.getCfg().getLong("storage.consistency-check-interval-minutes"));
        long periodTicks = intervalMinutes * 60L * 20L;

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                runCheckSynchronously();
            } catch (Exception x) {
                plugin.getLogger().log(Level.WARNING, "Consistency check failed", x);
            }
        }, periodTicks, periodTicks);

        scheduledTask.set(task);
    }

    private @Nonnull ConsistencyReport runCheckSynchronously() {
        int playerIssues = 0;
        int blockIssues = 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = PlayerProfile.find(player).orElse(null);
            if (profile == null) {
                continue;
            }

            PlayerData cachedData = profile.getPlayerData();
            PlayerData backendData = backendForChecks.loadPlayerData(player.getUniqueId());

            playerIssues += compareSection("researches", player.getUniqueId().toString(), cachedData.getResearches().size(), backendData.getResearches().size());
            playerIssues += compareSection("backpacks", player.getUniqueId().toString(), cachedData.getBackpacks().size(), backendData.getBackpacks().size());
            playerIssues += compareSection("waypoints", player.getUniqueId().toString(), cachedData.getWaypoints().size(), backendData.getWaypoints().size());
        }

        boolean blockScanEnabled = Slimefun.getCfg().getBoolean("storage.consistency-check-scan-blocks");
        if (blockScanEnabled) {
            File storedBlocksDirectory = new File("data-storage/Slimefun/stored-blocks");
            File[] files = storedBlocksDirectory.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        blockIssues += inspectStoredBlockDirectory(file);
                    } else {
                        blockIssues += inspectStoredBlockFile(file);
                    }
                }
            }
        }

        return new ConsistencyReport(playerIssues, blockIssues);
    }

    private int inspectStoredBlockDirectory(@Nonnull File directory) {
        int issues = 0;
        File[] files = directory.listFiles();

        if (files == null) {
            return 0;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                issues += inspectStoredBlockDirectory(file);
            } else {
                issues += inspectStoredBlockFile(file);
            }
        }

        return issues;
    }

    private int inspectStoredBlockFile(@Nonnull File file) {
        try {
            Config config = SF4Config.forFile(file);
            config.getKeys();
            return 0;
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Stored block file could not be parsed: " + file.getAbsolutePath(), x);
            return 1;
        }
    }

    private int compareSection(@Nonnull String section, @Nonnull String playerId, int cachedCount, int backendCount) {
        if (cachedCount != backendCount) {
            plugin.getLogger().log(Level.WARNING, "Storage consistency mismatch for player {0} in {1}: cached={2}, backend={3}",
                new Object[] { playerId, section, cachedCount, backendCount });
            return 1;
        }

        return 0;
    }

    /**
     * The result of a consistency check run.
     *
     * @param playerIssues
     *            The number of player-data mismatches found
     * @param blockIssues
     *            The number of block-storage issues found
     */
    @Beta
    public record ConsistencyReport(int playerIssues, int blockIssues) {
    }
}
