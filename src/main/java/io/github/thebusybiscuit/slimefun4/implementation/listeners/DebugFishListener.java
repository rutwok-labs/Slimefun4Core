package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Colors;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetProvider;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.utils.HeadTexture;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.github.thebusybiscuit.slimefun4.utils.tags.SlimefunTag;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * This {@link Listener} is responsible for handling our debugging tool, the debug fish.
 * This is where the functionality of this item is implemented.
 * 
 * @author TheBusyBiscuit
 *
 */
public class DebugFishListener implements Listener {

    private final String greenCheckmark;
    private final String redCross;

    public DebugFishListener(@Nonnull Slimefun plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        greenCheckmark = "&2\u2714";
        redCross = "&4\u2718";
    }

    @EventHandler
    public void onDebug(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL || e.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player p = e.getPlayer();

        if (SlimefunUtils.isItemSimilar(e.getItem(), SlimefunItems.DEBUG_FISH.item(), true, false)) {
            e.setCancelled(true);

            if (p.hasPermission("slimefun.debugging")) {
                if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                    onLeftClick(p, e.getClickedBlock(), e);
                } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    onRightClick(p, e.getClickedBlock(), e.getBlockFace());
                }
            } else {
                Slimefun.getLocalization().sendMessage(p, "messages.no-permission", true);
            }
        }
    }

    @ParametersAreNonnullByDefault
    private void onLeftClick(Player p, Block b, PlayerInteractEvent e) {
        if (p.isSneaking()) {
            if (BlockStorage.hasBlockInfo(b)) {
                BlockStorage.clearBlockInfo(b);
            }
        } else {
            e.setCancelled(false);
        }
    }

    @ParametersAreNonnullByDefault
    private void onRightClick(Player p, Block b, BlockFace face) {
        if (p.isSneaking()) {
            // Fixes #2655 - Delaying the placement to prevent a new event from being fired
            Slimefun.runSync(() -> {
                Block block = b.getRelative(face);
                block.setType(Material.PLAYER_HEAD);

                SlimefunUtils.setCustomHead(block, HeadTexture.MISSING_TEXTURE.getTexture(), true);
                SoundEffect.DEBUG_FISH_CLICK_SOUND.playFor(p);
            }, 2L);
        } else if (BlockStorage.hasBlockInfo(b)) {
            try {
                sendInfo(p, b);
            } catch (Exception x) {
                Slimefun.logger().log(Level.SEVERE, "An Exception occurred while using a Debug-Fish", x);
            }
        } else {
            // Read applicable Slimefun tags
            Set<SlimefunTag> tags = EnumSet.noneOf(SlimefunTag.class);

            for (SlimefunTag tag : SlimefunTag.values()) {
                if (tag.isTagged(b.getType())) {
                    tags.add(tag);
                }
            }

            if (!tags.isEmpty()) {
                p.sendMessage(" ");
                p.sendMessage(SF4Colors.color("&dSlimefun tags for: &e") + b.getType().name());

                for (SlimefunTag tag : tags) {
                    p.sendMessage(SF4Colors.color("&d* &e") + tag.name());
                }

                p.sendMessage(" ");
            }
        }
    }

    @ParametersAreNonnullByDefault
    private void sendInfo(Player p, Block b) {
        SlimefunItem item = BlockStorage.check(b);

        p.sendMessage(" ");
        p.sendMessage(SF4Colors.color("&d" + b.getType() + " &e@ X: " + b.getX() + " Y: " + b.getY() + " Z: " + b.getZ()));
        p.sendMessage(SF4Colors.color("&dId: " + "&e" + item.getId()));
        p.sendMessage(SF4Colors.color("&dPlugin: " + "&e" + item.getAddon().getName()));

        if (b.getState() instanceof Skull) {
            p.sendMessage(SF4Colors.color("&dSkull: " + greenCheckmark));

            // Check if the skull is a wall skull, and if so use Directional instead of Rotatable.
            if (b.getType() == Material.PLAYER_WALL_HEAD) {
                p.sendMessage(SF4Colors.color("  &dFacing: &e" + ((Directional) b.getBlockData()).getFacing().toString()));
            } else {
                p.sendMessage(SF4Colors.color("  &dRotation: &e" + ((Rotatable) b.getBlockData()).getRotation().toString()));
            }
        }

        if (BlockStorage.getStorage(b.getWorld()).hasInventory(b.getLocation())) {
            p.sendMessage(SF4Colors.color("&dInventory: " + greenCheckmark));
        } else {
            p.sendMessage(SF4Colors.color("&dInventory: " + redCross));
        }

        if (item.isTicking()) {
            p.sendMessage(SF4Colors.color("&dTicking: " + greenCheckmark));
            p.sendMessage(SF4Colors.color("  &dAsync: &e" + (item.getBlockTicker().isSynchronized() ? redCross : greenCheckmark)));
        } else if (item instanceof EnergyNetProvider) {
            p.sendMessage(SF4Colors.color("&dTicking: &3Indirect (Generator)"));
        } else {
            p.sendMessage(SF4Colors.color("&dTicking: " + redCross));
        }

        if (Slimefun.getProfiler().hasTimings(b)) {
            p.sendMessage(SF4Colors.color("  &dTimings: &e" + Slimefun.getProfiler().getTime(b)));
            p.sendMessage(SF4Colors.color("  &dTotal Timings: &e" + Slimefun.getProfiler().getTime(item)));
            p.sendMessage(SF4Colors.color("  &dChunk Timings: &e" + Slimefun.getProfiler().getTime(b.getChunk())));
        }

        if (item instanceof EnergyNetComponent component) {
            p.sendMessage(SF4Colors.color("&dEnergyNet Component"));
            p.sendMessage(SF4Colors.color("  &dType: &e" + component.getEnergyComponentType()));

            if (component.isChargeable()) {
                p.sendMessage(SF4Colors.color("  &dChargeable: " + greenCheckmark));
                p.sendMessage(SF4Colors.color("  &dEnergy: &e" + component.getCharge(b.getLocation()) + " / " + component.getCapacity()));
            } else {
                p.sendMessage(SF4Colors.color("&dChargeable: " + redCross));
            }
        }

        p.sendMessage(SF4Colors.color("&6" + BlockStorage.getBlockInfoAsJson(b)));
        p.sendMessage(" ");
    }
}
