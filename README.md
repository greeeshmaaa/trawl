# Trawl

A distributed, horizontally-scalable web crawler built on Java 21 virtual threads.

> **Status:** A hardened concurrent crawler with a durable **Amazon SQS** frontier and
> **Amazon S3** page storage. Cross-machine deduplication (DynamoDB) and multi-worker
> deployment (ECS/Fargate) are the active roadmap below — until DynamoDB lands, run a
> single worker process (see *Deduplication* under Design notes).

## What it does

Trawl crawls a website from one or more seed URLs, fetching pages concurrently, staying
on-domain, deduplicating URLs, respecting `robots.txt` and per-domain politeness delays,
and retrying transient failures. The URL queue lives in SQS and fetched pages are written
to S3. Every external dependency (the queue, the page store) sits behind a small interface,
so each cloud swap was one new class and one wiring line — not a rewrite.

## How it works

```
seeds -> [Frontier: SQS] -> worker (virtual thread) --+
              ^                                        |
              |                                   fetch (HTTP)
         enqueue new                                   |
         in-scope links                           parse links
              |                                        |
              +--------------- dedup (visited set) ----+
                                   |
                              save page -> S3
```

- **Concurrency** — one virtual thread per worker (`Executors.newVirtualThreadPerTaskExecutor`).
  Blocking HTTP and AWS calls park cheaply, so the worker count can scale far past OS-thread limits.
- **Durable frontier (SQS)** — `Frontier` is an interface with an in-memory implementation
  and an `SqsFrontier`. With SQS, a received message stays invisible for a visibility
  timeout; if the worker dies before acking it, SQS redelivers it automatically — so work
  is not lost when a worker crashes.
- **Pluggable storage (S3)** — `PageStore` is an interface; `LocalPageStore` writes to disk,
  `S3PageStore` writes objects to a bucket. Selected in `Main` via dependency injection.
- **Deduplication** — URLs are normalized (lowercased scheme/host, default ports and
  fragments stripped) then checked against a concurrent visited-set; `add()` returning
  `false` means already-seen.
- **Politeness** — a per-host minimum delay serializes requests to any single domain while
  letting different domains crawl in parallel; honors `Crawl-delay` from `robots.txt` when larger.
- **robots.txt compliance** — fetched and cached per host, with longest-match-wins rules,
  `Allow` overriding `Disallow` on ties, and `*` / `$` pattern support.
- **Resilience** — fetches retry on 5xx, 429, and network errors with exponential backoff
  plus jitter; 4xx responses are not retried.

## Project layout

```
src/main/java/com/crawler/
├── Main.java              entry point: wires config, frontier, and store
├── Crawler.java           orchestrator + worker loop
├── CrawlConfig.java       seeds, limits, concurrency, delay, retries, robots flag
├── fetch/                 HTTP client (retry/backoff) + result type
├── frontier/              Frontier interface, InMemoryFrontier, SqsFrontier, Task
├── parse/                 link extraction (jsoup)
├── politeness/            per-domain rate limiting
├── robots/                robots.txt fetch, parse, and path matching
├── store/                 PageStore interface, LocalPageStore, S3PageStore
└── url/                   URL normalization + host parsing
```

## Requirements

- JDK 21 or newer
- Maven 3.9+
- An AWS account with credentials configured locally (`aws configure`), plus:
  - an S3 bucket, and an SQS queue, in the same region
  - IAM permissions for S3 and SQS

Credentials are read from the default provider chain (`~/.aws/credentials`) and never
appear in source.

## Run it

Provision the cloud resources once (example region `us-east-1`):

```bash
aws s3 mb s3://YOUR_BUCKET_NAME --region us-east-1
aws sqs create-queue --queue-name trawl-frontier --region us-east-1
```

Set the bucket name, queue name, and region in `Main.java`, then:

```bash
mvn compile exec:java
```

`Main.java` selects the frontier and store by dependency injection. For a fully local run
(no AWS), switch to `InMemoryFrontier` and `LocalPageStore` — both are one-line swaps left
commented in `Main`. The default crawls
[books.toscrape.com](https://books.toscrape.com), a sandbox site built for practicing crawlers.

## Design notes

- **Strict page limit.** A worker reserves a slot in the page budget (an atomic
  compare-and-set) *before* fetching, and releases it if the fetch fails or returns
  non-HTML. So the number of saved pages never exceeds `maxPages`, and no fetch happens
  once the budget is full.
- **Completion detection differs by frontier.** In-memory uses an exact pending counter
  (queued *or* in-flight); workers stop only when it reaches zero. SQS has no shared
  counter across machines, so it uses a heuristic: after N consecutive empty long-polls,
  a worker treats the queue as drained and exits. Exact distributed termination would
  require consensus — the heuristic is the standard pragmatic choice.
- **Deduplication is currently in-process.** The visited-set lives in one JVM, so it is
  correct for a single worker process. Running multiple processes against the shared SQS
  queue today would double-fetch, because neither sees the other's visited-set. Moving
  this to DynamoDB (conditional writes) is the next roadmap item and is what makes
  horizontal scale correct.
- **Crawl is scoped to the seed's host** so it doesn't wander the open web.
- **Timeouts and a real User-Agent** are set on every request; AWS clients are closed
  cleanly via try-with-resources.

## Roadmap

Built so each cloud step is a localized swap, not a rewrite:

- [x] **Hardening** — robots.txt compliance, retry/backoff with max attempts, strict page limit
- [x] **Frontier → Amazon SQS** — workers pull from a shared queue; visibility timeouts give retry-on-worker-death for free
- [x] **LocalPageStore → Amazon S3** — one new class implementing `PageStore`
- [ ] **Visited-set → DynamoDB** — conditional writes for dedup across many machines
- [ ] **Multi-worker on ECS/Fargate** — run the worker as many containers in parallel