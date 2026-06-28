package com.crawler.frontier;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

public class SqsFrontier implements Frontier {
    private final SqsClient sqs;
    private final String queueUrl;
    private final int waitSeconds;
    private final int visibilitySeconds;
    private final int maxEmptyPolls;

    private final ThreadLocal<Integer> emptyPolls = ThreadLocal.withInitial(() -> 0);

    public SqsFrontier(String queueName, String region,
                       int waitSeconds, int visibilitySeconds, int maxEmptyPolls) {
        this.sqs = SqsClient.builder().region(Region.of(region)).build();
        this.queueUrl = sqs.getQueueUrl(
            GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
        this.waitSeconds = waitSeconds;
        this.visibilitySeconds = visibilitySeconds;
        this.maxEmptyPolls = maxEmptyPolls;
    }

    @Override
    public void add(String url) {
        sqs.sendMessage(SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(url)
            .build());
    }

    @Override
    public Task next() {
        ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(1)
            .waitTimeSeconds(waitSeconds)
            .visibilityTimeout(visibilitySeconds)
            .build());

        List<Message> messages = response.messages();
        if (messages.isEmpty()) {
            emptyPolls.set(emptyPolls.get() + 1);
            return null;
        }
        emptyPolls.set(0);
        Message m = messages.get(0);
        return new Task(m.body(), m.receiptHandle());
    }

    @Override
    public void complete(Task task) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(task.handle())
            .build());
    }

    @Override
    public boolean isExhausted() {
        return emptyPolls.get() >= maxEmptyPolls;
    }

    @Override
    public void close() {
        sqs.close();
    }
}
