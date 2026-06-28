package com.crawler.robots;

import com.crawler.fetch.FetchResult;
import com.crawler.fetch.Fetcher;
import com.crawler.url.UrlNormalizer;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class RobotsManager {
    private final Fetcher fetcher;
    private final String productToken;
    private final Map<String, RobotsRules> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public RobotsManager(Fetcher fetcher, String productToken) {
        this.fetcher = fetcher;
        this.productToken = productToken;
    }

    public boolean isAllowed(String url) {
        return rulesFor(url).allows(pathOf(url));
    }

    public long crawlDelayMs(String url) {
        return rulesFor(url).crawlDelayMs();
    }

    private RobotsRules rulesFor(String url) {
        Optional<String> hostOpt = UrlNormalizer.hostOf(url);
        if (hostOpt.isEmpty()) return RobotsRules.ALLOW_ALL;
        String host = hostOpt.get();

        RobotsRules cached = cache.get(host);
        if (cached != null) return cached;

        Object lock = locks.computeIfAbsent(host, h -> new Object());
        synchronized (lock) {
            cached = cache.get(host);
            if (cached != null) return cached;
            RobotsRules rules = load(url, host);
            cache.put(host, rules);
            return rules;
        }
    }

    private RobotsRules load(String url, String host) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String robotsUrl = scheme + "://" + host + "/robots.txt";
            FetchResult result = fetcher.fetch(robotsUrl);
            if (result.statusCode() == 200) {
                return RobotsParser.parse(result.body(), productToken);
            }
            return RobotsRules.ALLOW_ALL;
        } catch (Exception e) {
            return RobotsRules.ALLOW_ALL;
        }
    }

    private String pathOf(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();
            return path;
        } catch (Exception e) {
            return "/";
        }
    }
}
