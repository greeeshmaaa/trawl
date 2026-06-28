package com.crawler.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public class LocalPageStore implements PageStore {
    private final Path outputDir;

    public LocalPageStore(String dir) throws Exception {
        this.outputDir = Path.of(dir);
        Files.createDirectories(outputDir);
    }

    @Override
    public void save(String url, String content) throws Exception {
        String fileName = hash(url) + ".html";
        Files.writeString(outputDir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    private String hash(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }
}
