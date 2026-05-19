package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.util.Optional;

import javax.annotation.Nonnull;

import org.bukkit.entity.Player;

import io.github.bakedlibs.dough.common.PlayerList;

/**
 * Bridge for {@link PlayerList}.
 * When dough updates PlayerList, edit only this class.
 */
public final class SF4PlayerUtils {

    private SF4PlayerUtils() {}

    /** Delegates to {@link PlayerList#findByName(String)}. */
    public static @Nonnull Optional<Player> findByName(@Nonnull String name) {
        return PlayerList.findByName(name);
    }
}
