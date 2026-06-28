# Trawl

A distributed, horizontally-scalable web crawler built on Java 21 virtual threads.

> **Status:** Phase 1 complete — a single-process concurrent crawler. The distributed
> cloud architecture (SQS, S3, DynamoDB, multi-worker) is the active roadmap below.

## What it does

Trawl crawls a website starting from one or more seed URLs, fetching pages
concurrently, staying on-domain, deduplicating URLs, and respecting per-domain
politeness delays. Fetched pages are saved to local disk. Every external dependency
(the queue, the page store) sits behind a small interface so it can be swapped for a
cloud-backed implementation without touching the crawl logic.

## How it works

```
seeds -> [Frontier queue] -> worker (virtual thread) --+
              ^                                         |
              |                                    fetch (HTTP)
         enqueue new                                    |
         in-scope links                            parse links
              |                                         |
              +--------------- dedup (visited set) -----+
                                   |
                              save page -> disk
```

- **Concurrency** — one virtual thread per worker (`Executors.newVirtualThreadPerTaskExecutor`).
  Blocking HTTP calls park cheaply, so the worker count can scale far past OS-thread limits.
- **Deduplication** — URLs are normalized (lowercased scheme/host, default ports and
  fragments stripped) then checked against a concurrent visited-set; `add()` returning
  `false` means already-seen.
- **Politeness** — a per-host minimum delay serializes requests to any single domain
  while letting different domains crawl in parallel.
- **Completion detection** — a pending counter tracks URLs that are queued *or* in
  flight; workers exit only when the queue is empty and nothing is mid-process.
- **Pluggable storage** — `PageStore` is an interface; `LocalPageStore` writes to disk
  today, an `S3PageStore` will drop in later with no changes to the crawler.

## Project layout

```
src/main/java/com/crawler/
├── Main.java              entry point + crawl config
├── Crawler.java           orchestrator + worker loop
├── CrawlConfig.java       seeds, limits, concurrency, delay
├── Frontier.java          in-memory work queue (-> SQS in Phase 2)
├── fetch/                 HTTP client + result type
├── parse/                 link extraction (jsoup)
├── politeness/            per-domain rate limiting
├── store/                 PageStore interface + local-disk impl (-> S3)
└── url/                   URL normalization + host parsing
```

## Requirements

- JDK 21 or newer
- Maven 3.9+

## Run it

```bash
mvn compile exec:java
```

Configuration lives in `Main.java` — seed URLs, max pages, worker count, and the
per-domain delay. The default crawls [books.toscrape.com](https://books.toscrape.com),
a sandbox site built for practicing crawlers. Saved pages land in `crawl-output/`
(named by URL hash).

## Design notes

- **Page-limit is a soft bound.** Under concurrency, several workers can pass the
  "under the limit" check before the counter catches up, so the crawl may save
  slightly more than `maxPages`. This is the at-least-once tradeoff that distributed
  queues make by default; a strict bound is a planned refinement.
- **Crawl is scoped to the seed's host** so it doesn't wander the open web.
- **Timeouts and a real User-Agent are set** on every request — a crawler without
  timeouts eventually hangs on a slow server.

## Roadmap

Phase 1 is built so the cloud jump is a series of localized swaps, not a rewrite:

- [x] **Hardening** — robots.txt compliance, retry/backoff with max attempts, strict page limit
- [ ] **Frontier → Amazon SQS** — workers pull from a shared queue; visibility timeouts
      give retry-on-worker-death for free
- [ ] **LocalPageStore → Amazon S3** — one new class implementing `PageStore`
- [ ] **Visited-set → DynamoDB** — conditional writes for dedup across many machines
- [ ] **Multi-worker on ECS/Fargate** — run the worker as many containers in parallel
