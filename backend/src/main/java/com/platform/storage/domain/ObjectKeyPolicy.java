package com.platform.storage.domain;

/**
 * Shared ownership + safety policy for user-owned object keys. Encodes the rules that BOTH
 * {@code StoragePresignService} (presign PUT) and {@code ContentCommandService} (metadata
 * cover/file references) apply, so the two paths cannot drift: a key that the storage service
 * would reject must also be rejected on the content path, and vice versa.
 *
 * <p>Returns a boolean so each caller throws its OWN domain error code
 * ({@code STORAGE_PRESIGN_FORBIDDEN} vs {@code CONTENT_OBJECT_KEY_INVALID}).
 */
public final class ObjectKeyPolicy {

    private static final int MAX_OBJECT_KEY_LENGTH = 512;

    private ObjectKeyPolicy() {}

    /**
     * {@code true} iff {@code objectKey} is a safe, owner-owned key of the form
     * {@code users/{ownerId}/...} with no traversal and no absolute prefix.
     *
     * <p>The trailing {@code "/"} on the {@code users/{ownerId}/} prefix prevents prefix confusion:
     * {@code ownerId=1} does NOT match {@code "users/12/..."}.
     */
    public static boolean isOwnedObjectKey(String objectKey, Long ownerId) {
        if (objectKey == null || objectKey.isBlank()) return false;
        if (objectKey.startsWith("/")) return false;            // no absolute keys
        if (objectKey.contains("../")) return false;            // no path traversal
        if (objectKey.length() > MAX_OBJECT_KEY_LENGTH) return false;
        return objectKey.startsWith("users/" + ownerId + "/");
    }
}
