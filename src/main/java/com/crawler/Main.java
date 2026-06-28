package com.crawler;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        CrawlConfig config = new CrawlConfig(
            List.of("https://books.toscrape.com/"), // seed(s)
            50,    // maxPages
            20,    // concurrency (virtual threads)
            500    // perDomainDelayMs (be polite)
        );
        new Crawler(config).run();
    }
}
