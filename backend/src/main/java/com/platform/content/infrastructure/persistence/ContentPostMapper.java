package com.platform.content.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentPostMapper {

    @Insert("""
            INSERT INTO content_post (id, author_id, client_request_id, title, summary,
                cover_object_key, status, visibility, publish_stage, published_at)
            VALUES (#{id}, #{authorId}, #{clientRequestId}, #{title}, #{summary},
                #{coverObjectKey}, #{status}, #{visibility}, #{publishStage}, #{publishedAt})
            """)
    void insertPost(ContentPostRow row);

    @Insert("""
            INSERT INTO content_post_body (post_id, body_format, body_bucket, body_object_key,
                body_etag, body_sha256, body_size_bytes, body_version, upload_url_expires_at,
                confirmed_at)
            VALUES (#{postId}, #{bodyFormat}, #{bodyBucket}, #{bodyObjectKey},
                #{bodyEtag}, #{bodySha256}, #{bodySizeBytes}, #{bodyVersion},
                #{uploadUrlExpiresAt}, #{confirmedAt})
            """)
    void insertBody(ContentPostBodyRow row);

    @Select("""
            SELECT id, author_id, client_request_id, title, summary, cover_object_key,
                status, visibility, publish_stage, published_at, created_at, updated_at
            FROM content_post
            WHERE id = #{postId}
            """)
    @Results(id = "contentPostResult", value = {
            @Result(column = "author_id", property = "authorId"),
            @Result(column = "client_request_id", property = "clientRequestId"),
            @Result(column = "cover_object_key", property = "coverObjectKey"),
            @Result(column = "publish_stage", property = "publishStage"),
            @Result(column = "published_at", property = "publishedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<ContentPostRow> findPostById(@Param("postId") Long postId);

    @Select("""
            SELECT id, author_id, client_request_id, title, summary, cover_object_key,
                status, visibility, publish_stage, published_at, created_at, updated_at
            FROM content_post
            WHERE author_id = #{authorId} AND client_request_id = #{clientRequestId}
            """)
    @ResultMap("contentPostResult")
    Optional<ContentPostRow> findPostByAuthorAndClientRequestId(@Param("authorId") Long authorId,
                                                                @Param("clientRequestId") String clientRequestId);

    @Select("""
            SELECT post_id, body_format, body_bucket, body_object_key, body_etag, body_sha256,
                body_size_bytes, body_version, upload_url_expires_at, confirmed_at,
                created_at, updated_at
            FROM content_post_body
            WHERE post_id = #{postId}
            """)
    @Results(id = "contentPostBodyResult", value = {
            @Result(column = "post_id", property = "postId"),
            @Result(column = "body_format", property = "bodyFormat"),
            @Result(column = "body_bucket", property = "bodyBucket"),
            @Result(column = "body_object_key", property = "bodyObjectKey"),
            @Result(column = "body_etag", property = "bodyEtag"),
            @Result(column = "body_sha256", property = "bodySha256"),
            @Result(column = "body_size_bytes", property = "bodySizeBytes"),
            @Result(column = "body_version", property = "bodyVersion"),
            @Result(column = "upload_url_expires_at", property = "uploadUrlExpiresAt"),
            @Result(column = "confirmed_at", property = "confirmedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<ContentPostBodyRow> findBodyByPostId(@Param("postId") Long postId);

    @Select("""
            SELECT post_id, object_key, usage_type, content_type, size_bytes, sort_order, created_at
            FROM content_post_file
            WHERE post_id = #{postId}
            ORDER BY sort_order ASC
            """)
    @Results(id = "contentPostFileResult", value = {
            @Result(column = "post_id", property = "postId"),
            @Result(column = "object_key", property = "objectKey"),
            @Result(column = "usage_type", property = "usageType"),
            @Result(column = "content_type", property = "contentType"),
            @Result(column = "size_bytes", property = "sizeBytes"),
            @Result(column = "sort_order", property = "sortOrder"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ContentPostFileRow> findFilesByPostId(@Param("postId") Long postId);

    @Select("""
            SELECT t.id, t.name, t.created_at
            FROM content_tag t
            INNER JOIN content_post_tag pt ON pt.tag_id = t.id
            WHERE pt.post_id = #{postId}
            """)
    @Results(id = "contentTagResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "name", property = "name"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ContentTagRow> findTagsByPostId(@Param("postId") Long postId);

    @Update("""
            UPDATE content_post_body
            SET body_bucket = #{bucket},
                body_object_key = #{objectKey},
                upload_url_expires_at = #{expiresAt}
            WHERE post_id = #{postId}
            """)
    int updateBodyUploadUrl(@Param("postId") Long postId,
                            @Param("bucket") String bucket,
                            @Param("objectKey") String objectKey,
                            @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * Re-issues the body for an edit on an ALREADY-CONFIRMED body: bumps {@code body_version},
     * rewrites the bucket/object_key/upload expiry, and clears the confirmation fields
     * (etag/sha256/size/confirmed_at) so a fresh upload+confirm can be re-walked. The caller derives
     * the new objectKey from {@code oldVersion + 1} and presigns that key before calling this.
     */
    @Update("""
            UPDATE content_post_body
            SET body_version = body_version + 1,
                body_bucket = #{bucket},
                body_object_key = #{objectKey},
                body_etag = NULL,
                body_sha256 = NULL,
                body_size_bytes = NULL,
                confirmed_at = NULL,
                upload_url_expires_at = #{expiresAt}
            WHERE post_id = #{postId}
            """)
    int reissueBodyForEdit(@Param("postId") Long postId,
                           @Param("bucket") String bucket,
                           @Param("objectKey") String objectKey,
                           @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE content_post_body
            SET body_object_key = #{objectKey},
                body_etag = #{etag},
                body_sha256 = #{sha256},
                body_size_bytes = #{sizeBytes},
                confirmed_at = #{confirmedAt}
            WHERE post_id = #{postId}
            """)
    int confirmBody(@Param("postId") Long postId,
                    @Param("objectKey") String objectKey,
                    @Param("etag") String etag,
                    @Param("sha256") String sha256,
                    @Param("sizeBytes") long sizeBytes,
                    @Param("confirmedAt") LocalDateTime confirmedAt);

    @Update("""
            UPDATE content_post
            SET title = #{title},
                summary = #{summary},
                visibility = #{visibility},
                cover_object_key = #{coverObjectKey}
            WHERE id = #{postId}
            """)
    int updateMetadata(@Param("postId") Long postId,
                       @Param("title") String title,
                       @Param("summary") String summary,
                       @Param("visibility") String visibility,
                       @Param("coverObjectKey") String coverObjectKey);

    @Delete("DELETE FROM content_post_file WHERE post_id = #{postId}")
    int deleteFiles(@Param("postId") Long postId);

    @Insert("""
            INSERT INTO content_post_file (post_id, object_key, usage_type, content_type, size_bytes, sort_order)
            VALUES (#{postId}, #{objectKey}, #{usageType}, #{contentType}, #{sizeBytes}, #{sortOrder})
            """)
    void insertFile(ContentPostFileRow row);

    @Delete("DELETE FROM content_post_tag WHERE post_id = #{postId}")
    int deletePostTags(@Param("postId") Long postId);

    @Select("SELECT id FROM content_tag WHERE name = #{name}")
    Long findTagIdByName(@Param("name") String name);

    @Insert("INSERT INTO content_tag (name) VALUES (#{name})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTag(ContentTagRow row);

    @Insert("INSERT INTO content_post_tag (post_id, tag_id) VALUES (#{postId}, #{tagId})")
    void insertPostTag(@Param("postId") Long postId, @Param("tagId") Long tagId);

    @Update("UPDATE content_post SET publish_stage = #{publishStage} WHERE id = #{postId}")
    int updatePostStage(@Param("postId") Long postId, @Param("publishStage") String publishStage);

    @Update("""
            UPDATE content_post
            SET status = #{status},
                publish_stage = #{publishStage},
                published_at = #{publishedAt}
            WHERE id = #{postId}
            """)
    int updateStatusAndStage(@Param("postId") Long postId,
                             @Param("status") String status,
                             @Param("publishStage") String publishStage,
                             @Param("publishedAt") LocalDateTime publishedAt);

    @Update("UPDATE content_post SET status = 'DELETED' WHERE id = #{postId}")
    int softDelete(@Param("postId") Long postId);

    @Select("""
            SELECT id, author_id, client_request_id, title, summary, cover_object_key,
                status, visibility, publish_stage, published_at, created_at, updated_at
            FROM content_post
            WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC'
            ORDER BY published_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @ResultMap("contentPostResult")
    List<ContentPostRow> findPublicPublished(@Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            SELECT id, author_id, client_request_id, title, summary, cover_object_key,
                status, visibility, publish_stage, published_at, created_at, updated_at
            FROM content_post
            WHERE author_id = #{authorId} AND status <> 'DELETED'
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @ResultMap("contentPostResult")
    List<ContentPostRow> findByAuthor(@Param("authorId") Long authorId,
                                      @Param("limit") int limit,
                                      @Param("offset") long offset);
}
