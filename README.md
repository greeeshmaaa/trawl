# Trawl

A distributed, horizontally-scalable web crawler built on Java 21 virtual threads,
Amazon SQS, S3, and DynamoDB — deployable as multiple coordinated workers on AWS Fargate.

> **Status:** Complete. All components below are implemented and have been demonstrated
> running as multiple concurrent workers on Fargate, coordinating purely through shared
> AWS state (no shared memory, no leader, no direct communication).

## What it does

Trawl crawls a website from one or more seed URLs, fetching pages concurrently, staying
on-domain, deduplicating URLs across machines, respecting `robots.txt` and per-domain
politeness, and retrying transient failures. The URL queue lives in SQS, pages are written
to S3, and dedup state lives in DynamoDB — so any number of worker processes (local or on
Fargate) can crawl the same site cooperatively without crawling a URL twice.

Every external dependency sits behind a small interface, so each cloud capability was added
as one new class and one wiring line, never a rewrite.

## Architecture

```
                 +------------------+
   seeds ------> |   Amazon SQS     |  durable shared frontier
                 |  (URL frontier)  |  visibility timeout -> redelivery on worker death
                 +---------+--------+
                           | receive
            +--------------+--------------+   ... N workers (Fargate tasks / local procs)
            |  worker (virtual thread)    |
            |   1. robots.txt check       |
            |   2. claim page-budget slot |
            |   3. politeness wait        |
            |   4. fetch (retry/backoff)  |
            |   5. save page  ----------> |  Amazon S3 (page storage)
            |   6. extract + enqueue links|
            +--------------+--------------+
                           | markIfNew(url) = conditional PutItem
                           v
                 +------------------+
                 |   DynamoDB       |  shared dedup: exactly one worker claims each URL
                 +------------------+
```

- **Concurrency** — one virtual thread per worker (`Executors.newVirtualThreadPerTaskExecutor`).
  Blocking HTTP and AWS calls park cheaply, so worker count scales far past OS-thread limits.
- **Durable frontier (SQS)** — a received message is invisible for a visibility timeout; if
  the worker dies before deleting it, SQS redelivers it. Work is not lost on crash.
- **Cross-machine dedup (DynamoDB)** — claiming a URL is a conditional `PutItem`
  ("write only if absent"). Among many workers racing on the same URL, exactly one succeeds.
- **Page storage (S3)** — each page is an object keyed by a hash of its URL.
- **robots.txt** — fetched and cached per host; longest-match-wins, `Allow` beats `Disallow`
  on ties, with `*` and `$` pattern support; honors `Crawl-delay`.
- **Resilience** — retries on 5xx / 429 / network errors with exponential backoff and jitter;
  never retries 4xx.
- **Pluggable everything** — `Frontier`, `PageStore`, and `VisitedSet` are interfaces with
  in-memory and AWS-backed implementations, chosen by dependency injection in `Main`.

## Project layout

```
src/main/java/com/crawler/
├── Main.java              entry point: wires config, frontier, store, dedup
├── Crawler.java           orchestrator + worker loop
├── CrawlConfig.java       seeds, limits, concurrency, delay, retries, robots flag
├── dedup/                 VisitedSet interface, InMemory + Dynamo implementations
├── fetch/                 HTTP client (retry/backoff) + result type
├── frontier/              Frontier interface, InMemory + Sqs implementations, Task
├── parse/                 link extraction (jsoup)
├── politeness/            per-domain rate limiting
├── robots/                robots.txt fetch, parse, and path matching
├── store/                 PageStore interface, Local + S3 implementations
└── url/                   URL normalization + host parsing
Dockerfile                 multi-stage build -> slim JRE runtime image
task-definition.json       ECS/Fargate task spec (roles, image, logging)
```

## Requirements

- JDK 21+ and Maven 3.9+
- For the cloud path: an AWS account, Docker, and the AWS CLI configured
- AWS resources: an S3 bucket, an SQS queue, and a DynamoDB table (partition key `url`)

Credentials are always read from the environment — the default provider chain locally,
or an IAM task role on Fargate. No keys ever appear in source or in the image.

## Run locally

Fully local (no AWS) — switch `Main` to `InMemoryFrontier`, `LocalPageStore`,
`InMemoryVisitedSet` (one-line swaps, left commented in `Main`), then:

```bash
mvn compile exec:java
```

Against AWS — provision the resources once and set their names/region in `Main`:

```bash
aws s3 mb s3://YOUR_BUCKET --region us-east-1
aws sqs create-queue --queue-name trawl-frontier --region us-east-1
aws dynamodb create-table --table-name trawl-visited \
  --attribute-definitions AttributeName=url,AttributeType=S \
  --key-schema AttributeName=url,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST --region us-east-1
mvn compile exec:java
```

Run two processes in two terminals to see them split the frontier with no double-crawling.

## Deploy to AWS Fargate

The worker is packaged as a container and run as N Fargate tasks.

```bash
# 1. Build for x86_64 (Fargate's default arch) and push to ECR
docker build --platform linux/amd64 --provenance=false -t trawl .
aws ecr create-repository --repository-name trawl --region us-east-1
aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin <ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com
docker tag trawl:latest <ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com/trawl:latest
docker push <ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com/trawl:latest

# 2. Register the task definition (references an execution role + a task role)
aws ecs register-task-definition --cli-input-json file://task-definition.json --region us-east-1

# 3. Run N coordinated workers
aws ecs run-task --cluster trawl-cluster --task-definition trawl-task \
  --launch-type FARGATE --count 2 \
  --network-configuration "awsvpcConfiguration={subnets=[SUBNET],securityGroups=[SG],assignPublicIp=ENABLED}" \
  --region us-east-1
```

The **task role** grants the container its S3/SQS/DynamoDB access — the SDK picks it up
automatically, so no credentials are baked into the image. The build is a multi-stage
Dockerfile: a Maven stage produces a shaded uber-JAR (with the AWS SDK's `META-INF/services`
files merged so HTTP-client discovery works), and a slim JRE stage runs it. Tasks crawl until
the frontier drains, then exit on their own.

## Design notes

- **Strict page limit.** A worker reserves a budget slot via atomic compare-and-set *before*
  fetching, releasing it on failure — so saved pages never exceed `maxPages` per process and
  no fetch happens once the budget is full.
- **Completion detection differs by frontier.** In-memory uses an exact pending counter; SQS,
  having no shared counter across machines, treats N consecutive empty long-polls as drained.
  Exact distributed termination would need consensus — the heuristic is the pragmatic choice.
- **The page budget is per-process.** Each worker enforces its own `maxPages`; N workers can
  save up to N × maxPages. A global cap would use a shared atomic counter (a DynamoDB
  atomic increment).
- **Dedup costs one write per discovered URL.** A local cache or bloom filter in front of
  DynamoDB would cut round-trips for URLs a process has already seen.
- **Crawl is scoped to the seed's host**, with timeouts and a real User-Agent on every request.

## Roadmap

- [x] **Hardening** — robots.txt compliance, retry/backoff with max attempts, strict page limit
- [x] **Frontier → Amazon SQS** — shared queue; visibility timeouts give retry-on-worker-death
- [x] **LocalPageStore → Amazon S3** — one new class implementing `PageStore`
- [x] **Visited-set → DynamoDB** — conditional writes for dedup across many machines
- [x] **Multi-worker on ECS/Fargate** — the worker containerized and run as N coordinated tasks