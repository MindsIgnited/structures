package org.kinotic.structures.internal.utils;

import org.kinotic.continuum.core.api.crud.CursorPage;
import org.kinotic.continuum.core.api.crud.Page;
import org.kinotic.continuum.core.api.crud.Pageable;
import org.kinotic.continuum.core.api.crud.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Reads every result of a search, a page at a time.
 */
public final class PagingUtils {

    private PagingUtils() {
    }

    /**
     * Follows cursors sorted by the given field until the results run out. Unlike offset paging, cursor paging has no
     * 10,000 result limit, and the sort keeps page boundaries stable while documents are written.
     *
     * @param findPage  runs the search for one page; it must pass the {@link Pageable} on and return a {@link CursorPage}
     * @param sortField a field with a unique value per document, such as a keyword id
     * @param pageSize  the number of results per page
     * @return every result, in sort order
     */
    public static <T> CompletableFuture<List<T>> findAll(Function<Pageable, CompletableFuture<Page<T>>> findPage,
                                                         String sortField,
                                                         int pageSize) {
        return findFrom(findPage, Sort.by(sortField), pageSize, null, new ArrayList<>());
    }

    private static <T> CompletableFuture<List<T>> findFrom(Function<Pageable, CompletableFuture<Page<T>>> findPage,
                                                           Sort sort,
                                                           int pageSize,
                                                           String cursor,
                                                           List<T> found) {
        return findPage.apply(Pageable.create(cursor, pageSize, sort))
                       .thenCompose(page -> {
                           if (!(page instanceof CursorPage<T> cursorPage)) {
                               return CompletableFuture.failedFuture(
                                       new IllegalStateException("Expected a CursorPage but got " + page.getClass().getName()));
                           }
                           List<T> content = cursorPage.getContent() != null ? cursorPage.getContent() : List.of();
                           found.addAll(content);
                           if (content.size() < pageSize || cursorPage.getCursor() == null) {
                               return CompletableFuture.completedFuture(found);
                           }
                           return findFrom(findPage, sort, pageSize, cursorPage.getCursor(), found);
                       });
    }
}
