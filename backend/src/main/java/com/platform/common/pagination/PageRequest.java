package com.platform.common.pagination;

public record PageRequest(int page, int size) {

    public PageRequest {
        if (page < 1) {
            throw new IllegalArgumentException("page must be greater than zero");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }
}
