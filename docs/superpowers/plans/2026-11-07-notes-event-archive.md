# Notes Event Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish note changes through a transactional outbox, archive Kafka events to S3Mock in JSONL batches, and replay a baseline plus events into a separate DynamoDB Local table.

**Architecture:** The Notes API writes each note change and versioned event to DynamoDB in one transaction. A relay publishes pending outbox records to a local Kafka topic; a batch consumer writes deterministic per-partition offset segments and manifests to S3Mock. A maintenance command captures a baseline and replays it with later segments into a dedicated table.

**Tech Stack:** Java 25, Spring Boot 4, Spring Kafka, AWS SDK for Java v2 DynamoDB/S3, Kafka 4.4.0 in KRaft mode, S3Mock 5.2.3, DynamoDB Local, Docker Compose, kind, Prometheus, Grafana, JUnit 5, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-07-notes-archive-design.md`

**Dependency:** Execute `2026-11-07-notes-export-import.md` first; this plan reuses its `ObjectStore` adapter and snapshot DTOs.

## Global Constraints

- No AWS account, real AWS credentials, AWS calls, or paid resources.
- Kafka topic is `notes.events`; producer key is `noteId`.
- Event types are `NoteCreated`, `NoteImported`, and `NoteDeleted`, with full note snapshot for audit and replay.
- Publish at least once; event IDs are stable; replay deduplicates repeated event IDs.
- S3 event objects are JSONL, grouped by topic and partition, with deterministic offset-range keys and a manifest.
- Confirm Kafka offsets only after the S3 data object and manifest are written.
- Baseline capture pauses local writes, waits for pending outbox entries to publish, records notes and Kafka next offsets, then resumes writes.
- Replay writes only to the separate `notes-replay` table; it never clears or updates `notes`.
- The project remains a single deployable app for this phase; extraction to a microservice is future work.

## Review Focus

- DynamoDB transaction failure: neither note mutation nor event is committed; pin in `NoteApplicationServiceTests`.
- Kafka publish succeeds but outbox acknowledgement fails: retry reuses event ID and replay applies it once; pin in `OutboxRelayTests`.
- S3 write or manifest write fails: Kafka offset is not committed; pin in `EventArchiveConsumerTests`.
- Duplicate event IDs at different Kafka offsets: replay applies the event once; pin in `ArchiveReplayServiceTests`.
- Missing archive offsets or invalid checksum: replay stops before mutating target; pin in `ArchiveReplayServiceTests`.

---

## File Map

- Modify `app/pom.xml`: add Spring Kafka and Kafka test support.
- Create `app/src/main/java/dev/mrlptrc/notes/events/NoteEvent.java`: versioned event contract.
- Create `app/src/main/java/dev/mrlptrc/notes/events/NoteEventType.java`: supported event types.
- Create `app/src/main/java/dev/mrlptrc/notes/events/OutboxRecord.java`: DynamoDB outbox row.
- Create `app/src/main/java/dev/mrlptrc/notes/events/OutboxRepository.java`: transactional writes and pending record access.
- Create `app/src/main/java/dev/mrlptrc/notes/events/NoteApplicationService.java`: create, delete, and import orchestration.
- Create `app/src/main/java/dev/mrlptrc/notes/events/OutboxRelay.java`: scheduled at-least-once Kafka publisher.
- Create `app/src/main/java/dev/mrlptrc/notes/archive/ArchiveSegment.java`: JSONL segment and manifest DTOs.
- Create `app/src/main/java/dev/mrlptrc/notes/archive/EventArchiveConsumer.java`: batch Kafka consumer and S3 writer.
- Create `app/src/main/java/dev/mrlptrc/notes/archive/ArchiveBaseline.java`: snapshot and per-partition next offsets.
- Create `app/src/main/java/dev/mrlptrc/notes/archive/ArchiveBaselineService.java`: quiesced baseline creation.
- Create `app/src/main/java/dev/mrlptrc/notes/archive/ArchiveMaintenanceRunner.java`: baseline/replay command runner, enabled only with `app.archive.command`.
- Create `app/src/main/java/dev/mrlptrc/notes/archive/ArchiveReplayService.java`: validate and apply a baseline plus event segments.
- Create `app/src/test/java/dev/mrlptrc/notes/events/NoteApplicationServiceTests.java` and `OutboxRelayTests.java`.
- Create `app/src/test/java/dev/mrlptrc/notes/archive/EventArchiveConsumerTests.java` and `ArchiveReplayServiceTests.java`.
- Extend `app/src/test/java/dev/mrlptrc/notes/NotesApiApplicationTests.java` for event-producing mutations.
- Modify `app/src/main/resources/application.properties`: Kafka/outbox/archive config with safe local defaults.
- Modify `compose.yaml`: local KRaft broker and app bootstrap endpoint.
- Create `k8s/kafka.yaml`; modify `k8s/api-config.yaml` and `k8s/api.yaml` for Kafka config and the single-replica baseline constraint.
- Modify `k8s/observability/dashboard.json` for outbox and archive metrics.
- Create `scripts/smoke-test-event-archive.sh` and extend `.github/workflows/ci.yml` to deploy Kafka and run it in kind.
- Modify `docs/executando.md` and `README.md` with event schema, baseline, archive and replay instructions.

### Task 1: Add event contract and transactional outbox

**Interfaces:**
- `NoteEvent(int schemaVersion, String eventId, NoteEventType type, String noteId, Instant occurredAt, NoteSnapshot note)`.
- `OutboxRepository.createNoteWithEvent(Note note, NoteEvent event)`.
- `OutboxRepository.deleteNoteWithEvent(Note note, NoteEvent event)`.
- `OutboxRepository.findPending(int limit) -> List<OutboxRecord>` and `markPublished(String eventId)`.
- `NoteApplicationService.create(String title, String content) -> Note`, `delete(String id)`, `importNote(Note) -> ImportOutcome`.

- [ ] Add focused tests for create/delete atomicity, note-not-found behavior, event payload snapshots and import outcome.
- [ ] Run focused tests and confirm failure before implementation.
- [ ] Create the `note-events-outbox` table initializer and `OutboxRecord` schema keyed by `eventId`, storing serialized event, `status`, `createdAt`, and optional `publishedAt`.
- [ ] Implement create/delete transactions with DynamoDB `TransactWriteItems`; a failed transaction must leave both note and outbox unchanged.
- [ ] Refactor `NoteController` and `NoteImportService` to delegate mutations to `NoteApplicationService`; successful imported notes create `NoteImported` events.
- [ ] Rerun focused and existing API integration tests; expected PASS.
- [ ] Commit Task 1 as `feat: record note changes in transactional outbox`.

### Task 2: Run local Kafka and publish outbox records

**Files:** `pom.xml`, new `events/OutboxRelay.java`, Kafka configuration, `compose.yaml`, `k8s/kafka.yaml`, event/relay tests.

- [ ] Add tests that a pending event is published with key `noteId`, then marked published; a publish failure leaves it pending; an ack failure retries the same `eventId`.
- [ ] Add `spring-kafka`; configure Kafka producer serializers for the `NoteEvent` JSON contract and idempotent producer mode.
- [ ] Implement a scheduled relay that reads at most 100 pending entries per poll, sends them to `notes.events`, waits for broker acknowledgment, then marks them published.
- [ ] Add a single-broker Apache Kafka 4.4.0 KRaft service to Compose and kind; configure one partition for the lab and use `noteId` as the record key.
- [ ] Add `APP_KAFKA_BOOTSTRAP_SERVERS` and outbox poll interval configuration to application defaults, Compose and the ConfigMap.
- [ ] Add counters/timers for pending outbox count, publish success/failure and publish latency.
- [ ] Run focused tests and verify a created/deleted note appears in `notes.events` through Kafka CLI; expected one logical event ID per successful mutation.
- [ ] Commit Task 2 as `feat: publish note events to local kafka`.

### Task 3: Archive Kafka batches in S3Mock

**Files:** new archive DTOs/consumer, consumer tests, Kafka and S3 configuration.

**Interfaces:**
- `ArchiveSegmentKey(String topic, int partition, long firstOffset, long lastOffset)`.
- `EventArchiveConsumer.archiveBatch(List<ConsumerRecord<String, NoteEvent>> records)`.
- Object key: `events/topic={topic}/partition={partition}/segment={firstOffset}-{lastOffset}.jsonl`.
- Manifest key: same path with `.manifest.json` suffix; fields: schema version, topic, partition, first/last offsets, record count, checksum.

- [ ] Test JSONL encoding, grouping by partition, deterministic object names, manifest offsets/checksum, repeated batch overwrite, and S3 failure without offset acknowledgment.
- [ ] Configure a Spring Kafka batch listener with manual offset acknowledgment and maximum poll size 100.
- [ ] Implement archive write: group each poll by partition, sort by offset, write JSONL object, write manifest, acknowledge only after both writes succeed.
- [ ] Enable retry with bounded backoff; after retries are exhausted, keep the consumer offset uncommitted and expose the failure metric.
- [ ] Track archived event count, batch count/size, batch latency, failures and consumer lag.
- [ ] Run integration test with Kafka Testcontainer and S3Mock Testcontainer; expected archived JSONL can be fetched and parsed with the manifest.
- [ ] Commit Task 3 as `feat: archive kafka events in s3mock`.

### Task 4: Capture a baseline and replay the archive

**Files:** `ArchiveBaseline`, maintenance operation, replay service, replay table config and tests.

**Interfaces:**
- `ArchiveBaselineService.createBaseline() -> BaselineDescriptor`.
- `ArchiveReplayService.replay(String baselineId, String targetTable) -> ReplayReport`.
- Baseline stores schema version, complete note snapshot, creation time, and next Kafka offsets for every partition.
- Target table defaults to `notes-replay` and must be empty; replay rejects the active table `notes`.

- [ ] Test baseline publishes all pending outbox rows before capturing the note snapshot and Kafka end offsets.
- [ ] Implement baseline creation against a quiesced API: require the API deployment/process stopped externally, publish pending outbox rows, capture current notes and Kafka next offsets, then store the baseline.
- [ ] Store the baseline JSON and offset manifest under `baselines/{baselineId}.json` in S3Mock.
- [ ] Test replay of create/import/delete, duplicate event IDs, missing offset segments, checksum mismatch, non-empty target table, and active-table refusal.
- [ ] Implement replay: validate all required manifests/checksums before writes; populate an empty target from the baseline, then apply later segments per partition/offset while deduplicating by `eventId`.
- [ ] Create the `notes-replay` table if absent; reject it if non-empty and reject any target equal to `notes`.
- [ ] Implement `ArchiveMaintenanceRunner` commands `baseline` and `replay`; invoke with `--spring.main.web-application-type=none --app.archive.command=baseline` or `--app.archive.command=replay --app.archive.baseline-id={id}`. Disable the normal outbox scheduler and Kafka listener in maintenance mode; the baseline runner publishes pending outbox rows before recording Kafka end offsets.
- [ ] Require the API to be stopped/scaled to zero during baseline capture; document `docker compose stop api` / `start api` and `kubectl scale deployment/notes-api --replicas=0` / `1` around the command.
- [ ] Run integration tests with DynamoDB Local, Kafka and S3Mock containers; expected replayed list/get results match the source state at the recorded offsets.
- [ ] Commit Task 4 as `feat: rebuild notes from the s3 event archive`.

### Task 5: Wire observability, Kubernetes, CI and docs

**Files:** Kafka manifest, app deployment/config, Grafana dashboard, smoke script, CI, docs.

- [ ] Verify Kafka Deployment and Service readiness in kind; keep Notes API at one replica for this lab's relay and require it scaled to zero during baseline capture.
- [ ] Add Prometheus dashboard panels for pending outbox, event publish failures/latency, consumer lag, segment writes/failures, and replay results.
- [ ] Add an end-to-end smoke test: create note, verify Kafka event, verify archived segment/manifest, create baseline, create and delete another note, replay into `notes-replay`, and compare resulting notes.
- [ ] Extend CI to dry-run/apply Kafka manifests, wait for Kafka readiness, and run the event archive smoke test in kind without AWS credentials.
- [ ] Document baseline maintenance commands, replay target table, event schema/versioning, offset segments, local persistence, and single-replica limitation.
- [ ] Run `cd app && ./mvnw spotless:check verify`, `docker compose config`, server-side dry-run of all kind manifests, API/S3/Kafka smoke scripts and the full GitHub Actions workflow-equivalent commands; expected PASS with no AWS access.
- [ ] Commit Task 5 as `docs: document local kafka archive and replay`.
