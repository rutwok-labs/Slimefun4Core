package io.github.thebusybiscuit.slimefun4.core.services.localization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;

/**
 * Registry for language file descriptors.
 * Built-in files are registered first, custom descriptors may be appended by integrations.
 */
public final class LanguageFileRegistry {

    private static final List<LanguageFileDescriptor> FILES = new ArrayList<>();
    private static final List<LanguageFileDescriptor> CORE_FILES = List.of(LanguageFile.valuesCached);

    static {
        FILES.addAll(CORE_FILES);
    }

    private LanguageFileRegistry() {}

    public static synchronized void register(@Nonnull LanguageFileDescriptor descriptor) {
        Validate.notNull(descriptor, "Language file descriptor cannot be null");

        if (!FILES.contains(descriptor)) {
            FILES.add(descriptor);
        }
    }

    public static synchronized @Nonnull List<LanguageFileDescriptor> getFiles() {
        return Collections.unmodifiableList(new ArrayList<>(FILES));
    }

    public static @Nonnull List<LanguageFileDescriptor> getCoreFiles() {
        return CORE_FILES;
    }
}
