package io.github.thebusybiscuit.slimefun4.core.guide.options;

import java.util.List;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4DataAPI;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Items;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.core.SlimefunRegistry;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

class FireworksOption implements SlimefunGuideOption<Boolean> {

    @Override
    public SlimefunAddon getAddon() {
        return Slimefun.instance();
    }

    @Override
    public NamespacedKey getKey() {
        return new NamespacedKey(Slimefun.instance(), "research_fireworks");
    }

    @Override
    public Optional<ItemStack> getDisplayItem(Player p, ItemStack guide) {
        SlimefunRegistry registry = Slimefun.getRegistry();

        if (registry.isResearchingEnabled() && registry.isResearchFireworkEnabled()) {
            boolean enabled = getSelectedOption(p, guide).orElse(true);

            String optionState = enabled ? "enabled" : "disabled";
            List<String> lore = Slimefun.getLocalization().getMessages(p, "guide.options.fireworks." + optionState + ".text");
            lore.add("");
            lore.add("&7\u21E8 " + Slimefun.getLocalization().getMessage(p, "guide.options.fireworks." + optionState + ".click"));

            ItemStack item = SF4Items.create(Material.FIREWORK_ROCKET, lore);
            return Optional.of(item);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public void onClick(Player p, ItemStack guide) {
        setSelectedOption(p, guide, !getSelectedOption(p, guide).orElse(true));
        SlimefunGuideSettings.openSettings(p, guide);
    }

    @Override
    public Optional<Boolean> getSelectedOption(Player p, ItemStack guide) {
        NamespacedKey key = getKey();
        boolean value = !SF4DataAPI.hasByte(p, key) || SF4DataAPI.getByte(p, key) == (byte) 1;
        return Optional.of(value);
    }

    @Override
    public void setSelectedOption(Player p, ItemStack guide, Boolean value) {
        SF4DataAPI.setByte(p, getKey(), value.booleanValue() ? (byte) 1 : (byte) 0);
    }

}
