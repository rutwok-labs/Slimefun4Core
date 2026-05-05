package io.github.thebusybiscuit.slimefun4.core.addons;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Result of an addon management operation.
 */
public record AddonOperationResult(
    boolean success,
    boolean restartRequired,
    @Nonnull String message,
    @Nullable Throwable error
) {
    public static @Nonnull AddonOperationResult success(@Nonnull String message, boolean restartRequired) {
        return new AddonOperationResult(true, restartRequired, message, null);
    }

    public static @Nonnull AddonOperationResult failure(@Nonnull String message, @Nullable Throwable error) {
        return new AddonOperationResult(false, false, message, error);
    }
}
