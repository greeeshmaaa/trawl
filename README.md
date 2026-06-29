# Trawl

A distributed, horizontally-scalable web crawler built on Java 21 virtual threads,
Amazon SQS, S3, and DynamoDB — with a Spring Boot control panel that launches and
monitors crawls running as multiple workers on AWS Fargate.

> **Status:** Complete. The crawler runs as coordinated multi-worker Fargate tasks
> (no shared memory, no leader — coordination is entirely through shared AWS state).
> A web control panel launches crawls and shows live progress. The panel currently runs
> locally and launches real cloud workers; public hosting (App Runner) is the one
> remaining optional step.

## What it does

Trawl crawls a website from one or more seed URLs, fetching pages concurrently, staying
on-domain, deduplicating URLs across machines, respecting `robots.txt` and per-domain
politeness, and retrying transient failures. The URL queue lives in SQS, pages are written
to S3, and dedup state lives in DynamoDB — so any number of worker processes (local or on
Fargate) crawl the same site cooperatively without crawling a URL twice. A Spring Boot
control panel starts crawls (launching Fargate tasks with the seed and limits as overrides)
and polls live progress.

Every external dependency sits behind a small interface, so each capability was added as one
new class and one wiring line, never a rewrite.

## Architecture

```
   [ Control Panel ]  Spring Boot: POST /api/crawls, GET /api/status
        |  runTask (seed, maxPages, runId as overrides)
        v
   +------------------+
   |   Amazon SQS     |  durable shared frontier; visibility timeout -> redelivery on crash
   |  (URL frontier)  |
   +---------+--------+
             | receive
   +---------+---------+   ... N workers (Fargate tasks)
   |  worker (vthread) |
   |   robots -> claim |
   |   slot -> fetch   |
   |   -> save -> S3   |---> Amazon S3 (page storage, keyed by URL hash)
   |   -> enqueue links|
   +---------+---------+
             | markIfNew(url) = conditional PutItem
             v
   +------------------+
   |   DynamoDB       |  shared dedup: exactly one worker claims each URL
   +------------------+
```

- **Concurrency** — one virtual thread per worker. Blocking HTTP and AWS calls park cheaply,
  so worker count scales far past OS-thread limits.
- **Durable frontier (SQS)** — a received message is invisible for a visibility timeout; if
  the worker dies before deleting it, SQS redelivers it. Work survives a crash.
- **Cross-machine dedup (DynamoDB)** — claiming a URL is a conditional `PutItem`
  ("write only if absent"). Among workers racing on the same URL, exactly one succeeds.
  Keys are namespaced by a per-crawl run ID so re-crawling a site is not blocked.
- **Page storage (S3)** — each page is an object under `runs/<runId>/pages/`.
- **robots.txt** — fetched and cached per host; longest-match-wins, `Allow` beats `Disallow`
  on ties, with `*` / `$` support; honors `Crawl-delay`.
- **Resilience** — retries on 5xx / 429 / network errors with exponential backoff and jitter;
  never retries 4xx.
- **Pluggable + injectable** — `Frontier`, `PageStore`, and `VisitedSet` are interfaces with
  in-memory and AWS-backed implementations, chosen in `Main` (a `LOCAL` env toggle selects
  the in-memory set for offline development).

## Repository layout

Two projects in one repo: the crawler worker, and the control panel.

```
trawl/
├── src/main/java/com/crawler/      the crawler worker
│   ├── Main.java                   reads config from env vars, wires components
│   ├── Crawler.java                orchestrator + worker loop
│   ├── CrawlConfig.java
│   ├── dedup/                      VisitedSet: InMemory + Dynamo (conditional write)
│   ├── fetch/                      HTTP client (retry/backoff)
│   ├── frontier/                   Frontier: InMemory + Sqs (visibility-timeout redelivery)
│   ├── parse/                      link extraction (jsoup)
│   ├── politeness/                 per-domain rate limiting
│   ├── robots/                     robots.txt fetch, parse, matching
│   ├── store/                      PageStore: Local + S3
│   └── url/                        normalization + host parsing
├── Dockerfile                      multi-stage build -> slim JRE image (worker)
├── task-definition.json            ECS/Fargate task spec (roles, image, logging)
└── control-panel/                  Spring Boot web app
    ├── src/main/java/com/crawler/panel/
    │   ├── PanelApplication.java
    │   ├── CrawlService.java        launches Fargate tasks; reads SQS/DynamoDB/ECS stats
    │   └── PanelController.java      REST: POST /api/crawls, GET /api/status
    └── src/main/resources/static/index.html   single-page UI (form + live polling)
```

