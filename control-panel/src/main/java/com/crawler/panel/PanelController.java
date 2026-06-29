package com.crawler.panel;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class PanelController {
    private final CrawlService service;

    public PanelController(CrawlService service) {
        this.service = service;
    }

    public record StartRequest(String seed, Integer maxPages, Integer workers) {}

    @PostMapping("/crawls")
    public Map<String, Object> start(@RequestBody StartRequest req) {
        String seed = (req.seed() == null || req.seed().isBlank())
            ? "https://books.toscrape.com/" : req.seed().trim();
        int maxPages = req.maxPages() == null ? 50 : Math.max(1, req.maxPages());
        int workers  = req.workers() == null ? 2 : Math.min(5, Math.max(1, req.workers()));
        String runId = service.startCrawl(seed, maxPages, workers);
        return Map.of("runId", runId, "seed", seed, "maxPages", maxPages, "workers", workers);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }
}
