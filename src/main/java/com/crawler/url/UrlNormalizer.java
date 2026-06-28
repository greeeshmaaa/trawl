package com.crawler.url;

import java.net.URI;
import java.util.Optional;

public final class UrlNormalizer {
    private UrlNormalizer() {}

    /** Canonicalize a URL: lowercase scheme/host, drop fragment, strip default ports. */
    public static Optional<String> normalize(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);

            String scheme = uri.getScheme();
            if (scheme == null) return Optional.empty();
            scheme = scheme.toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Optional.empty(); // skip mailto:, javascript:, etc.
            }

            String host = uri.getHost();
            if (host == null) return Optional.empty();
            host = host.toLowerCase();

            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) ||
                (scheme.equals("https") && port == 443)) {
                port = -1; // remove redundant default port
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";

            // Rebuild without the fragment (the #section part)
            URI normalized = new URI(scheme, null, host, port, path, uri.getQuery(), null);
            return Optional.of(normalized.toString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Extract the lowercase host, used for per-domain politeness and scoping. */
    public static Optional<String> hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? Optional.empty() : Optional.of(host.toLowerCase());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