## Requirements

- JDK 21+ and Maven 3.9+
- For the cloud path: an AWS account, Docker, and the AWS CLI configured
- AWS resources: an S3 bucket, an SQS queue, a DynamoDB table (partition key `url`),
  and (for Fargate) an ECS cluster, task definition, and the two IAM roles

Credentials are read from the environment — the default provider chain locally, or an IAM
task role on Fargate. No keys appear in source or in any image.

## Run the crawler

Fully local (no AWS) — the `LOCAL` toggle uses in-memory implementations:

```bash
LOCAL=1 SEED_URL="https://books.toscrape.com/" MAX_PAGES=50 mvn compile exec:java
```

Against AWS — provision resources once, then run (configurable via env vars):

```bash
SEED_URL="https://books.toscrape.com/" MAX_PAGES=200 mvn compile exec:java
```

Run two processes in two terminals to watch them split the frontier with no double-crawling.
Configurable env vars: `SEED_URL`, `MAX_PAGES`, `CONCURRENCY`, `PER_DOMAIN_DELAY_MS`,
`MAX_RETRIES`, `RESPECT_ROBOTS`, `RUN_ID`, `REGION`, `BUCKET`, `QUEUE_NAME`, `TABLE_NAME`,
`LOCAL`.

## Deploy the worker to Fargate

```bash
# Build for x86_64 (Fargate's default arch) and push to ECR
docker build --platform linux/amd64 --provenance=false -t trawl .
aws ecr create-repository --repository-name trawl --region us-east-1
aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin <ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com
docker tag trawl:latest <ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com/trawl:latest
docker push <ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com/trawl:latest

# Register the task definition and run N coordinated workers
aws ecs register-task-definition --cli-input-json file://task-definition.json --region us-east-1
aws ecs run-task --cluster trawl-cluster --task-definition trawl-task \
  --launch-type FARGATE --count 2 \
  --network-configuration "awsvpcConfiguration={subnets=[SUBNET],securityGroups=[SG],assignPublicIp=ENABLED}" \
  --region us-east-1
```

The task's **task role** grants the container its S3/SQS/DynamoDB access — picked up
automatically by the SDK, so no credentials are baked into the image. The build is a
multi-stage Dockerfile: a Maven stage produces a shaded uber-JAR (AWS SDK
`META-INF/services` files merged so HTTP-client discovery works), and a slim JRE stage runs
it. Tasks crawl until the frontier drains, then exit on their own.

## Run the control panel

A Spring Boot web app that starts crawls and shows live progress. It needs a subnet and
security group (for the Fargate network config) and uses your credentials to launch tasks:

```bash
cd control-panel
SUBNET=subnet-xxxx SECURITY_GROUP=sg-yyyy mvn spring-boot:run
```

Open http://localhost:8080, enter a seed URL / page limit / worker count, and click
**Start crawl** — it launches Fargate tasks and the tiles show queue depth, in-flight count,
URLs discovered (scoped to the current run), and running workers, polled live.

Hosting the panel publicly on AWS App Runner (with an instance role granting `ecs:RunTask`,
`iam:PassRole`, and the read access the status view needs) is an optional next step.

## Design notes

- **Strict page limit.** A worker reserves a budget slot via atomic compare-and-set *before*
  fetching, releasing it on failure — so saved pages never exceed `maxPages` per process.
- **Completion detection differs by frontier.** In-memory uses an exact pending counter; SQS,
  having no shared counter across machines, treats N consecutive empty long-polls as drained.
  Exact distributed termination would need consensus — the heuristic is the pragmatic choice.
- **The page budget is per-process.** N workers can each save up to `maxPages`; a global cap
  would use a shared atomic counter (a DynamoDB atomic increment).
- **Per-run "discovered" count.** The panel filters the dedup table by the run's key prefix.
  A `Scan` with a filter still reads the whole table; at scale a per-run counter item would
  be cheaper.
- **Crawl is scoped to the seed's host**, with timeouts and a real User-Agent on every request.

## Roadmap

- [x] **Hardening** — robots.txt compliance, retry/backoff with max attempts, strict page limit
- [x] **Frontier → Amazon SQS** — shared queue; visibility timeouts give retry-on-worker-death
- [x] **LocalPageStore → Amazon S3** — one new class implementing `PageStore`
- [x] **Visited-set → DynamoDB** — conditional writes for dedup across many machines
- [x] **Multi-worker on ECS/Fargate** — the worker containerized and run as N coordinated tasks
- [x] **Control panel** — Spring Boot UI to launch crawls and watch live progress
- [ ] **Host the control panel on App Runner** — public URL with an IAM instance role