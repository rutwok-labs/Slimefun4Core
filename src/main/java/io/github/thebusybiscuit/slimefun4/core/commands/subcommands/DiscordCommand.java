package io.github.thebusybiscuit.slimefun4.core.commands.subcommands;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.command.CommandSender;

import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Colors;
import io.github.thebusybiscuit.slimefun4.core.commands.SlimefunCommand;
import io.github.thebusybiscuit.slimefun4.core.commands.SubCommand;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.hover.content.Text;

class DiscordCommand extends SubCommand {

    @ParametersAreNonnullByDefault
    DiscordCommand(Slimefun plugin, SlimefunCommand cmd) {
        super(plugin, cmd, "discord", false);
    }

    @Override
    public void onExecute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!Slimefun.getCfg().getBoolean("slimefun.discord.enabled")) {
            sender.sendMessage(SF4Colors.color("&cThe Slimefun Discord invite is disabled."));
            return;
        }

        String url = Slimefun.getCfg().getString("slimefun.discord.invite-url");
        sender.spigot().sendMessage(new ComponentBuilder(SF4Colors.color("&9&lJoin our Discord! &r"))
            .append(url)
            .event(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
            .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to open the Slimefun Discord invite")))
            .create());
    }

    @Override
    public @Nonnull String getDescription(@Nonnull CommandSender sender) {
        return "Shows the Slimefun Discord invite";
    }
}
