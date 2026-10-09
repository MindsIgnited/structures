package org.kinotic.structures.api.exceptions;

/**
 * Thrown when a write is rejected because the entity changed since the version sent with it was read,
 * or because an entity being created already exists.
 * This happens when a Structure uses optimistic locking (an @Version field) or is a stream.
 * The REST endpoints answer with a 409 Conflict for it.
 * <p>
 * Callers can read the entity again to get its current version, and retry.
 */
public class VersionConflictException extends RuntimeException {

    // Continuum rebuilds remote exceptions through a single String constructor
    public VersionConflictException(String message) {
        super(message);
    }

}
