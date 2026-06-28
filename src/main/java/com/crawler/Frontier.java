package com.crawler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Frontier {
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger pending = new AtomicInteger(0);

    /** Add a URL to crawl. */
    public void add(String url) {
        pending.incrementAndGet();
        queue.add(url);
    }

    /** Take the next URL, waiting up to 1s. Returns null if none arrived. */
    public String next() throws InterruptedException {
        return queue.poll(1, TimeUnit.SECONDS);
    }

    /** Mark a URL fully processed (fetched + children enqueued). */
    public void complete() {
        pending.decrementAndGet();
    }

    /** True when nothing is queued AND nothing is mid-processing. */
    public boolean isDrained() {
        return pending.get() == 0;
    }
}
