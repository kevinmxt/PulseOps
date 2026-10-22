package dev.pulseops.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class PageQueries {

    private static final int MAX_PAGE_SIZE = 100;

    private PageQueries() {
    }

    public static PageRequest of(int page, int size, Sort sort) {
        if (page < 0) {
            throw new InvalidQueryException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidQueryException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }
}
