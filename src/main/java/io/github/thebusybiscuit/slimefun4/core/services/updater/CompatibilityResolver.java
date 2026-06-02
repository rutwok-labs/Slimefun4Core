package io.github.thebusybiscuit.slimefun4.core.services.updater;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import io.papermc.lib.PaperLib;

/**
 * Resolves the current server environment and validates Modrinth compatibility metadata.
 */
public final class CompatibilityResolver {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?.*");

    public @Nonnull Environment detect(@Nonnull Plugin plugin) {
        String bukkitVersion = plugin.getServer().getBukkitVersion();
        int separator = bukkitVersion.indexOf('-');
        String minecraftVersion = separator >= 0 ? bukkitVersion.substring(0, separator) : bukkitVersion;
        String platform = detectPlatform();
        Set<String> compatibleLoaders = switch (platform.toLowerCase(Locale.ROOT)) {
            case "purpur" -> Set.of("purpur", "paper", "spigot", "bukkit");
            case "paper" -> Set.of("paper", "spigot", "bukkit");
            case "spigot" -> Set.of("spigot", "bukkit");
            default -> Set.of("bukkit");
        };

        return new Environment(
            platform,
            minecraftVersion,
            System.getProperty("java.version", "Unknown"),
            List.of("paper", "purpur", "spigot", "bukkit"),
            compatibleLoaders
        );
    }

    public boolean supportsGameVersion(@Nonnull JsonArray gameVersions, @Nonnull String serverVersion) {
        for (JsonElement element : gameVersions) {
            if (element.isJsonPrimitive() && isCompatibleVersion(element.getAsString(), serverVersion)) {
                return true;
            }
        }

        return false;
    }

    public boolean supportsLoader(@Nonnull JsonArray loaders, @Nonnull Set<String> compatibleLoaders) {
        for (JsonElement element : loaders) {
            if (element.isJsonPrimitive() && compatibleLoaders.contains(element.getAsString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    public boolean isCompatibleVersion(@Nonnull String declaredVersion, @Nonnull String serverVersion) {
        String declared = normalize(declaredVersion);
        String server = normalize(serverVersion);

        if (declared.equalsIgnoreCase(server)) {
            return true;
        }

        if (declared.endsWith(".x")) {
            return server.startsWith(declared.substring(0, declared.length() - 2) + '.');
        }

        int rangeSeparator = declared.indexOf('-');
        if (rangeSeparator > 0) {
            String lower = declared.substring(0, rangeSeparator);
            String upper = declared.substring(rangeSeparator + 1);
            return compareSemantic(server, lower) >= 0 && compareSemantic(server, upper) <= 0;
        }

        return server.startsWith(declared + '.');
    }

    private @Nonnull String detectPlatform() {
        String serverName = Bukkit.getName();
        String serverVersion = Bukkit.getVersion();
        String combined = (serverName + ' ' + serverVersion).toLowerCase(Locale.ROOT);

        if (combined.contains("purpur")) {
            return "Purpur";
        }

        if (PaperLib.isPaper()) {
            return "Paper";
        }

        if (PaperLib.isSpigot()) {
            return "Spigot";
        }

        return "Bukkit";
    }

    private @Nonnull String normalize(@Nonnull String version) {
        String normalized = version.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("minecraft ") ? normalized.substring("minecraft ".length()) : normalized;
    }

    private int compareSemantic(@Nonnull String left, @Nonnull String right) {
        int[] leftParts = parseSemantic(left);
        int[] rightParts = parseSemantic(right);

        for (int i = 0; i < leftParts.length; i++) {
            int comparison = Integer.compare(leftParts[i], rightParts[i]);
            if (comparison != 0) {
                return comparison;
            }
        }

        return 0;
    }

    private int[] parseSemantic(@Nonnull String value) {
        Matcher matcher = VERSION_PATTERN.matcher(value);
        int[] parts = new int[] {0, 0, 0};

        if (!matcher.matches()) {
            return parts;
        }

        for (int i = 0; i < parts.length; i++) {
            String group = matcher.group(i + 1);
            if (group != null) {
                try {
                    parts[i] = Integer.parseInt(group);
                } catch (NumberFormatException ignored) {
                    parts[i] = 0;
                }
            }
        }

        return parts;
    }

    public record Environment(
        @Nonnull String platform,
        @Nonnull String minecraftVersion,
        @Nonnull String javaVersion,
        @Nonnull List<String> queryLoaders,
        @Nonnull Set<String> compatibleLoaders
    ) {}
}
