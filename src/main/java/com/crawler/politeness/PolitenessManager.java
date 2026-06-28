package com.crawler.politeness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PolitenessManager {
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Long> nextAllowed = new ConcurrentHashMap<>();

    public void waitForTurn(String host, long delayMs) throws InterruptedException {
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
