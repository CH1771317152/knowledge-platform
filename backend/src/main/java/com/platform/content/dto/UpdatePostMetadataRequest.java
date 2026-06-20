package com.platform.content.dto;

import com.platform.content.domain.PostVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Stage-4 request: set the post's editable metadata (title / summary / visibility / cover) plus its
 * file references and tags. Requires the body to already be confirmed.
 */
public record UpdatePostMetadataRequest(
        @NotBlank String title,
        String summary,
        @NotNull PostVisibility visibility,
        String coverObjectKey,
        List<PostFileRequest> files,
        List<String> tags
) {}
