package com.crawler.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class Fetcher {
    public static final String PRODUCT_TOKEN = "TrawlBot";
    public static final String USER_AGENT =
        PRODUCT_TOKEN + "/1.0 (+https://github.com/greeeshmaaa/trawl)";

    private final HttpClient client;
    private final int maxRetries;

    public Fetcher(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public FetchResult fetch(String url) throws IOException, InterruptedException {
        long backoffMs = 500;
        IOException lastError = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = client.send(
                    buildRequest(url), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if ((status >= 500 || status == 429) && attempt < maxRetries) {
                    sleepWithBackoff(backoffMs);
                    backoffMs *= 2;
                    continue;
                }

                String contentType = response.headers()
                    .firstValue("Content-Type").orElse("");
                return new FetchResult(status, response.body(), contentType);

            } catch (IOException e) {
                lastError = e;
                if (attempt < maxRetries) {
                    sleepWithBackoff(backoffMs);
                    backoffMs *= 2;
                }
            }
        }
        if (lastError != null) throw lastError;
        throw new IOException("fetch failed after " + maxRetries + " attempts: " + url);
    }

    private HttpRequest buildRequest(String url) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    }

    private void sleepWithBackoff(long backoffMs) throws InterruptedException {
        long jitter = ThreadLocalRandom.current().nextLong(100);
        Thread.sleep(backoffMs + jitter);
    }
}
