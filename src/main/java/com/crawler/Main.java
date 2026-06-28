package com.crawler;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        CrawlConfig config = new CrawlConfig(
            List.of("https://books.toscrape.com/"),
            50,
            20,
            500,
            3,
            true
        );
        new Crawler(config).run();
    }
}
