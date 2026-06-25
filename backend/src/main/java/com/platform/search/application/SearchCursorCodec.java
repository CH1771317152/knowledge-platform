package com.platform.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.search.domain.SearchCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encodes and decodes the search_after cursor for search pagination.
 *
 * <p>The cursor is a Base64url JSON payload carrying the query hash (so a cursor from one query
 * cannot be replayed against another), a rank-now anchor for score normalization, an expiry, and the
 * last page's sort values. A trailing HMAC-SHA256 signature (computed over the Base64 payload)
 * prevents client tampering. Decoding re-checks the signature, the query hash, and the TTL.
 *
 * <p>The codec is constructed with the cursor secret and TTL from {@code platform.search.cursor}; it
 * is registered as a Spring bean by {@code ElasticsearchConfig} so consumers share one instance.
 */
public class SearchCursorCodec {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final String secret;
    private final long ttlSeconds;

    public SearchCursorCodec(String secret, long ttlSeconds) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    public SearchCursor newCursor(String queryHash, Instant rankNow, List<Object> sortValues) {
        return new SearchCursor(queryHash, rankNow, rankNow.plusSeconds(ttlSeconds), sortValues);
    }

    public String encode(SearchCursor cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            String payload64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            String sig = sign(payload64);
            return payload64 + "." + sig;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to encode cursor", e);
        }
    }

    public SearchCursor decode(String token, String expectedQueryHash) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2 || !sign(parts[0]).equals(parts[1])) {
                throw new IllegalArgumentException("invalid cursor signature");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            SearchCursor cursor = objectMapper.readValue(payload, SearchCursor.class);
            if (!cursor.queryHash().equals(expectedQueryHash)) {
                throw new IllegalArgumentException("cursor query mismatch");
            }
            if (Instant.now().isAfter(cursor.expiresAt())) {
                throw new IllegalArgumentException("cursor expired");
            }
            return cursor;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }

    private String sign(String payload64) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload64.getBytes(StandardCharsets.UTF_8)));
    }
}
