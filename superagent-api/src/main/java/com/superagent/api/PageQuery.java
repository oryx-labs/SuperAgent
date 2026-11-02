package com.superagent.api;

/**
 * Common query parameters for paged list endpoints.
 *
 * <p>{@link #normalized()} clamps client input to a safe window, so controllers
 * never have to validate page bounds themselves.
 *
 * @param page 1-based page number
 * @param size page size
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    public PageQuery normalized() {
        int p = page < 1 ? DEFAULT_PAGE : page;
        int s = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageQuery(p, s);
    }

    public int offset() {
        PageQuery n = normalized();
        return (n.page - 1) * n.size;
    }
}
