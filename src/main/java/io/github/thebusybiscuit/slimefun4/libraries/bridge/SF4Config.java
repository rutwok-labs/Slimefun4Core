package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.io.File;

import javax.annotation.Nonnull;

import org.bukkit.plugin.Plugin;

import io.github.bakedlibs.dough.config.Config;

/**
 * Bridge for {@link Config}.
 * Provides factory methods to replace direct constructor calls.
 */
public final class SF4Config {

    private SF4Config() {}

    /** Factory for {@code new Config(plugin)}. */
    public static @Nonnull Config forPlugin(@Nonnull Plugin plugin) {
        return new Config(plugin);
    }

    /** Factory for {@code new Config(plugin, fileName)}. */
    public static @Nonnull Config forPlugin(@Nonnull Plugin plugin, @Nonnull String fileName) {
        return new Config(plugin, fileName);
    }

    /** Factory for {@code new Config(file)}. */
    public static @Nonnull Config forFile(@Nonnull File file) {
        return new Config(file);
    }

    /** Factory for {@code new Config(path)}. */
    public static @Nonnull Config forPath(@Nonnull String path) {
        return new Config(path);
    }
}
