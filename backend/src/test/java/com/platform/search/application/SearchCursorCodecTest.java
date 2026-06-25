package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.search.domain.SearchCursor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchCursorCodecTest {

    @Test
    void encodesAndDecodesSignedCursor() {
        SearchCursorCodec codec = new SearchCursorCodec("cursor-secret-for-tests", 600);
        // expiresAt must be in the future relative to the decode-time clock check, so use a far-future
        // instant rather than a hardcoded wall-clock value that goes stale the moment the test runs
        // past it. rankNow is just payload data (the score anchor) and is not clock-checked.
        SearchCursor cursor = new SearchCursor(
                "hash-1",
                Instant.parse("2026-06-25T12:00:00Z"),
                Instant.now().plusSeconds(600),
                List.of(1, 9.3d, 4.2d, "2026-06-25T11:00:00Z", 100L));

        String token = codec.encode(cursor);
        SearchCursor decoded = codec.decode(token, "hash-1");

        assertThat(decoded.queryHash()).isEqualTo("hash-1");
        assertThat(decoded.sortValues()).containsExactly(1, 9.3d, 4.2d, "2026-06-25T11:00:00Z", 100);
    }

    @Test
    void rejectsCursorForDifferentQueryHash() {
        SearchCursorCodec codec = new SearchCursorCodec("cursor-secret-for-tests", 600);
        SearchCursor cursor = new SearchCursor(
                "hash-1", Instant.now(), Instant.now().plusSeconds(600), List.of(1, 2, 3));

        String token = codec.encode(cursor);

        assertThatThrownBy(() -> codec.decode(token, "hash-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor query mismatch");
    }

    @Test
    void rejectsTamperedSignature() {
        SearchCursorCodec codec = new SearchCursorCodec("cursor-secret-for-tests", 600);
        String token = codec.encode(new SearchCursor(
                "hash-1", Instant.now(), Instant.now().plusSeconds(600), List.of(1, 2, 3)));

        String tampered = token.substring(0, token.length() - 2) + "AA";

        assertThatThrownBy(() -> codec.decode(tampered, "hash-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid cursor signature");
    }

    @Test
    void rejectsExpiredCursor() {
        SearchCursorCodec codec = new SearchCursorCodec("cursor-secret-for-tests", 600);
        SearchCursor expired = new SearchCursor(
                "hash-1", Instant.now().minusSeconds(1200), Instant.now().minusSeconds(600), List.of(1));

        String token = codec.encode(expired);

        assertThatThrownBy(() -> codec.decode(token, "hash-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor expired");
    }
}
