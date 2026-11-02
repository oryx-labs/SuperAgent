package com.superagent.api;

import java.io.Serializable;
import java.util.List;

/**
 * Payload of a paged list endpoint. Carries the page window alongside the items
 * so clients never have to echo back their own query parameters to render a pager.
 *
 * @param items items on this page
 * @param page  1-based page number
 * @param size  requested page size
 * @param total total number of matching items across all pages
 */
public record PageResult<T>(List<T> items, int page, int size, long total) implements Serializable {

    public static <T> PageResult<T> of(List<T> items, PageQuery query, long total) {
        PageQuery normalized = query.normalized();
        return new PageResult<>(items, normalized.page(), normalized.size(), total);
    }
}
