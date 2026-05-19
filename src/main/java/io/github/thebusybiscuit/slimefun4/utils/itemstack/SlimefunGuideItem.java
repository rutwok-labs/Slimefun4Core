package io.github.thebusybiscuit.slimefun4.utils.itemstack;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Colors;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4DataAPI;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideImplementation;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * This is just a helper {@link ItemStack} class for the {@link SlimefunGuide} {@link ItemStack}.
 * 
 * @author TheBusyBiscuit
 * 
 * @see SlimefunGuide
 * @see SlimefunGuideImplementation
 *
 */
public class SlimefunGuideItem extends ItemStack {

    public SlimefunGuideItem(@Nonnull SlimefunGuideImplementation implementation, @Nonnull String name) {
        super(Material.ENCHANTED_BOOK);

        ItemMeta meta = getItemMeta();
        meta.setDisplayName(SF4Colors.color(name));

        List<String> lore = new ArrayList<>();
        SlimefunGuideMode type = implementation.getMode();
        lore.add(type == SlimefunGuideMode.CHEAT_MODE ? SF4Colors.color("&4&lOnly openable by Admins") : "");
        lore.add(SF4Colors.color("&eRight Click &8\u21E8 &7Browse Items"));
        lore.add(SF4Colors.color("&eShift + Right Click &8\u21E8 &7Open Settings / Credits"));

        if (Slimefun.getCfg().getBoolean("slimefun.discord.enabled") && Slimefun.getCfg().getBoolean("slimefun.discord.show-in-guide")) {
            lore.add(SF4Colors.color("&9Discord &8\u21E8 &7" + Slimefun.getCfg().getString("slimefun.discord.invite-url")));
        }

        meta.setLore(lore);

        SF4DataAPI.setString(meta, Slimefun.getRegistry().getGuideDataKey(), type.name());
        Slimefun.getItemTextureService().setTexture(meta, "SLIMEFUN_GUIDE");

        setItemMeta(meta);
    }

}
