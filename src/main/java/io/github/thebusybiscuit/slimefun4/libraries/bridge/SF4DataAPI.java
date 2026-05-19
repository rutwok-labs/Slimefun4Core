package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataHolder;

import io.github.bakedlibs.dough.data.persistent.PersistentDataAPI;

/**
 * Bridge for {@link PersistentDataAPI}.
 * When dough updates PersistentDataAPI, edit only this class.
 */
public final class SF4DataAPI {

    private SF4DataAPI() {}

    /** Delegates to {@link PersistentDataAPI#setString(PersistentDataHolder, NamespacedKey, String)}. */
    public static void setString(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key, @Nonnull String value) {
        PersistentDataAPI.setString(holder, key, value);
    }

    /** Delegates to {@link PersistentDataAPI#getString(PersistentDataHolder, NamespacedKey)}. */
    public static @Nullable String getString(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key) {
        return PersistentDataAPI.getString(holder, key);
    }

    /** Delegates to {@link PersistentDataAPI#getOptionalString(PersistentDataHolder, NamespacedKey)}. */
    public static @Nonnull Optional<String> getOptionalString(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key) {
        return PersistentDataAPI.getOptionalString(holder, key);
    }

    /** Delegates to {@link PersistentDataAPI#setInt(PersistentDataHolder, NamespacedKey, int)}. */
    public static void setInt(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key, int value) {
        PersistentDataAPI.setInt(holder, key, value);
    }

    /** Delegates to {@link PersistentDataAPI#getInt(PersistentDataHolder, NamespacedKey, int)}. */
    public static int getInt(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key, int defaultValue) {
        return PersistentDataAPI.getInt(holder, key, defaultValue);
    }

    /** Delegates to {@link PersistentDataAPI#setByte(PersistentDataHolder, NamespacedKey, byte)}. */
    public static void setByte(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key, byte value) {
        PersistentDataAPI.setByte(holder, key, value);
    }

    /** Delegates to {@link PersistentDataAPI#getByte(PersistentDataHolder, NamespacedKey)}. */
    public static byte getByte(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key) {
        return PersistentDataAPI.getByte(holder, key);
    }

    /** Delegates to {@link PersistentDataAPI#hasByte(PersistentDataHolder, NamespacedKey)}. */
    public static boolean hasByte(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key) {
        return PersistentDataAPI.hasByte(holder, key);
    }

    /** Delegates to {@link PersistentDataAPI#remove(PersistentDataHolder, NamespacedKey)}. */
    public static void remove(@Nonnull PersistentDataHolder holder, @Nonnull NamespacedKey key) {
        PersistentDataAPI.remove(holder, key);
    }
}
