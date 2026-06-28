package com.crawler.politeness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PolitenessManager {
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Long> nextAllowed = new ConcurrentHashMap<>();
    private final long delayMs;

    public PolitenessManager(long delayMs) {
        this.delayMs = delayMs;
    }

    /** Block until this host is allowed to be hit again. */
    public void waitForTurn(String host) throws InterruptedException {
        Object lock = locks.computeIfAbsent(host, h -> new Object());
        synchronized (lock) {
            long now = System.currentTimeMillis();
            Long allowedAt = nextAllowed.get(host);
            if (allowedAt != null && allowedAt > now) {
                Thread.sleep(allowedAt - now);
            }
            nextAllowed.put(host, System.currentTimeMillis() + delayMs);
        }
    }
}
