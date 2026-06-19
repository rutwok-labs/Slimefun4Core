package io.github.thebusybiscuit.slimefun4.core.services.localization;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;

import org.bukkit.configuration.file.FileConfiguration;

import io.github.thebusybiscuit.slimefun4.utils.NumberUtils;

/**
 * Calculates translation completeness without making {@link Language} depend on the localization service singleton.
 */
public final class TranslationProgressCalculator {

    private TranslationProgressCalculator() {}

    public static double calculate(@Nonnull Language english, @Nonnull Language language) {
        Set<String> defaultKeys = getTotalKeys(english);
        if (defaultKeys.isEmpty()) {
            return 0;
        }

        Set<String> keys = getTotalKeys(language);
        int matches = 0;

        for (String key : defaultKeys) {
            if (keys.contains(key)) {
                matches++;
            }
        }

        return Math.min(NumberUtils.reparseDouble(100.0 * (matches / (double) defaultKeys.size())), 100.0);
    }

    private static @Nonnull Set<String> getTotalKeys(@Nonnull Language language) {
        Set<String> keys = new HashSet<>();

        for (FileConfiguration cfg : language.getCoreFiles()) {
            keys.addAll(cfg.getKeys(true));
        }

        return keys;
    }
}
