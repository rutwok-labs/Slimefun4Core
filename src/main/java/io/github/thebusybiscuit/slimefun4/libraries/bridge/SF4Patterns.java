package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import io.github.bakedlibs.dough.common.CommonPatterns;

/**
 * Bridge for {@link CommonPatterns}.
 * When dough renames or restructures patterns, edit only this class.
 */
public final class SF4Patterns {

    private SF4Patterns() {}

    /** {@link CommonPatterns#COLON} */
    public static final Pattern COLON = CommonPatterns.COLON;

    /** {@link CommonPatterns#SEMICOLON} */
    public static final Pattern SEMICOLON = CommonPatterns.SEMICOLON;

    /** {@link CommonPatterns#HASH} */
    public static final Pattern HASH = CommonPatterns.HASH;

    /** {@link CommonPatterns#COMMA} */
    public static final Pattern COMMA = CommonPatterns.COMMA;

    /** {@link CommonPatterns#DASH} */
    public static final Pattern DASH = CommonPatterns.DASH;

    /** {@link CommonPatterns#UNDERSCORE} */
    public static final Pattern UNDERSCORE = CommonPatterns.UNDERSCORE;

    /** {@link CommonPatterns#ASCII} */
    public static final Pattern ASCII = CommonPatterns.ASCII;

    /** {@link CommonPatterns#HEXADECIMAL} */
    public static final Pattern HEXADECIMAL = CommonPatterns.HEXADECIMAL;

    /** {@link CommonPatterns#NUMERIC} */
    public static final Pattern NUMERIC = CommonPatterns.NUMERIC;

    /** {@link CommonPatterns#NUMBER_SEPARATOR} */
    public static final Pattern NUMBER_SEPARATOR = CommonPatterns.NUMBER_SEPARATOR;

    /** Delegates to {@link CommonPatterns#SEMICOLON}. */
    public static @Nonnull String[] splitSemicolon(@Nonnull String s) {
        return CommonPatterns.SEMICOLON.split(s);
    }

    /** Delegates to {@link CommonPatterns#COLON}. */
    public static @Nonnull String[] splitColon(@Nonnull String s) {
        return CommonPatterns.COLON.split(s);
    }

    /** Delegates to {@link CommonPatterns#DASH}. */
    public static @Nonnull String[] splitDash(@Nonnull String s) {
        return CommonPatterns.DASH.split(s);
    }

    /** Delegates to {@link CommonPatterns#HASH}. */
    public static @Nonnull String[] splitHash(@Nonnull String s) {
        return CommonPatterns.HASH.split(s);
    }
}
