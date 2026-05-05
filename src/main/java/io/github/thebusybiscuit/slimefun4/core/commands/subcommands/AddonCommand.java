package io.github.thebusybiscuit.slimefun4.core.commands.subcommands;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.command.CommandSender;

import io.github.thebusybiscuit.slimefun4.core.commands.SlimefunCommand;
import io.github.thebusybiscuit.slimefun4.core.commands.SubCommand;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

class AddonCommand extends SubCommand {

    @ParametersAreNonnullByDefault
    AddonCommand(Slimefun plugin, SlimefunCommand cmd) {
        super(plugin, cmd, "addon", false);
    }

    @Override
    public void onExecute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        String[] addonArgs;

        if (args.length <= 1) {
            addonArgs = new String[0];
        } else {
            addonArgs = new String[args.length - 1];
            System.arraycopy(args, 1, addonArgs, 0, addonArgs.length);
        }

        Slimefun.getAddonManager().handleCommand(sender, "sf addon", addonArgs);
    }

    @Override
    public @Nonnull String getDescription(@Nonnull CommandSender sender) {
        return "Managed Slimefun addon administration";
    }
}
