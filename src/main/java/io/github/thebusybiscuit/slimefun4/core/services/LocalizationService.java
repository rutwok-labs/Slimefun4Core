package io.github.thebusybiscuit.slimefun4.core.services;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.apache.commons.lang.Validate;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import io.github.thebusybiscuit.slimefun4.core.services.localization.Language;
import io.github.thebusybiscuit.slimefun4.core.services.localization.LanguageFile;
import io.github.thebusybiscuit.slimefun4.core.services.localization.LanguageFileDescriptor;
import io.github.thebusybiscuit.slimefun4.core.services.localization.LanguageFileRegistry;
import io.github.thebusybiscuit.slimefun4.core.services.localization.SlimefunLocalization;
import io.github.thebusybiscuit.slimefun4.core.services.localization.TranslationProgressCalculator;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.PatternUtils;

/**
 * As the name suggests, this Service is responsible for Localization.
 * It is used for managing the {@link Language} of a {@link Player} and the entire {@link Server}.
 * 
 * @author TheBusyBiscuit
 * 
 * @see Language
 *
 */
public class LocalizationService extends SlimefunLocalization {

    private static final String LANGUAGE_PATH = "language";

    // All supported languages are stored in this LinkedHashMap, it is Linked so we keep the order
    private final Map<String, Language> languages = new LinkedHashMap<>();
    private final Set<String> loggedStubFiles = ConcurrentHashMap.newKeySet();
    private final boolean translationsEnabled;
    private final Slimefun plugin;
    private final String prefix;
    private final NamespacedKey languageKey;
    private final Language defaultLanguage;

    public LocalizationService(@Nonnull Slimefun plugin, @Nullable String prefix, @Nullable String serverDefaultLanguage) {
        super(plugin);

        this.plugin = plugin;
        this.prefix = prefix;
        languageKey = new NamespacedKey(plugin, LANGUAGE_PATH);

        if (serverDefaultLanguage != null) {
            translationsEnabled = Slimefun.getCfg().getBoolean("options.enable-translations");

            defaultLanguage = new Language(serverDefaultLanguage, "11b3188fd44902f72602bd7c2141f5a70673a411adb3d81862c69e536166b");
            defaultLanguage.setFile(LanguageFile.MESSAGES, getConfig().getConfiguration());

            loadEmbeddedLanguages();

            String language = getConfig().getString(LANGUAGE_PATH);

            if (language == null) {
                language = serverDefaultLanguage;
            }

            if (hasLanguage(serverDefaultLanguage)) {
                setLanguage(serverDefaultLanguage, !serverDefaultLanguage.equals(language));
            } else {
                setLanguage("en", false);
                plugin.getLogger().log(Level.WARNING, "Could not recognize the given language: \"{0}\"", serverDefaultLanguage);
            }

            Slimefun.logger().log(Level.INFO, "Available languages: {0}", String.join(", ", languages.keySet()));
            save();
        } else {
            translationsEnabled = false;
            defaultLanguage = null;
        }
    }

    /**
     * This method returns whether translations are enabled on this {@link Server}.
     * 
     * @return Whether translations are enabled
     */
    public boolean isEnabled() {
        return translationsEnabled;
    }

    @Override
    public String getChatPrefix() {
        return prefix;
    }

    @Override
    @Nonnull
    public NamespacedKey getKey() {
        return languageKey;
    }

    @Override
    @Nullable
    public Language getLanguage(@Nonnull String id) {
        Validate.notNull(id, "The language id cannot be null");
        return languages.get(id);
    }

    @Override
    @Nonnull
    public Collection<Language> getLanguages() {
        return languages.values();
    }

    @Override
    public boolean hasLanguage(@Nonnull String id) {
        Validate.notNull(id, "The language id cannot be null");

        // Checks if our jar files contains a messages.yml file for that language
        String file = LanguageFile.MESSAGES.getFilePath(id);
        return !getConfigurationFromStream(file, null, false).getKeys(false).isEmpty();
    }

    /**
     * This returns whether the given {@link Language} is loaded or not.
     * 
     * @param id
     *            The id of that {@link Language}
     * 
     * @return Whether or not this {@link Language} is loaded
     */
    public boolean isLanguageLoaded(@Nonnull String id) {
        Validate.notNull(id, "The language cannot be null!");
        return languages.containsKey(id);
    }

    @Override
    public Language getDefaultLanguage() {
        return defaultLanguage;
    }

    @Override
    public Language getLanguage(@Nonnull Player p) {
        Validate.notNull(p, "Player cannot be null!");

        PersistentDataContainer container = p.getPersistentDataContainer();
        String language = container.get(languageKey, PersistentDataType.STRING);

        if (language != null) {
            Language lang = languages.get(language);

            if (lang != null) {
                return lang;
            }
        }

        return getDefaultLanguage();
    }

