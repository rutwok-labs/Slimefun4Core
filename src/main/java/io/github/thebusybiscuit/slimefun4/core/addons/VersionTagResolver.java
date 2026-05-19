package io.github.thebusybiscuit.slimefun4.core.addons;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;

import io.github.thebusybiscuit.slimefun4.api.MinecraftVersion;

/**
 * Resolves the SF4 addon build tag for the running Minecraft version.
 */
public final class VersionTagResolver {

    private VersionTagResolver() {}

    public static @Nonnull String resolveTag(@Nonnull MinecraftVersion mc) {
        Validate.notNull(mc, "Minecraft version must not be null");

        if (mc.isAtLeast(MinecraftVersion.MINECRAFT_1_21)) {
            return "1.21.11";
        }
        if (mc.isAtLeast(MinecraftVersion.MINECRAFT_1_20)) {
            return "1.20.4";
        }
        if (mc.isAtLeast(MinecraftVersion.MINECRAFT_1_19)) {
            return "1.19.4";
        }

        return "1.21.11";
    }
}
