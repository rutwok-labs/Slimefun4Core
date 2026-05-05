package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Handles safe asynchronous addon downloads.
 */
public final class DownloadService {

    private static final int MAX_RETRIES = 3;
    private static final String USER_AGENT = "Slimefun-AddonManager";

    private final Slimefun plugin;
    private final ConfigManager configManager;
    private final AddonLoader addonLoader;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public DownloadService(@Nonnull Slimefun plugin, @Nonnull ConfigManager configManager, @Nonnull AddonLoader addonLoader) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.addonLoader = addonLoader;
    }

    public @Nonnull CompletableFuture<AddonOperationResult> downloadAsync(@Nonnull AddonDefinition definition, @Nullable RemoteAddonInfo remoteInfo) {
        return Slimefun.getThreadService().supplyFuture(plugin, "AddonManager#download(" + definition.key() + ")", () -> download(definition, remoteInfo));
    }

    public @Nonnull AddonOperationResult download(@Nonnull AddonDefinition definition, @Nullable RemoteAddonInfo remoteInfo) {
        Throwable lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String downloadUrl = remoteInfo != null ? remoteInfo.downloadUrl() : definition.url();
                String expectedChecksum = remoteInfo != null ? remoteInfo.expectedSha256() : normalizeSha(definition.sha256());
                Path target = configManager.resolveAddonPath(definition);
                Path temporary = Files.createTempFile(configManager.getTempDirectory(), definition.key() + "-", ".jar");

                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                    if (response.statusCode() >= 400) {
                        throw new IOException("Download returned HTTP " + response.statusCode() + " for " + downloadUrl);
                    }

                    try (InputStream inputStream = response.body(); OutputStream outputStream = Files.newOutputStream(temporary)) {
                        inputStream.transferTo(outputStream);
                    }

                    String actualChecksum = sha256(temporary);
                    boolean verified = false;

                    if (expectedChecksum != null) {
                        if (!expectedChecksum.equals(actualChecksum)) {
                            throw new IOException("Checksum mismatch for " + definition.name() + " (expected " + expectedChecksum + ", got " + actualChecksum + ')');
                        }

                        verified = true;
                    } else {
                        plugin.getLogger().warning("No SHA256 checksum was available for managed addon \"" + definition.key() + "\". The jar was downloaded but could not be externally verified.");
                    }

                    if (!addonLoader.validateAddonJar(temporary, definition)) {
                        throw new IOException("Downloaded jar for " + definition.name() + " failed plugin validation");
                    }

                    backupExistingJar(target, definition, actualChecksum);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    AddonCacheEntry cacheEntry = new AddonCacheEntry(
                        remoteInfo != null ? remoteInfo.resolvedVersion() : definition.version(),
                        actualChecksum,
                        remoteInfo != null ? remoteInfo.etag() : null,
                        remoteInfo != null ? remoteInfo.lastModified() : null,
                        downloadUrl,
                        Instant.now(),
                        verified
                    );
                    configManager.saveCacheEntry(definition.key(), cacheEntry);

                    return AddonOperationResult.success("Downloaded " + definition.name() + " and staged it for the next restart.", true);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (Throwable x) {
                lastError = x;
                plugin.getLogger().log(Level.WARNING, "Managed addon download attempt " + attempt + " failed for " + definition.name(), x);
            }
        }

        return AddonOperationResult.failure("Failed to download " + definition.name() + " after " + MAX_RETRIES + " attempts.", lastError);
    }

    private @Nonnull String sha256(@Nonnull Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream inputStream = Files.newInputStream(file); DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
            byte[] buffer = new byte[8192];
            while (digestInputStream.read(buffer) != -1) {
                // DigestInputStream updates automatically.
            }
        }

        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private @Nullable String normalizeSha(@Nullable String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private void backupExistingJar(@Nonnull Path target, @Nonnull AddonDefinition definition, @Nonnull String checksum) throws IOException {
        if (Files.notExists(target)) {
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        Path backup = configManager.getBackupDirectory().resolve(definition.key() + "-" + timestamp + ".jar");
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        Path checksumFile = configManager.getBackupDirectory().resolve(definition.key() + "-" + timestamp + ".sha256");
        Files.writeString(checksumFile, checksum, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
