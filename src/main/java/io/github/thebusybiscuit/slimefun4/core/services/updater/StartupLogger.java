package io.github.thebusybiscuit.slimefun4.core.services.updater;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Small prefixed logger for the Slimefun4Core updater startup diagnostics.
 */
public final class StartupLogger {

    private static final String PREFIX = "[Slimefun4Core] ";

    private final Logger logger;

    public StartupLogger(@Nonnull Logger logger) {
        this.logger = logger;
    }

    public void info(@Nonnull String message) {
        logger.log(Level.INFO, PREFIX + message);
    }

    public void warning(@Nonnull String message) {
        logger.log(Level.WARNING, PREFIX + message);
    }

    public void warning(@Nonnull String message, @Nullable Throwable throwable) {
        if (throwable == null) {
            warning(message);
        } else {
            logger.log(Level.WARNING, PREFIX + message, throwable);
        }
    }
}
