package io.github.thebusybiscuit.slimefun4.core.services.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import io.github.thebusybiscuit.slimefun4.utils.JsonUtils;

/**
 * Stores the last compatible Modrinth release so update checks can survive API outages.
 */
public final class CacheManager {

    private static final Path CACHE_FILE = Path.of("plugins/Slimefun/modrinth-cache.json");

    private final Duration ttl;
    private final StartupLogger logger;

    public CacheManager(@Nonnull Duration ttl, @Nonnull StartupLogger logger) {
        this.ttl = ttl;
        this.logger = logger;
    }

    public @Nonnull Optional<CachedRelease> load(@Nonnull String gameVersion) {
        if (Files.notExists(CACHE_FILE)) {
            return Optional.empty();
        }

        try {
            JsonObject root = JsonUtils.parseString(Files.readString(CACHE_FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            CachedRelease release = new CachedRelease(
                getString(root, "version"),
                getString(root, "assetName"),
                getString(root, "downloadUrl"),
                getLong(root, "cachedAt"),
                getString(root, "gameVersion"),
                getString(root, "projectUrl"),
                parseInstant(getString(root, "publishedAt")),
                getInteger(root, "buildNumber")
            );

            if (!gameVersion.equalsIgnoreCase(release.gameVersion())) {
                return Optional.empty();
            }

            Duration age = Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - release.cachedAt()));
            if (age.compareTo(ttl) > 0) {
                logger.warning("Ignoring expired Modrinth cache (" + formatAge(age) + " old).");
                return Optional.empty();
            }

            logger.info("Modrinth check: from cache (" + formatAge(age) + " ago)");
            return Optional.of(release);
        } catch (RuntimeException | IOException x) {
            logger.warning("Could not read Modrinth cache: " + x.getMessage());
            return Optional.empty();
        }
    }

    public void save(@Nonnull CachedRelease release) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("version", release.version());
            root.addProperty("assetName", release.assetName());
            root.addProperty("downloadUrl", release.downloadUrl());
            root.addProperty("cachedAt", release.cachedAt());
            root.addProperty("gameVersion", release.gameVersion());
            root.addProperty("projectUrl", release.projectUrl());
            root.addProperty("publishedAt", release.publishedAt().toString());

            if (release.buildNumber() != null) {
                root.addProperty("buildNumber", release.buildNumber());
            }

            Path temporary = Files.createTempFile(CACHE_FILE.getParent(), "modrinth-cache-", ".json.tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, CACHE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException x) {
            logger.warning("Could not write Modrinth cache: " + x.getMessage());
        }
    }

    public @Nonnull String formatAge(@Nonnull Duration age) {
        long hours = age.toHours();
        if (hours > 0) {
            return hours + "h";
        }

        long minutes = age.toMinutes();
        if (minutes > 0) {
            return minutes + "m";
        }

        return Math.max(0L, age.toSeconds()) + "s";
    }

    private @Nonnull String getString(@Nonnull JsonObject object, @Nonnull String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private long getLong(@Nonnull JsonObject object, @Nonnull String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : 0L;
    }

    private @Nullable Integer getInteger(@Nonnull JsonObject object, @Nonnull String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : null;
    }

    private @Nonnull Instant parseInstant(@Nonnull String value) {
        try {
            return value.isBlank() ? Instant.EPOCH : Instant.parse(value);
        } catch (RuntimeException ignored) {
            return Instant.EPOCH;
        }
    }

    public record CachedRelease(
        @Nonnull String version,
        @Nonnull String assetName,
        @Nonnull String downloadUrl,
        long cachedAt,
        @Nonnull String gameVersion,
        @Nonnull String projectUrl,
        @Nonnull Instant publishedAt,
        @Nullable Integer buildNumber
    ) {}
}
