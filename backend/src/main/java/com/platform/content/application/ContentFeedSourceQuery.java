package com.platform.content.application;

import com.platform.cache.feed.domain.Cursor;
import com.platform.cache.feed.domain.FeedPage;
import com.platform.cache.feed.domain.FeedSourceQuery;
import com.platform.content.infrastructure.persistence.ContentPostMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Content-side adapter for {@link FeedSourceQuery}. Backfills feed pages from MySQL using
 * keyset (cursor) pagination on the V4 composite indexes.
 *
 * <p>Each query fetches {@code size + 1} rows: the extra row (if present) proves {@code hasMore}
 * without a separate count query. The returned {@link FeedPage#getNextCursor() nextCursor} is
 * always built from the LAST id actually returned on the page (never the extra row), so callers
 * can hand it straight back to the corresponding {@code ...After} method.
 */
@Repository
@Profile("!test")
public class ContentFeedSourceQuery implements FeedSourceQuery {

    private final ContentPostMapper mapper;

    public ContentFeedSourceQuery(ContentPostMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public FeedPage findPublicFeedHead(int size) {
        List<Map<String, Object>> rows = mapper.findPublicFeedHead(size + 1);
        return toPage(rows, size, "published_at");
    }

    @Override
    public FeedPage findPublicFeedAfter(Cursor cursor, int size) {
        if (cursor == null) {
            return findPublicFeedHead(size);
        }
        List<Map<String, Object>> rows =
                mapper.findPublicFeedAfter(cursor.timestamp(), cursor.id(), size + 1);
        return toPage(rows, size, "published_at");
    }

    @Override
    public FeedPage findUserFeedHead(Long userId, int size) {
        List<Map<String, Object>> rows = mapper.findUserFeedHead(userId, size + 1);
        return toPage(rows, size, "created_at");
    }

    @Override
    public FeedPage findUserFeedAfter(Long userId, Cursor cursor, int size) {
        if (cursor == null) {
            return findUserFeedHead(userId, size);
        }
        List<Map<String, Object>> rows =
                mapper.findUserFeedAfter(userId, cursor.timestamp(), cursor.id(), size + 1);
        return toPage(rows, size, "created_at");
    }

    private FeedPage toPage(List<Map<String, Object>> rows, int size, String timestampColumn) {
        boolean hasMore = rows.size() > size;
        // Trim the probe row before exposing ids / building the cursor.
        List<Map<String, Object>> page = hasMore ? rows.subList(0, size) : rows;

        List<Long> ids = new ArrayList<>(page.size());
        Cursor nextCursor = null;
        for (int i = 0; i < page.size(); i++) {
            Map<String, Object> row = page.get(i);
            Long id = ((Number) row.get("id")).longValue();
            ids.add(id);
            if (i == page.size() - 1) {
                nextCursor = new Cursor(extractTimestamp(row, timestampColumn), id);
            }
        }
        return new FeedPage(ids, hasMore, nextCursor);
    }

    /**
     * MyBatis maps the {@code DATETIME} column to a {@link LocalDateTime} but the exact key in the
     * result {@code Map} depends on the JDBC driver's case handling, so try both the SQL column
     * name and its lowercase form before giving up.
     */
    private static LocalDateTime extractTimestamp(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toLowerCase());
        }
        if (value == null) {
            throw new IllegalStateException("Feed row missing timestamp column: " + column);
        }
        return (LocalDateTime) value;
    }
}
