package com.platform.content.dto;

import java.util.List;

/**
 * Full detail of a readable post: the {@link PostSummaryResponse} summary plus the rendered body
 * markdown (read live from object storage), the body object key / sha, and attached files.
 *
 * @param summary       the post summary
 * @param body          the markdown body text read from object storage, or null if no body has been
 *                      uploaded/confirmed yet
 * @param bodyObjectKey the confirmed body object key, or null if not confirmed
 * @param bodySha256    the confirmed body sha256 hex, or null if not confirmed
 * @param files         attached files (cover / inline / attachments)
 */
public record PostDetailResponse(
        PostSummaryResponse summary,
        String body,
        String bodyObjectKey,
        String bodySha256,
        List<PostFileResponse> files
) {}
