package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.UnknownDependencyException;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Responsible for validating and loading managed addon jars.
 */
public final class AddonLoader {

    private final Slimefun plugin;
    private final ConfigManager configManager;

    public AddonLoader(@Nonnull Slimefun plugin, @Nonnull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public boolean validateAddonJar(@Nonnull Path jarPath, @Nonnull AddonDefinition definition) {
        Validate.notNull(jarPath, "Jar path must not be null");
        Validate.notNull(definition, "Addon definition must not be null");

        if (Files.notExists(jarPath)) {
            plugin.getLogger().warning("Managed addon jar does not exist: " + jarPath);
            return false;
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            if (jarFile.getEntry("plugin.yml") == null) {
                plugin.getLogger().warning("Managed addon jar is missing plugin.yml: " + jarPath.getFileName());
                return false;
            }

            try (InputStream inputStream = jarFile.getInputStream(jarFile.getEntry("plugin.yml"))) {
                PluginDescriptionFile description = new PluginDescriptionFile(inputStream);

                if (!description.getDepend().contains(plugin.getName()) && !description.getSoftDepend().contains(plugin.getName())) {
                    plugin.getLogger().warning("Managed addon \"" + definition.key() + "\" does not declare a dependency on Slimefun.");
                }

                if (hasDuplicateJar(description.getName(), jarPath, configManager.resolveAddonPath(definition))) {
                    plugin.getLogger().warning("Managed addon \"" + definition.name() + "\" conflicts with another installed jar named " + description.getName() + '.');
                    return false;
                }

                if (!hasRequiredDependencies(description)) {
                    plugin.getLogger().warning("Managed addon \"" + definition.name() + "\" is missing one or more hard dependencies.");
                    return false;
                }

                return true;
            }
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Failed to validate managed addon jar " + jarPath.getFileName(), x);
            return false;
        }
    }

    public @Nonnull AddonOperationResult validateInstallCandidate(@Nonnull AddonDefinition definition) {
        Validate.notNull(definition, "Addon definition must not be null");

        Plugin loadedByName = Bukkit.getPluginManager().getPlugin(definition.name());
        Plugin loadedByKey = Bukkit.getPluginManager().getPlugin(definition.key());

        if (loadedByName != null || loadedByKey != null) {
            if (hasMatchingJarOnDisk(definition)) {
                return AddonOperationResult.failure("Addon " + definition.name() + " is already installed. Remove the existing plugin jar before using the managed installer.", null);
            }

            configManager.appendLog("PluginManager reports " + definition.name() + " as loaded, but no matching jar exists on disk. Treating this as stale state and allowing managed download.");
        }

        try {
            Path expectedPath = configManager.resolveAddonPath(definition);
            String normalizedDefinitionName = definition.name().toLowerCase(java.util.Locale.ROOT);

            for (Path jar : findCandidateJars()) {
                if (isSameFile(jar, expectedPath)) {
                    continue;
                }

                try {
                    PluginDescriptionFile description = readDescription(jar.toFile());
                    if (description.getName().equalsIgnoreCase(definition.name()) || description.getName().toLowerCase(java.util.Locale.ROOT).equals(normalizedDefinitionName)) {
                        return AddonOperationResult.failure("Addon " + definition.name() + " already exists as " + jar.getFileName() + ". Duplicate installs are blocked.", null);
                    }
                } catch (InvalidDescriptionException ignored) {
                    // Ignore non-plugin jars in the server plugins folder.
                }
            }
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Could not complete duplicate addon preflight for " + definition.name(), x);
            return AddonOperationResult.failure("Could not validate addon install safety for " + definition.name() + ": " + x.getMessage(), x);
        }

        int javaFeature = Runtime.version().feature();
        if (javaFeature < 17) {
            return AddonOperationResult.failure("Addon " + definition.name() + " requires a modern Java runtime. Detected Java " + javaFeature + '.', null);
        }

        return AddonOperationResult.success("Addon " + definition.name() + " passed install preflight.", false);
    }

    public boolean hasMatchingJarOnDisk(@Nonnull AddonDefinition definition) {
        Validate.notNull(definition, "Addon definition must not be null");

        for (Path jar : findCandidateJars()) {
            try {
                PluginDescriptionFile description = readDescription(jar.toFile());
                if (description.getName().equalsIgnoreCase(definition.name()) || description.getName().equalsIgnoreCase(definition.key())) {
                    return true;
                }
            } catch (InvalidDescriptionException ignored) {
                // Ignore non-plugin jars in the server plugins folder.
            }
        }

        return false;
    }

    public void loadManagedAddons(@Nonnull Collection<AddonDefinition> definitions) {
        PluginManager pluginManager = plugin.getServer().getPluginManager();

        for (AddonDefinition definition : definitions) {
            if (!definition.enabled() || !definition.download()) {
                continue;
            }

            Path addonPath = configManager.resolveAddonPath(definition);

            if (!validateAddonJar(addonPath, definition)) {
                continue;
            }

            try {
                PluginDescriptionFile description = readDescription(addonPath.toFile());

                if (pluginManager.getPlugin(description.getName()) != null) {
                    plugin.getLogger().info("Managed addon already present in the plugin manager, skipping duplicate load: " + description.getName());
                    continue;
                }

                Plugin managedPlugin = pluginManager.loadPlugin(addonPath.toFile());
                if (managedPlugin != null) {
                    pluginManager.enablePlugin(managedPlugin);
                    plugin.getLogger().info("Loaded managed Slimefun addon: " + managedPlugin.getName() + " v" + managedPlugin.getDescription().getVersion());
                }
            } catch (UnknownDependencyException | InvalidPluginException | InvalidDescriptionException x) {
                plugin.getLogger().log(Level.WARNING, "Failed to load managed addon \"" + definition.name() + "\"", x);
            }
        }
    }

    public boolean isLoaded(@Nonnull AddonDefinition definition) {
        Path addonPath = configManager.resolveAddonPath(definition);

        if (Files.exists(addonPath)) {
            try {
                PluginDescriptionFile description = readDescription(addonPath.toFile());
                Plugin pluginInstance = Bukkit.getPluginManager().getPlugin(description.getName());
                return pluginInstance != null && pluginInstance.isEnabled();
            } catch (InvalidDescriptionException x) {
                plugin.getLogger().log(Level.WARNING, "Failed to read plugin.yml for managed addon \"" + definition.name() + "\"", x);
                return false;
            }
        }

        Plugin pluginInstance = Bukkit.getPluginManager().getPlugin(definition.name());
        return pluginInstance != null && pluginInstance.isEnabled();
    }

    public @Nonnull Set<String> getLoadedManagedAddonNames(@Nonnull Collection<AddonDefinition> definitions) {
        Set<String> names = new LinkedHashSet<>();

        for (AddonDefinition definition : definitions) {
            if (isLoaded(definition)) {
                names.add(definition.name());
            }
        }

        return names;
    }

    private @Nonnull PluginDescriptionFile readDescription(@Nonnull File file) throws InvalidDescriptionException {
        try (JarFile jarFile = new JarFile(file);
             InputStream inputStream = jarFile.getInputStream(jarFile.getEntry("plugin.yml"))) {
            return new PluginDescriptionFile(inputStream);
        } catch (InvalidDescriptionException x) {
            throw x;
        } catch (Exception x) {
            throw new InvalidDescriptionException(x);
        }
    }

    private boolean hasRequiredDependencies(@Nonnull PluginDescriptionFile description) throws InvalidDescriptionException {
        for (String dependency : description.getDepend()) {
            if (dependency.equalsIgnoreCase(plugin.getName())) {
                continue;
            }

            if (Bukkit.getPluginManager().getPlugin(dependency) == null && !repositoryContainsPlugin(dependency)) {
                plugin.getLogger().warning("Missing hard dependency for managed addon " + description.getName() + ": " + dependency);
                return false;
            }
        }

        return true;
    }

    private boolean repositoryContainsPlugin(@Nonnull String pluginName) {
        try {
            for (File jar : configManager.getRepositoryJars()) {
                try {
                    PluginDescriptionFile description = readDescription(jar);
                    if (description.getName().equalsIgnoreCase(pluginName)) {
                        return true;
                    }
                } catch (InvalidDescriptionException ignored) {
                    // Ignore invalid jars here, validateAddonJar will report them when selected.
                }
            }
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Failed to inspect managed addon repository dependencies", x);
        }

        return false;
    }

    private boolean hasDuplicateJar(@Nonnull String pluginName, @Nonnull Path currentJar, @Nonnull Path expectedManagedJar) throws InvalidDescriptionException {
        for (Path jar : findCandidateJars()) {
            if (isSameFile(jar, currentJar) || isSameFile(jar, expectedManagedJar)) {
                continue;
            }

            try {
                PluginDescriptionFile description = readDescription(jar.toFile());
                if (description.getName().equalsIgnoreCase(pluginName)) {
                    return true;
                }
            } catch (InvalidDescriptionException ignored) {
                // Ignore invalid jars here, validateAddonJar reports the selected jar explicitly.
            }
        }

        return false;
    }

    private @Nonnull Set<Path> findCandidateJars() {
        Set<Path> jars = new LinkedHashSet<>();
        Path pluginsDirectory = plugin.getDataFolder().toPath().getParent();

        if (pluginsDirectory != null && Files.isDirectory(pluginsDirectory)) {
            try (var stream = Files.list(pluginsDirectory)) {
                stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .forEach(jars::add);
            } catch (Exception x) {
                plugin.getLogger().log(Level.WARNING, "Failed to scan server plugin directory for managed addon duplicates", x);
            }
        }

        try {
            for (File jar : configManager.getRepositoryJars()) {
                jars.add(jar.toPath());
            }
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Failed to scan managed addon repository for duplicates", x);
        }

        return jars;
    }

    private boolean isSameFile(@Nonnull Path left, @Nonnull Path right) {
        try {
            return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
        } catch (Exception x) {
            return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
        }
    }
}
