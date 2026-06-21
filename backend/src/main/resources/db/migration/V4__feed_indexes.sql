-- V4__feed_indexes.sql — composite indexes for keyset feed pagination
ALTER TABLE content_post ADD KEY idx_content_post_pub_feed    (status, visibility, published_at DESC, id DESC);
ALTER TABLE content_post ADD KEY idx_content_post_author_feed (author_id, created_at DESC, id DESC);
