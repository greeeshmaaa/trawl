package com.crawler.fetch;

public record FetchResult(int statusCode, String body, String contentType) {
    public boolean isHtml() {
        return contentType != null && contentType.contains("text/html");
    }
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
