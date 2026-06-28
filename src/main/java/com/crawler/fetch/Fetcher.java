package com.crawler.fetch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Fetcher {
    private final HttpClient client;

    public Fetcher() {
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public FetchResult fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "MyPortfolioCrawler/1.0 (+learning project)")
            .GET()
            .build();

        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());

        String contentType = response.headers()
            .firstValue("Content-Type").orElse("");

        return new FetchResult(response.statusCode(), response.body(), contentType);
    }
}
