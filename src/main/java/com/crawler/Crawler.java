package com.crawler;

import com.crawler.fetch.Fetcher;
import com.crawler.fetch.FetchResult;
import com.crawler.parse.LinkExtractor;
import com.crawler.politeness.PolitenessManager;
import com.crawler.store.LocalPageStore;
import com.crawler.store.PageStore;
import com.crawler.url.UrlNormalizer;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Crawler {
    private final CrawlConfig config;
    private final Frontier frontier = new Frontier();
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pagesCrawled = new AtomicInteger(0);

    private final Fetcher fetcher = new Fetcher();
    private final LinkExtractor linkExtractor = new LinkExtractor();
    private final PolitenessManager politeness;
    private final PageStore pageStore;

    public Crawler(CrawlConfig config) {
        this.config = config;
        this.politeness = new PolitenessManager(config.perDomainDelayMs());
        try {
            this.pageStore = new LocalPageStore("crawl-output");
        } catch (Exception e) {
            throw new RuntimeException("Failed to init page store", e);
        }
    }

    public void run() {
        long start = System.currentTimeMillis();

        // Seed the frontier
        for (String seed : config.seeds()) {
            UrlNormalizer.normalize(seed).ifPresent(url -> {
                if (visited.add(url)) frontier.add(url);
            });
        }

        // One virtual thread per worker. try-with-resources waits for all to finish.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < config.concurrency(); i++) {
                pool.submit(this::worker);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("%nDone. Crawled %d pages in %.1fs%n",
            pagesCrawled.get(), elapsed / 1000.0);
    }

    private void worker() {
        while (true) {
            String url;
            try {
                url = frontier.next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (url == null) {
                if (frontier.isDrained()) return; // queue empty AND nothing in flight
                continue;
            }

            try {
                process(url);
            } catch (Exception e) {
                System.out.println("  ! error on " + url + " : " + e.getMessage());
            } finally {
                frontier.complete();
            }
        }
    }

    private void process(String url) throws Exception {
        if (pagesCrawled.get() >= config.maxPages()) return; // limit hit: drain fast

        Optional<String> hostOpt = UrlNormalizer.hostOf(url);
        if (hostOpt.isEmpty()) return;
        String host = hostOpt.get();

        politeness.waitForTurn(host);

        FetchResult result = fetcher.fetch(url);
        if (!result.isSuccess() || !result.isHtml()) return;

        int count = pagesCrawled.incrementAndGet();
        if (count > config.maxPages()) return; // another worker raced past the limit

        pageStore.save(url, result.body());
        System.out.printf("[%d] %s%n", count, url);

        // Enqueue new in-scope links
        for (String link : linkExtractor.extractLinks(result.body(), url)) {
            Optional<String> normalized = UrlNormalizer.normalize(link);
            if (normalized.isEmpty()) continue;
            String next = normalized.get();

            Optional<String> nextHost = UrlNormalizer.hostOf(next);
            if (nextHost.isEmpty() || !nextHost.get().equals(host)) continue; // stay on-site

            if (visited.add(next)) {   // add() returns false if already seen -> dedup
                frontier.add(next);
            }
        }
    }
}
