package com.crawler.store;

public interface PageStore extends AutoCloseable {
    void save(String url, String content) throws Exception;

    /** Release any underlying resources. Default: nothing to close. */
    @Override
    default void close() throws Exception { }
}
