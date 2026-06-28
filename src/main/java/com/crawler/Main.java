package com.crawler;

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

        // Local disk (Phase 1):
        // PageStore store = new LocalPageStore("crawl-output");

        // Amazon S3 (Phase 2):
        PageStore store = new S3PageStore("trawl-pages-greeshma-2026", "us-east-1", "pages");

        try (store) {
            new Crawler(config, store).run();
        }
    }
}