    private void setLanguage(@Nonnull String language, boolean reset) {
        // Clearing out the old Language (if necessary)
        if (reset) {
            getConfig().clear();
        }

        // Copy all non-message resource files for the selected server default language.
        for (LanguageFileDescriptor file : LanguageFileRegistry.getFiles()) {
            if (!file.usesServerDefaultConfigDefaults()) {
                copyToDefaultLanguage(language, file);
            }
        }

        Slimefun.logger().log(Level.INFO, "Loaded language \"{0}\"", language);
        getConfig().setValue(LANGUAGE_PATH, language);

        // Loading in the defaults from our resources folder
        String path = "/languages/" + language + "/messages.yml";

        FileConfiguration config = getConfigurationFromStream(path, null);
        getConfig().getConfiguration().setDefaults(config);

        save();
    }

    @ParametersAreNonnullByDefault
    private void copyToDefaultLanguage(String language, LanguageFileDescriptor file) {
        FileConfiguration config = getConfigurationFromStream(file.getFilePath(language), null);
        defaultLanguage.setFile(file, config);
    }

    @Override
    protected void addLanguage(@Nonnull String id, @Nonnull String texture) {
        Validate.notNull(id, "The language id cannot be null!");
        Validate.notNull(texture, "The language texture cannot be null");

        if (hasLanguage(id)) {
            Language language = new Language(id, texture);
            language.setTranslationProgressProvider(this::calculateProgress);

            loadLanguageFiles(language, id);

            languages.put(id, language);
        }
    }

    /**
     * Loads every registered language file for the given {@link Language}.
     * The messages.yml file gets server-default defaults because server owners may override chat/UI messages in plugins/Slimefun/messages.yml.
     */
    private void loadLanguageFiles(@Nonnull Language language, @Nonnull String id) {
        for (LanguageFileDescriptor file : LanguageFileRegistry.getFiles()) {
            FileConfiguration defaults = file.usesServerDefaultConfigDefaults() ? getConfig().getConfiguration() : null;
            FileConfiguration config = getConfigurationFromStream(file.getFilePath(id), defaults);
            language.setFile(file, config);
        }
    }

    public void registerLanguageFile(@Nonnull LanguageFileDescriptor file) {
        Validate.notNull(file, "Language file descriptor cannot be null!");
        LanguageFileRegistry.register(file);

        for (Language language : languages.values()) {
            FileConfiguration config = getConfigurationFromStream(file.getFilePath(language), null);
            language.setFile(file, config);
        }
    }

    /**
     * This returns the progress of translation for any given {@link Language}.
     * The progress is determined by the amount of translated strings divided by the amount
     * of strings in the english {@link Language} file and multiplied by 100.0
     * 
     * @param lang
     *            The {@link Language} to get the progress of
     * 
     * @return A percentage {@code (0.0 - 100.0)} for the progress of translation of that {@link Language}
     */
    public double calculateProgress(@Nonnull Language lang) {
        Validate.notNull(lang, "Cannot get the language progress of null");

        Language english = languages.get("en");
        if (english == null) {
            return 0;
        }
        if (lang.getId().equals("en")) {
            return 100.0;
        }

        return TranslationProgressCalculator.calculate(english, lang);
    }

    private @Nonnull FileConfiguration getConfigurationFromStream(@Nonnull String file, @Nullable FileConfiguration defaults) {
        return getConfigurationFromStream(file, defaults, true);
    }

    private @Nonnull FileConfiguration getConfigurationFromStream(@Nonnull String file, @Nullable FileConfiguration defaults, boolean reportMissing) {
        InputStream inputStream = plugin.getClass().getResourceAsStream(file);

        if (inputStream == null) {
            if (reportMissing) {
                Slimefun.logger().log(Level.FINE, "Language file is missing: \"{0}\"", file);
            }
            return new YamlConfiguration();
        }

        try (inputStream) {
            byte[] bytes = inputStream.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            YamlConfiguration config = new YamlConfiguration();

            if (isLanguageStub(bytes, content)) {
                logStubOnce(file);
                return config;
            }

            if (!PatternUtils.YAML_ENTRY.matcher(content).find()) {
                Slimefun.logger().log(Level.FINE, "Language file has no YAML entries: \"{0}\"", file);
                return config;
            }

            config.loadFromString(content);

            if (defaults != null) {
                config.setDefaults(defaults);
            }

            return config;
        } catch (IOException | InvalidConfigurationException e) {
            Slimefun.logger().log(Level.WARNING, e, () -> "Failed to load language file into memory: \"" + file + "\"");
            return new YamlConfiguration();
        }
    }

    private boolean isLanguageStub(byte[] bytes, String content) {
        return bytes.length <= 4 || content.isBlank();
    }

    private void logStubOnce(@Nonnull String file) {
        if (loggedStubFiles.add(file)) {
            Slimefun.logger().log(Level.FINE, "Skipping placeholder language stub: \"{0}\"", file);
        }
    }
}
