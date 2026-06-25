package com.platform.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.cache.feed.domain.FeedPage;
import com.platform.cache.feed.domain.FeedSourceQuery;
import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.PostBodyFormat;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.infrastructure.id.ContentIdGenerator;
import com.platform.content.repository.ContentPostRepository;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration coverage for the content-side {@link FeedSourceQuery} adapter and the V4 keyset
 * indexes. Verifies keyset ordering, the {@code hasMore} probe row, the next-cursor contract,
 * and the public/user status filters.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class ContentFeedSourceQueryIntegrationTest {

    private static final String USERNAME = "it_feed_user";
    private static final String EMAIL = "it_feed_user@example.com";
    private static final String PHONE = "13700000430";

    @Autowired
    private FeedSourceQuery feedSourceQuery;

    @Autowired
    private ContentPostRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentIdGenerator idGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long authorId;

    @BeforeEach
    void createUser() {
        jdbcTemplate.update("""
                DELETE FROM content_post_tag
                WHERE post_id IN (SELECT id FROM content_post
                    WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?))
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM content_post_file
                WHERE post_id IN (SELECT id FROM content_post
                    WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?))
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM content_post_body
                WHERE post_id IN (SELECT id FROM content_post
                    WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?))
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM content_post
                WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?)
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM user_profile
                WHERE user_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?)
                """, USERNAME, EMAIL);
        jdbcTemplate.update("DELETE FROM user_account WHERE username = ? OR email = ?", USERNAME, EMAIL);

        UserAccount account = new UserAccount(null, USERNAME, EMAIL, PHONE, "hash",
                UserStatus.ACTIVE, UserRole.USER, false, false, null, null, null);
        UserProfile profile = new UserProfile(null, "Feed User", null, "bio", "Shanghai",
                "https://example.com", LocalDate.of(1995, 5, 20), null, null);
        UserAccount saved = userRepository.save(account, profile);
        this.authorId = saved.id();
    }

    @Test
    void publicFeedPaginatesByKeysetAndExcludesNonPublished() {
        Long oldestId = idGenerator.nextId();
        Long middleId = idGenerator.nextId();
        Long newestId = idGenerator.nextId();
        Long draftId = idGenerator.nextId();

        savePublishedPublic(oldestId, "req-oldest", LocalDateTime.now().minusMinutes(30));
        savePublishedPublic(middleId, "req-middle", LocalDateTime.now().minusMinutes(20));
        savePublishedPublic(newestId, "req-newest", LocalDateTime.now().minusMinutes(10));
        // DRAFT — must not appear in the public feed.
        saveDraft(draftId, "req-draft");

        // Page 1: size 2 of 3 published -> newest, middle.
        FeedPage head = feedSourceQuery.findPublicFeedHead(2);
        assertThat(head.ids()).containsExactly(newestId, middleId);
        assertThat(head.hasMore()).isTrue();
        assertThat(head.nextCursor()).isNotNull();
        assertThat(head.nextCursor().id()).isEqualTo(middleId);
        assertThat(head.nextCursor().timestamp()).isNotNull();

        // Page 2: continue after the middle's cursor -> oldest, no more.
        FeedPage tail = feedSourceQuery.findPublicFeedAfter(head.nextCursor(), 2);
        assertThat(tail.ids()).containsExactly(oldestId);
        assertThat(tail.hasMore()).isFalse();
        assertThat(tail.nextCursor()).isNotNull(); // cursor of the last item on this page

        // Draft was never returned across either page.
        assertThat(head.ids()).doesNotContain(draftId);
        assertThat(tail.ids()).doesNotContain(draftId);
    }

    @Test
    void publicFeedHeadWhenEmptyReturnsEmptyPage() {
        FeedPage head = feedSourceQuery.findPublicFeedHead(10);
        assertThat(head.ids()).isEmpty();
        assertThat(head.hasMore()).isFalse();
        assertThat(head.nextCursor()).isNull();
    }

    @Test
    void userFeedIncludesDraftsAndOrdersByCreatedAtDesc() {
        Long firstId = idGenerator.nextId();
        Long secondId = idGenerator.nextId();
        Long thirdId = idGenerator.nextId();
        Long publishedId = idGenerator.nextId();

        saveDraft(firstId, "user-first");
        saveDraft(secondId, "user-second");
        saveDraft(thirdId, "user-third");
        savePublishedPublic(publishedId, "user-published", LocalDateTime.now().minusMinutes(5));

        // createdAt is DB-generated on insert; since firstId..thirdId..publishedId are inserted
        // in that order sequentially, later inserts carry >= created_at.
        FeedPage userFeed = feedSourceQuery.findUserFeedHead(authorId, 10);
        assertThat(userFeed.ids())
                .containsExactlyInAnyOrder(firstId, secondId, thirdId, publishedId);
        // Ordering by created_at DESC: the last-inserted row is first.
        assertThat(userFeed.ids().get(0)).isEqualTo(publishedId);
        // newest-first means strictly non-increasing createdAt; here we just assert the published
        // (most-recent) post leads.
        assertThat(userFeed.hasMore()).isFalse();
    }

    @Test
    void userFeedExcludesSoftDeletedPosts() {
        Long aliveId = idGenerator.nextId();
        Long deadId = idGenerator.nextId();
        saveDraft(aliveId, "user-alive");
        saveDraft(deadId, "user-dead");
        repository.softDelete(deadId);

        FeedPage userFeed = feedSourceQuery.findUserFeedHead(authorId, 10);
        assertThat(userFeed.ids()).contains(aliveId);
        assertThat(userFeed.ids()).doesNotContain(deadId);
    }

    @Test
    void userFeedPaginatesByKeysetAndHasMore() {
        Long a = idGenerator.nextId();
        Long b = idGenerator.nextId();
        Long c = idGenerator.nextId();
        saveDraft(a, "u-a");
        saveDraft(b, "u-b");
        saveDraft(c, "u-c");

        FeedPage head = feedSourceQuery.findUserFeedHead(authorId, 2);
        assertThat(head.ids()).hasSize(2);
        assertThat(head.hasMore()).isTrue();
        assertThat(head.nextCursor()).isNotNull();
        assertThat(head.nextCursor().id()).isEqualTo(head.ids().get(1));

        FeedPage tail = feedSourceQuery.findUserFeedAfter(authorId, head.nextCursor(), 2);
        assertThat(tail.ids()).hasSize(1);
        assertThat(tail.hasMore()).isFalse();
        // No overlap between the two pages.
        assertThat(tail.ids()).doesNotContainAnyElementsOf(head.ids());
    }

    private void saveDraft(Long id, String clientRequestId) {
        ContentPost draft = new ContentPost(id, authorId, clientRequestId, "T-" + clientRequestId, null,
                null, PostStatus.DRAFT, PostVisibility.PRIVATE, PublishStage.DRAFT_CREATED, null, null, null, 0L);
        ContentPostBody body = new ContentPostBody(id, PostBodyFormat.MARKDOWN, null, null, null, null,
                0L, 1, null, null, null, null);
        repository.saveDraft(draft, body);
    }

    private void savePublishedPublic(Long id, String clientRequestId, LocalDateTime publishedAt) {
        saveDraft(id, clientRequestId);
        repository.updateMetadata(id, "Title-" + clientRequestId, null, PostVisibility.PUBLIC, null);
        repository.updateStatusAndStage(id, PostStatus.PUBLISHED, PublishStage.PUBLISHED, publishedAt);
    }

    @Test
    void v4FlywayMigrationApplied() {
        // The V4 migration must have run for the keyset indexes to exist; assert it's recorded.
        Integer v4 = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM flyway_schema_history WHERE version = '4' AND success = 1",
                Integer.class);
        assertThat(v4).as("Flyway V4 feed_indexes migration should be applied").isEqualTo(1);
    }

    @Test
    void v4KeysetIndexesExist() {
        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_post' "
                        + "AND INDEX_NAME IN ('idx_content_post_pub_feed','idx_content_post_author_feed')",
                String.class);
        assertThat(indexNames).containsExactlyInAnyOrder(
                "idx_content_post_pub_feed", "idx_content_post_author_feed");
    }
}
