package com.crawler.frontier;

public interface Frontier extends AutoCloseable {
    void add(String url);
    Task next() throws InterruptedException;
    void complete(Task task);
    boolean isExhausted();

    @Override
    default void close() throws Exception { }
}
