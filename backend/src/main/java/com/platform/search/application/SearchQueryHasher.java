package com.platform.search.application;

/**
 * Computes the stable query-hash embedded in the search cursor.
 *
 * <p>The hash ties a cursor to the exact query that produced it: a cursor minted for
 * {@code (keyword, tag, contentType, size)} will not decode for any other combination, so a stale
 * bookmarked URL cannot leak results from a different query (the codec rejects the mismatch with
 * {@code "cursor query mismatch"}). The hash is deliberately coarse — it covers the parameters that
 * change the result set shape, not the requester id (the personalization overlay is per-request and
 * is not part of the pagination contract).
 *
 * <p>Package-private: this is an internal helper of the query service, not a public API.
 */
final class SearchQueryHasher {

    private SearchQueryHasher() {}

    /**
     * @param keyword     the search keyword, may be {@code null}/blank (tag-only search).
     * @param tag         the tag filter, may be {@code null}/blank.
     * @param contentType the content-type filter (e.g. {@code ARTICLE}).
     * @param size        the clamped page size.
     * @return a short hex string stable across equal inputs.
     */
    static String hash(String keyword, String tag, String contentType, int size) {
        return Integer.toHexString(
                (String.valueOf(keyword) + "|" + tag + "|" + contentType + "|" + size).hashCode());
    }
}
