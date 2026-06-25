package com.platform.common.util;

/**
 * Small stateless string utilities shared across modules.
 */
public final class Strings {

    private Strings() {
    }

    /**
     * Trims {@code s} and returns {@code null} if the result is empty (or the input was null).
     */
    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
