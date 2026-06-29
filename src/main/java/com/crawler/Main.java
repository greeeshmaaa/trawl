package com.crawler;

import com.crawler.dedup.DynamoVisitedSet;
import com.crawler.dedup.InMemoryVisitedSet;
import com.crawler.dedup.VisitedSet;
import com.crawler.frontier.Frontier;
import com.crawler.frontier.InMemoryFrontier;
import com.crawler.frontier.SqsFrontier;
import com.crawler.store.LocalPageStore;
import com.crawler.store.PageStore;
import com.crawler.store.S3PageStore;

import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        boolean local = boolEnv("LOCAL", false);

        String seed         = env("SEED_URL", "https://books.toscrape.com/");
        int maxPages        = intEnv("MAX_PAGES", 50);
        int concurrency     = intEnv("CONCURRENCY", 20);
        long perDomainDelay = longEnv("PER_DOMAIN_DELAY_MS", 500);
        int maxRetries      = intEnv("MAX_RETRIES", 3);
        boolean robots      = boolEnv("RESPECT_ROBOTS", true);

        String region = env("REGION", "us-east-1");
        String bucket = env("BUCKET", "trawl-pages-greeshma-2026");
        String queue  = env("QUEUE_NAME", "trawl-frontier");
        String table  = env("TABLE_NAME", "trawl-visited");
        String runId  = env("RUN_ID", "default");

        CrawlConfig config = new CrawlConfig(
            List.of(seed), maxPages, concurrency, perDomainDelay, maxRetries, robots);

        System.out.printf("Starting crawl run=%s seed=%s maxPages=%d local=%b%n",
            runId, seed, maxPages, local);

        Frontier frontier = local
            ? new InMemoryFrontier()
            : new SqsFrontier(queue, region, 20, 60, 2);

        PageStore store = local
            ? new LocalPageStore("crawl-output")
            : new S3PageStore(bucket, region, "runs/" + runId + "/pages");

        VisitedSet visited = local
            ? new InMemoryVisitedSet()
            : new DynamoVisitedSet(table, region, runId + "#");

        try (frontier; store; visited) {
            new Crawler(config, frontier, store, visited).run();
        }
    }

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }
    private static int intEnv(String key, int dflt) {
        try { return Integer.parseInt(env(key, Integer.toString(dflt))); }
        catch (NumberFormatException e) { return dflt; }
    }
    private static long longEnv(String key, long dflt) {
        try { return Long.parseLong(env(key, Long.toString(dflt))); }
        catch (NumberFormatException e) { return dflt; }
    }
    private static boolean boolEnv(String key, boolean dflt) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return dflt;
        v = v.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }
}
