package com.crawler;

import com.crawler.dedup.VisitedSet;
import com.crawler.fetch.Fetcher;
import com.crawler.fetch.FetchResult;
import com.crawler.frontier.Frontier;
import com.crawler.frontier.Task;
import com.crawler.parse.LinkExtractor;
import com.crawler.politeness.PolitenessManager;
import com.crawler.robots.RobotsManager;
import com.crawler.store.PageStore;
import com.crawler.url.UrlNormalizer;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Crawler {
    private final CrawlConfig config;
    private final Frontier frontier;
    private final VisitedSet visited;
    private final AtomicInteger pagesCrawled = new AtomicInteger(0);

    private final Fetcher fetcher;
    private final LinkExtractor linkExtractor = new LinkExtractor();
    private final PolitenessManager politeness;
    private final RobotsManager robots;
    private final PageStore pageStore;

    public Crawler(CrawlConfig config, Frontier frontier, PageStore pageStore, VisitedSet visited) {
        this.config = config;
        this.frontier = frontier;
        this.pageStore = pageStore;
        this.visited = visited;
        this.fetcher = new Fetcher(config.maxRetries());
        this.politeness = new PolitenessManager();
        this.robots = new RobotsManager(fetcher, Fetcher.PRODUCT_TOKEN);
    }

    public void run() {
        long start = System.currentTimeMillis();

        for (String seed : config.seeds()) {
            UrlNormalizer.normalize(seed).ifPresent(url -> {
                if (visited.markIfNew(url)) frontier.add(url);
            });
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < config.concurrency(); i++) {
                pool.submit(this::worker);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("%nDone. Saved %d pages in %.1fs%n",
            pagesCrawled.get(), elapsed / 1000.0);
    }

    private void worker() {
        while (true) {
            Task task;
            try {
                task = frontier.next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (task == null) {
                if (frontier.isExhausted()) return;
                continue;
            }

            try {
                process(task.url());
            } catch (Exception e) {
                System.out.println("  ! error on " + task.url() + " : " + e.getMessage());
            } finally {
                frontier.complete(task);
            }
        }
    }

    private int claimSlot() {
        while (true) {
            int current = pagesCrawled.get();
            if (current >= config.maxPages()) return -1;
            if (pagesCrawled.compareAndSet(current, current + 1)) return current + 1;
        }
    }

    private void releaseSlot() {
        pagesCrawled.decrementAndGet();
    }

    private void process(String url) throws Exception {
        Optional<String> hostOpt = UrlNormalizer.hostOf(url);
        if (hostOpt.isEmpty()) return;
        String host = hostOpt.get();

        if (config.respectRobots() && !robots.isAllowed(url)) return;

        int slot = claimSlot();
        if (slot < 0) return;

        boolean saved = false;
        try {
            long delay = config.perDomainDelayMs();
            if (config.respectRobots()) {
                delay = Math.max(delay, robots.crawlDelayMs(url));
            }
            politeness.waitForTurn(host, delay);

            FetchResult result = fetcher.fetch(url);
            if (!result.isSuccess() || !result.isHtml()) return;

            pageStore.save(url, result.body());
            saved = true;
            System.out.printf("[%d] %s%n", slot, url);

            for (String link : linkExtractor.extractLinks(result.body(), url)) {
                Optional<String> normalized = UrlNormalizer.normalize(link);
                if (normalized.isEmpty()) continue;
                String next = normalized.get();

                Optional<String> nextHost = UrlNormalizer.hostOf(next);
                if (nextHost.isEmpty() || !nextHost.get().equals(host)) continue;

                if (visited.markIfNew(next)) frontier.add(next);
            }
        } finally {
            if (!saved) releaseSlot();
        }
    }
}
