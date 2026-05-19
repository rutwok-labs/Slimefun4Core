package io.github.thebusybiscuit.slimefun4.core.services;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Creates compressed backups of the server's main world folders.
 * Runs alongside the existing BackupService on server shutdown.
 *
 * Backup destination: data-storage/Slimefun/world-backups/yyyy-MM-dd-HH-mm.zip
 * Max retained: 10 world backups by default.
 */
public class WorldBackupService implements Runnable {

    private static final int DEFAULT_MAX_BACKUPS = 10;
    private static final String[] WORLD_FOLDER_NAMES = { "world", "world_nether", "world_the_end" };
    private static final String BACKUP_DIR = "data-storage/Slimefun/world-backups";

    private final Slimefun plugin;
    private final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm", Locale.ROOT);

    public WorldBackupService(@Nonnull Slimefun plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!Slimefun.getCfg().getBoolean("slimefun.world-backup.enabled")) {
            return;
        }

        Instant started = Instant.now();
        Path backupDirectory = Path.of(BACKUP_DIR);
        Path temporary = backupDirectory.resolve(format.format(LocalDateTime.now()) + ".zip.tmp");
        Path target = backupDirectory.resolve(format.format(LocalDateTime.now()) + ".zip");

        try {
            Files.createDirectories(backupDirectory);
            List<Path> worldFolders = getWorldFolders();

            if (worldFolders.isEmpty()) {
                Slimefun.logger().warning("World backup skipped because no configured world folders exist.");
                return;
            }

            long estimatedSize = estimateSize(worldFolders);
            long freeSpace = backupDirectory.toFile().getUsableSpace();
            if (freeSpace < estimatedSize * 2L) {
                Slimefun.logger().warning("World backup skipped because free disk space is below 2x estimated backup size.");
                return;
            }

            Slimefun.logger().info("Starting Slimefun world backup...");
            try (OutputStream outputStream = Files.newOutputStream(temporary); ZipOutputStream zipOutput = new ZipOutputStream(outputStream)) {
                zipOutput.setMethod(ZipOutputStream.DEFLATED);
                for (Path folder : worldFolders) {
                    addWorldFolder(zipOutput, folder);
                }
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            purgeOldBackups(backupDirectory);

            long zipSize = Files.size(target);
            Slimefun.logger().log(Level.INFO, "World backup completed in {0}ms, size={1} bytes: {2}",
                new Object[] { Duration.between(started, Instant.now()).toMillis(), zipSize, target.getFileName() });
            uploadToCloud(target);
        } catch (Exception x) {
            Slimefun.logger().log(Level.SEVERE, x, () -> "An Exception occurred while creating a world backup for Slimefun " + Slimefun.getVersion());
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private @Nonnull List<Path> getWorldFolders() {
        List<Path> worlds = new ArrayList<>();
        boolean includeNether = Slimefun.getCfg().getBoolean("slimefun.world-backup.include-nether");
        boolean includeEnd = Slimefun.getCfg().getBoolean("slimefun.world-backup.include-end");

        for (String worldName : WORLD_FOLDER_NAMES) {
            if ("world_nether".equals(worldName) && !includeNether) {
                continue;
            }
            if ("world_the_end".equals(worldName) && !includeEnd) {
                continue;
            }

            Path path = Path.of(worldName);
            if (Files.isDirectory(path)) {
                worlds.add(path);
            }
        }

        return worlds;
    }

    private long estimateSize(@Nonnull List<Path> folders) throws IOException {
        long total = 0L;

        for (Path folder : folders) {
            total += folderSize(folder);
        }

        return total;
    }

    private long folderSize(@Nonnull Path folder) throws IOException {
        final long[] total = { 0L };

        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!shouldSkip(file)) {
                    total[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return total[0];
    }

    private void addWorldFolder(@Nonnull ZipOutputStream output, @Nonnull Path worldFolder) throws IOException {
        Validate.notNull(output, "Zip output stream cannot be null");
        Validate.notNull(worldFolder, "World folder cannot be null");

        Files.walkFileTree(worldFolder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (shouldSkip(file)) {
                    return FileVisitResult.CONTINUE;
                }

                Path relative = worldFolder.getParent() == null ? worldFolder.relativize(file) : worldFolder.getParent().relativize(file);
                ZipEntry entry = new ZipEntry(relative.toString().replace('\\', '/'));
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean shouldSkip(@Nonnull Path file) {
        String fileName = file.getFileName().toString();
        return "session.lock".equalsIgnoreCase(fileName) || "uid.dat".equalsIgnoreCase(fileName) || fileName.endsWith(".lock");
    }

    private void purgeOldBackups(@Nonnull Path backupDirectory) throws IOException {
        int maxBackups = Math.max(1, Slimefun.getCfg().getOrSetDefault("slimefun.world-backup.max-backups", DEFAULT_MAX_BACKUPS));

        try (var stream = Files.list(backupDirectory)) {
            List<Path> backups = stream
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
                .toList();

            for (int i = maxBackups; i < backups.size(); i++) {
                Files.deleteIfExists(backups.get(i));
            }
        }
    }

    private void uploadToCloud(@Nonnull Path file) {
        CloudStorageProvider provider = (localFile, remotePath) -> {
            Slimefun.logger().info("Cloud upload stub: " + localFile.getFileName() + " -> not yet configured");
            return CompletableFuture.completedFuture(false);
        };

        provider.upload(file, "world-backups/" + file.getFileName());
    }
}
