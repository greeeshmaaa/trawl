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
        CrawlConfig config = new CrawlConfig(
            List.of("https://books.toscrape.com/"),
            50,
            20,
            500,
            3,
            true
        );

        // Frontier: where the URL queue lives.
        // Frontier frontier = new InMemoryFrontier();
        Frontier frontier = new SqsFrontier("trawl-frontier", "us-east-1", 20, 60, 2);

        // Store: where crawled pages go.
        // PageStore store = new LocalPageStore("crawl-output");
        PageStore store = new S3PageStore("trawl-pages-greeshma-2026", "us-east-1", "pages");

        // Dedup: which URLs have been claimed.
        // VisitedSet visited = new InMemoryVisitedSet();
        VisitedSet visited = new DynamoVisitedSet("trawl-visited", "us-east-1");

        try (frontier; store; visited) {
            new Crawler(config, frontier, store, visited).run();
        }
    }
}
