package com.crawler.frontier;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryFrontier implements Frontier {
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger pending = new AtomicInteger(0);

    @Override
    public void add(String url) {
        pending.incrementAndGet();
        queue.add(url);
    }

    @Override
    public Task next() throws InterruptedException {
        String url = queue.poll(1, TimeUnit.SECONDS);
        return url == null ? null : new Task(url, null);
    }

    @Override
    public void complete(Task task) {
        pending.decrementAndGet();
    }

    @Override
    public boolean isExhausted() {
        return pending.get() == 0;
    }
}
