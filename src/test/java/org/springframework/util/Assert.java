package org.springframework.util;

/** Minimal stand-in for Spring's Assert (same signatures and exceptions) so fixtures compile without Spring. */
public abstract class Assert {
    public static void notNull(Object object, String message) {
        if (object == null) throw new IllegalArgumentException(message);
    }

    public static void isTrue(boolean expression, String message) {
        if (!expression) throw new IllegalArgumentException(message);
    }

    public static void state(boolean expression, String message) {
        if (!expression) throw new IllegalStateException(message);
    }

    public static void hasText(String text, String message) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(message);
    }
}
