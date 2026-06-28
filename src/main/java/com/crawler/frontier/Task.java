package com.crawler.frontier;

public final class Task {
    private final String url;
    private final String handle; // SQS receipt handle; null for in-memory

    public Task(String url, String handle) {
        this.url = url;
        this.handle = handle;
    }

    public String url() { return url; }
    public String handle() { return handle; }
}
