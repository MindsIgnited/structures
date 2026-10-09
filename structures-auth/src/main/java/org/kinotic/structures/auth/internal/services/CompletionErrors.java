package org.kinotic.structures.auth.internal.services;

import java.util.concurrent.CompletionException;

/**
 * A stage that fails because a stage it depends on failed reports a {@link CompletionException} wrapping the
 * original error. These helpers recover the original, so its type and message survive.
 */
final class CompletionErrors {

    private CompletionErrors() {
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
