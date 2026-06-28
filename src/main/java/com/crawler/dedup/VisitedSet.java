package com.crawler.dedup;

public interface VisitedSet extends AutoCloseable {
    boolean markIfNew(String url);

    @Override
    default void close() throws Exception { }
}
