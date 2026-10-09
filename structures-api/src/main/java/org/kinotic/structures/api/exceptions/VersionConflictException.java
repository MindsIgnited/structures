package org.kinotic.structures.api.exceptions;

/**
 * Thrown when a write is rejected because it conflicts with the entity as stored. That is when the entity changed
 * since the version sent with it was read, or an entity being created already exists, for a Structure that uses
 * optimistic locking (an @Version field) or is a stream. For any Structure, it is also when another write changes
 * an entity while an update is applied to it.
 * The REST endpoints answer with a 409 Conflict for it.
 * <p>
 * With a version field, callers can read the entity again to get its current version, and retry.
 * Without one, they can send the update again.
 */
public class VersionConflictException extends RuntimeException {

    // Continuum rebuilds remote exceptions through a single String constructor
    public VersionConflictException(String message) {
        super(message);
    }

}
