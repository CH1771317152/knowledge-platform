package com.platform.content.dto;

/**
 * Request body for creating a content-post draft. {@code clientRequestId} is the caller-supplied
 * idempotency key: a non-blank value makes {@code createDraft} return the same post on retries.
 */
public record CreateDraftRequest(String clientRequestId) {}
