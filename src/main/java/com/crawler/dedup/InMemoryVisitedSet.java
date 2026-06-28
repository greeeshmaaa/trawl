package com.crawler.dedup;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryVisitedSet implements VisitedSet {
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markIfNew(String url) {
        return seen.add(url);
    }
}
