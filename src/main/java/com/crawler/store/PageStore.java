package com.crawler.store;

public interface PageStore {
    void save(String url, String content) throws Exception;
}
