package io.github.thebusybiscuit.slimefun4.core.services.localization;

import javax.annotation.Nonnull;

/**
 * Explicit metadata for an embedded {@link LanguagePreset}.
 * This keeps language id, release visibility, text direction and texture hash self-documenting.
 */
public record LanguageMetadata(
    @Nonnull String id,
    boolean releaseReady,
    @Nonnull TextDirection textDirection,
    @Nonnull String textureHash
) {

    public LanguageMetadata {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Language id must not be blank");
        }
        if (textDirection == null) {
            throw new IllegalArgumentException("Text direction must not be null");
        }
        if (textureHash == null || textureHash.isBlank()) {
            throw new IllegalArgumentException("Language texture hash must not be blank");
        }
    }

    public static @Nonnull LanguageMetadata releaseReady(@Nonnull String id, @Nonnull String textureHash) {
        return new LanguageMetadata(id, true, TextDirection.LEFT_TO_RIGHT, textureHash);
    }

    public static @Nonnull LanguageMetadata releaseReady(@Nonnull String id, @Nonnull TextDirection direction, @Nonnull String textureHash) {
        return new LanguageMetadata(id, true, direction, textureHash);
    }

    public static @Nonnull LanguageMetadata workInProgress(@Nonnull String id, @Nonnull String textureHash) {
        return new LanguageMetadata(id, false, TextDirection.LEFT_TO_RIGHT, textureHash);
    }

    public static @Nonnull LanguageMetadata workInProgress(@Nonnull String id, @Nonnull TextDirection direction, @Nonnull String textureHash) {
        return new LanguageMetadata(id, false, direction, textureHash);
    }
}
