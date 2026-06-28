package com.crawler;

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
        // In-memory (single process):
        // Frontier frontier = new InMemoryFrontier();
        // Amazon SQS (shared, durable): queueName, region, waitSeconds, visibilityTimeout, maxEmptyPolls
        Frontier frontier = new SqsFrontier("trawl-frontier", "us-east-1", 20, 60, 2);

        // Store: where crawled pages go.
        // PageStore store = new LocalPageStore("crawl-output");
        PageStore store = new S3PageStore("trawl-pages-greeshma-2026", "us-east-1", "pages");

        try (frontier; store) {
            new Crawler(config, frontier, store).run();
        }
    }
}
