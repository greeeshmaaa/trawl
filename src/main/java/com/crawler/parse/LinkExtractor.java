package com.crawler.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

public class LinkExtractor {
    public List<String> extractLinks(String html, String baseUrl) {
        List<String> links = new ArrayList<>();
        Document doc = Jsoup.parse(html, baseUrl); // baseUrl enables relative-link resolution
        Elements anchors = doc.select("a[href]");
        for (Element a : anchors) {
            String absolute = a.absUrl("href"); // resolves to a full URL
            if (!absolute.isBlank()) {
                links.add(absolute);
            }
        }
        return links;
    }
}
