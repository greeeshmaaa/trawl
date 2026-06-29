package com.crawler.dedup;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

public class DynamoVisitedSet implements VisitedSet {
    private final DynamoDbClient db;
    private final String table;
    private final String keyPrefix;

    public DynamoVisitedSet(String tableName, String region, String keyPrefix) {
        this.table = tableName;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.db = DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Override
    public boolean markIfNew(String url) {
        try {
            db.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(Map.of("url", AttributeValue.builder().s(keyPrefix + url).build()))
                .conditionExpression("attribute_not_exists(#u)")
                .expressionAttributeNames(Map.of("#u", "url"))
                .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public void close() {
        db.close();
    }
}
