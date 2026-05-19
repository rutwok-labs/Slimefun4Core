package io.github.thebusybiscuit.slimefun4.core.addons;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.Comparator;
import java.util.Set;

import javax.annotation.Nonnull;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Colors;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Handles /addon commands for the managed addon system.
 */
public final class CommandManager implements CommandExecutor, TabCompleter {

    private static final List<String> ACTIONS = List.of("list", "status", "sync", "download", "update", "remove", "enable", "disable");
    private static final Set<String> ACTIONS_WITH_ADDON_ARGUMENTS = Set.of("download", "update", "remove", "enable", "disable");

    private final Slimefun plugin;
    private final AddonService addonService;
    private final GitHubSyncService gitHubSyncService;

    public CommandManager(@Nonnull Slimefun plugin, @Nonnull AddonService addonService, @Nonnull GitHubSyncService gitHubSyncService) {
        this.plugin = plugin;
        this.addonService = addonService;
        this.gitHubSyncService = gitHubSyncService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return handleCommand(sender, label, args);
    }

    public boolean handleCommand(@Nonnull CommandSender sender, @Nonnull String label, @Nonnull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (!hasAnyPermission(sender)) {
                noPermission(sender);
                return true;
            }

            sendList(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            if (!hasAnyPermission(sender)) {
                noPermission(sender);
                return true;
            }

            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("sync")) {
            if (!sender.hasPermission("addon.admin") && !sender.hasPermission("addon.update")) {
                noPermission(sender);
                return true;
            }

            sender.sendMessage(SF4Colors.color("&7Running remote addonmanager.yml sync..."));
            gitHubSyncService.syncNow().whenComplete((result, error) -> Slimefun.runSync(() -> {
                if (error != null) {
                    sender.sendMessage(SF4Colors.color("&cRemote sync failed: &4" + error.getMessage()));
                    return;
                }

                ChatColor color = result.outcome() == RemoteSyncOutcome.UPDATED || result.outcome() == RemoteSyncOutcome.UNCHANGED
                    ? ChatColor.GREEN
                    : result.outcome() == RemoteSyncOutcome.EMPTY_REMOTE ? ChatColor.YELLOW : ChatColor.RED;
                sender.sendMessage(color + result.message());
            }));
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            sender.sendMessage(SF4Colors.color("&cUsage: /" + label + ' ' + action + " <name>"));
            return true;
        }

        String addonKey = args[1].toLowerCase(Locale.ROOT);
        CompletableFuture<AddonOperationResult> future;

        switch (action) {
            case "download" -> {
                if (!sender.hasPermission("addon.admin") && !sender.hasPermission("addon.update")) {
                    noPermission(sender);
                    return true;
                }
                sender.sendMessage(SF4Colors.color("&7Processing addon download for &f" + addonKey + "&7..."));
                future = addonService.download(addonKey);
            }
            case "update" -> {
                if (!sender.hasPermission("addon.admin") && !sender.hasPermission("addon.update")) {
                    noPermission(sender);
                    return true;
                }
                sender.sendMessage(SF4Colors.color("&7Checking addon update for &f" + addonKey + "&7..."));
                future = addonService.update(addonKey);
            }
            case "remove" -> {
                if (!sender.hasPermission("addon.admin") && !sender.hasPermission("addon.manage")) {
                    noPermission(sender);
                    return true;
                }
                sender.sendMessage(SF4Colors.color("&7Removing managed addon &f" + addonKey + "&7..."));
                future = addonService.remove(addonKey);
            }
            case "enable" -> {
                if (!sender.hasPermission("addon.admin") && !sender.hasPermission("addon.manage")) {
                    noPermission(sender);
                    return true;
                }
                sender.sendMessage(SF4Colors.color("&7Downloading and enabling managed addon &f" + addonKey + "&7 for the next restart..."));
                future = addonService.enable(addonKey);
            }
            case "disable" -> {
                if (!sender.hasPermission("addon.admin") && !sender.hasPermission("addon.manage")) {
                    noPermission(sender);
                    return true;
                }
                sender.sendMessage(SF4Colors.color("&7Disabling and deleting managed addon &f" + addonKey + "&7 for the next restart..."));
                future = addonService.disable(addonKey);
            }
            default -> {
                sender.sendMessage(SF4Colors.color("&cUnknown subcommand. Try /" + label + " list"));
                return true;
            }
        }

        future.whenComplete((result, error) -> Slimefun.runSync(() -> {
            if (error != null) {
                sender.sendMessage(SF4Colors.color("&cAddon operation failed: &4" + error.getMessage()));
                return;
            }

            ChatColor color = result.success() ? ChatColor.GREEN : ChatColor.RED;
            sender.sendMessage(color + result.message());
            if (result.restartRequired()) {
                sender.sendMessage(SF4Colors.color("&eThis change takes effect on the next restart."));
            }
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return tabComplete(args);
    }

    public @Nonnull List<String> tabComplete(@Nonnull String[] args) {
        if (args.length == 1) {
            return ACTIONS.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }

        if (args.length == 2 && ACTIONS_WITH_ADDON_ARGUMENTS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return addonService.listAddons().stream()
                .map(status -> status.definition().key())
                .filter(key -> key.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .sorted()
                .toList();
        }

        return List.of();
    }

    private void sendList(@Nonnull CommandSender sender) {
        List<ManagedAddonStatus> statuses = addonService.listAddons();
        List<Plugin> unmanagedLoadedAddons = getUnmanagedLoadedAddons(statuses);
        int configuredAddons = addonService.countConfiguredAddons();
        int hiddenAddons = Math.max(0, configuredAddons - statuses.size());
        sender.sendMessage("");
        sender.sendMessage(SF4Colors.color("&8[&bAddonManager&8] &7Server version: &f" + Slimefun.getMinecraftVersion().getName() + " &7-> using SF4 build tag: &f" + addonService.getActiveVersionTag()));
        sender.sendMessage(SF4Colors.color("&8[&bAddonManager&8] &7Showing &f" + statuses.size() + " &7compatible addons (&f" + hiddenAddons + " &7hidden - no URL for this version)"));
        sender.sendMessage(SF4Colors.color("&bManaged Slimefun Addons"));

        if (statuses.isEmpty()) {
            sender.sendMessage(SF4Colors.color("&7No addons are configured in addonmanager.yml"));
        } else {
            for (ManagedAddonStatus status : statuses) {
                AddonDefinition definition = status.definition();
                sender.sendMessage(SF4Colors.color(
                    "&7- &f" + definition.key()
                        + "&8 (&b" + definition.name() + "&8) "
                        + (definition.enabled() ? "&aenabled" : "&cdisabled")
                        + "&7, "
                        + (definition.download() ? "&adownload" : "&cno-download")
                        + "&7, jar="
                        + (status.jarPresent() ? "&apresent" : "&cmissing")
                        + "&7, loaded="
                        + (status.loaded() ? "&ayes" : "&cno")
                        + "&7, cached-version=&f" + (status.cachedVersion() != null ? status.cachedVersion() : "none")
                ));
            }
        }

        if (!unmanagedLoadedAddons.isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage(SF4Colors.color("&bOther Installed Slimefun Addons"));

            for (Plugin plugin : unmanagedLoadedAddons) {
                sendLoadedAddonLine(sender, plugin);
            }
        }
    }

    private void sendStatus(@Nonnull CommandSender sender) {
        RemoteSyncSettings settings = gitHubSyncService.getSettings();
        RemoteSyncState state = gitHubSyncService.getState();
        List<ManagedAddonStatus> statuses = addonService.listAddons();
        List<Plugin> unmanagedLoadedAddons = getUnmanagedLoadedAddons(statuses);

        sender.sendMessage("");
        sender.sendMessage(SF4Colors.color("&bAddonManager Cloud Sync Status"));
        sender.sendMessage(SF4Colors.color("&7Enabled: &f" + settings.enabled()));
        sender.sendMessage(SF4Colors.color("&7Interval: &f" + settings.intervalMinutes() + " minute(s)"));
        sender.sendMessage(SF4Colors.color("&7Last Status: &f" + (state.lastStatus() != null ? state.lastStatus() : "never synced")));
        sender.sendMessage(SF4Colors.color("&7Last Sync: &f" + (state.lastSyncedAt() != null ? state.lastSyncedAt() : "never")));
        sender.sendMessage(SF4Colors.color("&7ETag: &f" + (state.etag() != null ? state.etag() : "none")));
        sender.sendMessage(SF4Colors.color("&7Managed entries: &f" + statuses.size()));
        sender.sendMessage(SF4Colors.color("&7Loaded Slimefun addons: &f" + Slimefun.getInstalledAddons().size()));
        sender.sendMessage(SF4Colors.color("&7Loaded unmanaged addons: &f" + unmanagedLoadedAddons.size()));

        if (!unmanagedLoadedAddons.isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage(SF4Colors.color("&bLoaded Addons Outside addonmanager.yml"));

            for (Plugin plugin : unmanagedLoadedAddons) {
                sendLoadedAddonLine(sender, plugin);
            }
        }

        sender.sendMessage("");
        sender.sendMessage(SF4Colors.color("&eNote: Downloaded, updated, enabled, disabled or removed addons take effect only after a server restart."));
    }

    private @Nonnull List<Plugin> getUnmanagedLoadedAddons(@Nonnull List<ManagedAddonStatus> statuses) {
        return Slimefun.getInstalledAddons().stream()
            .filter(plugin -> statuses.stream().noneMatch(status -> matchesManagedEntry(plugin, status.definition())))
            .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private boolean matchesManagedEntry(@Nonnull Plugin plugin, @Nonnull AddonDefinition definition) {
        return plugin.getName().equalsIgnoreCase(definition.name()) || plugin.getName().equalsIgnoreCase(definition.key());
    }

    private void sendLoadedAddonLine(@Nonnull CommandSender sender, @Nonnull Plugin plugin) {
        String authors = plugin.getDescription().getAuthors().isEmpty()
            ? "unknown"
            : String.join(", ", plugin.getDescription().getAuthors());

        sender.sendMessage(SF4Colors.color(
            "&7- &f" + plugin.getName()
                + " &7v&f" + plugin.getDescription().getVersion()
                + "&7, authors=&f" + authors
                + "&7, source=&emanual/runtime"
                + "&7, loaded="
                + (plugin.isEnabled() ? "&ayes" : "&cno")
        ));
    }

    private boolean hasAnyPermission(@Nonnull CommandSender sender) {
        return sender.hasPermission("addon.admin") || sender.hasPermission("addon.update") || sender.hasPermission("addon.manage");
    }

    private void noPermission(@Nonnull CommandSender sender) {
        sender.sendMessage(SF4Colors.color("&cYou do not have permission to manage addons."));
    }
}
