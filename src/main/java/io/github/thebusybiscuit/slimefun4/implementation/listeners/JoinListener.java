package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import javax.annotation.Nonnull;

import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Colors;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.api.items.HashedArmorpiece;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.services.UpdaterService.UpdateStatus;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.armor.SlimefunArmorPiece;
import io.github.thebusybiscuit.slimefun4.implementation.tasks.armor.RadiationTask;

/**
 * This {@link Listener} caches the armor of the player on join.
 * This is mainly for the {@link RadiationTask}.
 *
 * @author iTwins
 */
public class JoinListener implements Listener {

    public JoinListener(@Nonnull Slimefun plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(@Nonnull PlayerJoinEvent e) {
        PlayerProfile.get(e.getPlayer(), playerProfile -> {
            final ItemStack[] armorContents = e.getPlayer().getInventory().getArmorContents();
            final HashedArmorpiece[] hashedArmorpieces = playerProfile.getArmor();
            for (int i = 0; i < 4; i++) {
                final ItemStack armorPiece = armorContents[i];
                if (armorPiece != null && armorPiece.getType() != Material.AIR && SlimefunItem.getByItem(armorPiece) instanceof SlimefunArmorPiece sfArmorPiece) {
                    hashedArmorpieces[i].update(armorPiece, sfArmorPiece);
                }
            }

            if (Slimefun.getCfg().getBoolean("slimefun.discord.enabled") && Slimefun.getCfg().getBoolean("slimefun.discord.show-on-join")
                && !playerProfile.getConfig().getBoolean("discord.has-seen-invite")) {
                e.getPlayer().sendMessage(SF4Colors.color(Slimefun.getCfg().getString("slimefun.discord.join-message")));
                playerProfile.getConfig().setValue("discord.has-seen-invite", true);
                playerProfile.getConfig().save();
            }
        });

        if (!Slimefun.getCfg().getBoolean("options.notify-admins-about-updates") || !e.getPlayer().hasPermission("slimefun.command.update")) {
            return;
        }

        UpdateStatus status = Slimefun.getUpdater().getStatus();

        if (status.updateAvailable()) {
            e.getPlayer().sendMessage(SF4Colors.color("&6Slimefun update available: &f" + status.latestVersion()));
            e.getPlayer().sendMessage(SF4Colors.color("&7Current: &f" + status.currentVersion()));

            if (status.projectUrl() != null) {
                e.getPlayer().sendMessage(SF4Colors.color("&7Download: &f" + status.projectUrl()));
            }
        }
    }

}
