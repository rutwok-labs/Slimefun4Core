package io.github.thebusybiscuit.slimefun4.core.services.localization;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;

/**
 * Describes a per-language YAML file under {@code /languages/<id>/}.
 * Core files are still represented by {@link LanguageFile}; addons can register additional descriptors.
 */
public interface LanguageFileDescriptor {

    @Nonnull
    String getFileName();

    default boolean usesServerDefaultConfigDefaults() {
        return false;
    }

    default @Nonnull String getFilePath(@Nonnull Language language) {
        return getFilePath(language.getId());
    }

    default @Nonnull String getFilePath(@Nonnull String languageId) {
        Validate.notNull(languageId, "Language id must not be null!");
        return "/languages/" + languageId + '/' + getFileName();
    }
}
