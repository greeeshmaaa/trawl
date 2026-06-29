package com.crawler.panel;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.List;
import java.util.Map;

@Service
public class CrawlService {
    private final EcsClient ecs;
    private final SqsClient sqs;
    private final DynamoDbClient db;

    private final String cluster   = env("CLUSTER", "trawl-cluster");
    private final String taskDef   = env("TASK_DEF", "trawl-task");
    private final String subnet    = env("SUBNET", "");
    private final String securityGroup = env("SECURITY_GROUP", "");
    private final String region    = env("REGION", "us-east-1");
    private final String queueName = env("QUEUE_NAME", "trawl-frontier");
    private final String tableName = env("TABLE_NAME", "trawl-visited");

    private final String queueUrl;

    // Remembers the most recently launched run so status can be scoped to it.
    private volatile String currentRunId = null;

    public CrawlService() {
        Region r = Region.of(region);
        this.ecs = EcsClient.builder().region(r).build();
        this.sqs = SqsClient.builder().region(r).build();
        this.db  = DynamoDbClient.builder().region(r).build();
        this.queueUrl = sqs.getQueueUrl(
            GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
    }

    public String startCrawl(String seed, int maxPages, int workers) {
        String runId = "run-" + Long.toString(System.currentTimeMillis(), 36);

        ContainerOverride override = ContainerOverride.builder()
            .name("trawl")
            .environment(
                kv("SEED_URL", seed),
                kv("MAX_PAGES", Integer.toString(maxPages)),
                kv("RUN_ID", runId))
            .build();

        ecs.runTask(RunTaskRequest.builder()
            .cluster(cluster)
            .taskDefinition(taskDef)
            .launchType(LaunchType.FARGATE)
            .count(Math.max(1, workers))
            .overrides(TaskOverride.builder().containerOverrides(override).build())
            .networkConfiguration(NetworkConfiguration.builder()
                .awsvpcConfiguration(AwsVpcConfiguration.builder()
                    .subnets(subnet)
                    .securityGroups(securityGroup)
                    .assignPublicIp(AssignPublicIp.ENABLED)
                    .build())
                .build())
            .build());

        this.currentRunId = runId;
        return runId;
    }

    public Map<String, Object> status() {
        var attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
            .queueUrl(queueUrl)
            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
            .build()).attributes();

        int queued   = parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        int inFlight = parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));

        int discovered = countDiscovered();

        List<String> tasks = ecs.listTasks(ListTasksRequest.builder()
            .cluster(cluster).desiredStatus(DesiredStatus.RUNNING).build()).taskArns();

        return Map.of(
            "queued", queued,
            "inFlight", inFlight,
            "discovered", discovered,
            "runningTasks", tasks.size(),
            "runId", currentRunId == null ? "" : currentRunId);
    }

    /** Count visited-set items. Scoped to the current run when one is known. */
    private int countDiscovered() {
        ScanRequest.Builder scan = ScanRequest.builder()
            .tableName(tableName)
            .select(Select.COUNT);

        if (currentRunId != null) {
            // keys are stored as "<runId>#<url>"; count only this run's items
            scan = scan
                .filterExpression("begins_with(#u, :p)")
                .expressionAttributeNames(Map.of("#u", "url"))
                .expressionAttributeValues(Map.of(
                    ":p", AttributeValue.builder().s(currentRunId + "#").build()));
        }
        return db.scan(scan.build()).count();
    }

    private static KeyValuePair kv(String k, String v) {
        return KeyValuePair.builder().name(k).value(v).build();
    }
    private static int parse(String s) {
        try { return s == null ? 0 : Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }
    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
