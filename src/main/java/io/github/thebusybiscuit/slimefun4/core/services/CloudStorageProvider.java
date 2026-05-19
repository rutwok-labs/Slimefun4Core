package io.github.thebusybiscuit.slimefun4.core.services;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

/**
 * Upload target for future Slimefun backup integrations.
 */
public interface CloudStorageProvider {

    CompletableFuture<Boolean> upload(@Nonnull Path localFile, @Nonnull String remotePath);
}
