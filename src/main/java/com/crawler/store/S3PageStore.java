package com.crawler.store;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class S3PageStore implements PageStore {
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3PageStore(String bucket, String region, String prefix) {
        this.bucket = bucket;
        this.prefix = (prefix == null || prefix.isBlank())
            ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        this.s3 = S3Client.builder().region(Region.of(region)).build();
    }

    @Override
    public void save(String url, String content) throws Exception {
        String key = prefix + hash(url) + ".html";
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("text/html; charset=utf-8")
            .build();
        s3.putObject(request, RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        s3.close();
    }

    private String hash(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }
}
