package org.kinotic.structures.auth.internal.services;

/**
 * Text from an unverified token, or derived from one such as a JWT library's error message, made safe to log
 * or return to a client
 */
final class UntrustedText {

    private static final int MAX_LENGTH = 200;

    private UntrustedText() {
    }

    /**
     * Shortened to {@link #MAX_LENGTH} characters, with control characters such as newlines replaced, so it
     * cannot forge log lines or bloat them
     */
    static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String shortened = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "..." : text;
        StringBuilder safe = new StringBuilder(shortened.length());
        shortened.codePoints().forEach(c -> safe.appendCodePoint(Character.isISOControl(c) ? '?' : c));
        return safe.toString();
    }
}
