package io.github.thebusybiscuit.slimefun4.core.services.localization;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;

/**
 * This enum holds the different types of files each {@link Language} holds.
 * 
 * @author TheBusyBiscuit
 * 
 * @see Language
 * @see SlimefunLocalization
 *
 */
public enum LanguageFile implements LanguageFileDescriptor {

    MESSAGES("messages.yml", true),
    CATEGORIES("categories.yml"),
    RECIPES("recipes.yml"),
    RESOURCES("resources.yml"),
    RESEARCHES("researches.yml");

    protected static final LanguageFile[] valuesCached = values();

    private final String fileName;
    private final boolean serverDefaultConfigDefaults;

    LanguageFile(@Nonnull String fileName) {
        this(fileName, false);
    }

    LanguageFile(@Nonnull String fileName, boolean serverDefaultConfigDefaults) {
        this.fileName = fileName;
        this.serverDefaultConfigDefaults = serverDefaultConfigDefaults;
    }

    @Override
    @Nonnull
    public String getFileName() {
        return fileName;
    }

    @Override
    @Nonnull
    public String getFilePath(@Nonnull Language language) {
        return LanguageFileDescriptor.super.getFilePath(language);
    }

    @Override
    @Nonnull
    public String getFilePath(@Nonnull String languageId) {
        Validate.notNull(languageId, "Language id must not be null!");
        return LanguageFileDescriptor.super.getFilePath(languageId);
    }

    @Override
    public boolean usesServerDefaultConfigDefaults() {
        return serverDefaultConfigDefaults;
    }
}
