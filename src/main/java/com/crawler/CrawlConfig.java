package com.crawler;

import java.util.List;

public record CrawlConfig(
    List<String> seeds,
    int maxPages,
    int concurrency,
    long perDomainDelayMs,
    int maxRetries,
    boolean respectRobots
) {}
