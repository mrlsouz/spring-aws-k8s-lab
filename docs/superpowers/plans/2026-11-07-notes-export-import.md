# Notes Export and Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export and restore notes through the local S3Mock bucket without calling AWS.

**Architecture:** Notes remain in DynamoDB Local. A focused S3 adapter stores versioned JSON snapshots under `exports/`; API use cases create, list, download, and import those snapshots. Import is resumable and reports per-note results, so a retry does not silently duplicate or overwrite conflicting notes.

**Tech Stack:** Java 25, Spring Boot 4, AWS SDK for Java v2 S3 and DynamoDB clients, S3Mock 5.2.3, DynamoDB Local, Docker Compose, kind, JUnit 5, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-07-notes-archive-design.md`

## Global Constraints

- No AWS account, real AWS credentials, AWS calls, or paid resources.
- Use bucket `notes-local` and object prefix `exports/`.
- Snapshot format is JSON with `schemaVersion`, `exportId`, `generatedAt`, and complete notes including IDs.
- Import preserves IDs; same ID and same content is skipped, conflicting content is reported and never overwritten.
- S3Mock data must survive container and Pod restarts through local disk volumes.
- Keep S3 operations behind an application service and object-storage adapter.

## Review Focus

- Invalid or unsupported snapshot schema: return a client error and write no note for the invalid record; pin in `NoteImportServiceTests`.
- Duplicate note IDs with identical content: report as skipped; pin in `NoteImportServiceTests`.
- Duplicate note IDs with different content: report a conflict and preserve existing data; pin in `NoteImportServiceTests`.
- S3 write/read failure: return a server error without exposing SDK internals; pin in `NoteExportServiceTests`.
- S3Mock Pod replacement: previously written exports remain downloadable; pin in `scripts/smoke-test-s3mock.sh`.

---

## File Map

- Modify `app/pom.xml`: add the AWS SDK v2 S3 module under the existing BOM.
- Create `app/src/main/java/dev/mrlptrc/notes/storage/S3Config.java`: local endpoint, dummy credentials, region and bucket configuration.
- Create `app/src/main/java/dev/mrlptrc/notes/storage/ObjectStore.java`: narrow put/get/list contract.
- Create `app/src/main/java/dev/mrlptrc/notes/storage/S3ObjectStore.java`: S3 SDK adapter.
- Create `app/src/main/java/dev/mrlptrc/notes/export/NotesSnapshot.java`: versioned snapshot DTO.
- Create `app/src/main/java/dev/mrlptrc/notes/export/NoteSnapshot.java`: serialized note data independent of DynamoDB bean annotations.
- Create `app/src/main/java/dev/mrlptrc/notes/export/ExportDescriptor.java`: export ID and object metadata.
- Create `app/src/main/java/dev/mrlptrc/notes/export/ImportReport.java`: imported, skipped, conflicting, and failed IDs.
- Create `app/src/main/java/dev/mrlptrc/notes/export/NoteExportService.java`: snapshot creation, listing and download.
- Create `app/src/main/java/dev/mrlptrc/notes/export/NoteImportService.java`: validation and resumable import.
- Create `app/src/main/java/dev/mrlptrc/notes/export/ExportController.java`: `/exports` API routes.
- Create `app/src/main/java/dev/mrlptrc/notes/export/ImportController.java`: `/imports` API route.
- Modify `app/src/main/resources/application.properties`: safe local S3 defaults.
- Modify `compose.yaml`: S3Mock service and named volume.
- Modify `k8s/s3mock.yaml`: bucket and persistent hostPath for the single-node kind lab.
- Modify `k8s/api-config.yaml`: local S3 endpoint and bucket settings.
- Modify `scripts/smoke-test-s3mock.sh` to confirm an object survives S3Mock Pod replacement.
- Create `app/src/test/java/dev/mrlptrc/notes/storage/S3ObjectStoreTests.java`.
- Create `app/src/test/java/dev/mrlptrc/notes/export/NoteExportServiceTests.java`.
- Create `app/src/test/java/dev/mrlptrc/notes/export/NoteImportServiceTests.java`.
- Extend `app/src/test/java/dev/mrlptrc/notes/NotesApiApplicationTests.java` for HTTP contracts.
- Create `scripts/smoke-test-note-export.sh` for API export/download/import.
- Modify `docs/executando.md` and `README.md` with local setup and endpoints.

### Task 1: Run S3Mock with persistent local storage

**Interfaces:** Provides bucket `notes-local` at `http://s3mock:9090` inside Compose and kind, with object data surviving container/Pod restart.

- [ ] Add an S3Mock 5.2.3 service to Compose, configure `notes-local` and `COM_ADOBE_TESTING_S3MOCK_STORE_ROOT=/data`, and mount a named volume at `/data`.
- [ ] Configure `k8s/s3mock.yaml` with the same bucket and storage root; mount a single-node kind `hostPath` volume at `/data` using `DirectoryOrCreate`.
- [ ] Extend `scripts/smoke-test-s3mock.sh` to upload an object, delete the S3Mock Pod, wait for readiness, then download and compare the same object.
- [ ] Add API ConfigMap values `APP_S3_ENDPOINT=http://s3mock:9090` and `APP_S3_BUCKET=notes-local`.
- [ ] Run `docker compose config`; expected output validates the Compose model.
- [ ] Apply the S3Mock manifest, upload a test object, delete the Pod, wait for readiness, and verify the object is still present.
- [ ] Commit Task 1 as `feat: persist local s3mock objects`.

### Task 2: Add the S3 object adapter

**Files:** `pom.xml`, new `storage/S3Config.java`, `storage/ObjectStore.java`, `storage/S3ObjectStore.java`, `application.properties`, new adapter tests.

**Interfaces:** `ObjectStore.put(String key, byte[] bytes, String contentType)`, `ObjectStore.get(String key) -> StoredObject`, `ObjectStore.list(String prefix) -> List<StoredObjectSummary>`. Define `StoredObject(byte[] bytes, String contentType)` and `StoredObjectSummary(String key, Instant lastModified, long size)` as nested records in `ObjectStore`.

- [ ] Write `S3ObjectStoreTests` for put/get/list against an S3Mock Testcontainer using a unique test bucket and dummy credentials.
- [ ] Run `cd app && ./mvnw -Dtest=S3ObjectStoreTests test`; expected initial failure because the adapter is not implemented.
- [ ] Add `software.amazon.awssdk:s3` to `app/pom.xml`; configure `S3Client` with endpoint override, `Region`, and static dummy credentials.
- [ ] Implement the `ObjectStore` methods with the AWS SDK v2 and map SDK results to storage DTOs.
- [ ] Configure `app.s3.endpoint=http://localhost:9090`, `app.s3.bucket=notes-local`, and `app.s3.region=us-east-1` defaults; Compose/kind override the endpoint.
- [ ] Rerun `cd app && ./mvnw -Dtest=S3ObjectStoreTests test`; expected PASS.
- [ ] Commit Task 2 as `feat: add local s3 object storage adapter`.

### Task 3: Export and download snapshots

**Files:** new DTOs and service/controller under `export/`, focused service tests, application integration tests.

**Interfaces:**
- `NoteExportService.createExport() -> ExportDescriptor`
- `NoteExportService.listExports() -> List<ExportDescriptor>`
- `NoteExportService.downloadExport(String exportId) -> NotesSnapshot`
- `NotesSnapshot(int schemaVersion, String exportId, Instant generatedAt, List<NoteSnapshot> notes)`
- `POST /exports`, `GET /exports`, `GET /exports/{exportId}`

- [ ] Write tests proving export stores all note fields with schema version `1`, generated UUID, and key `exports/{exportId}.json`; list and download return stored snapshots.
- [ ] Run the focused export tests and confirm failure before implementation.
- [ ] Implement `NoteExportService` using `NoteRepository.list()` and `ObjectStore`; accept only UUID export IDs on download.
- [ ] Implement `ExportController`: POST returns `201` and descriptor; GET list returns descriptors; GET by ID returns `application/json` with attachment filename `notes-{exportId}.json`; missing IDs return `404`.
- [ ] Rerun focused export tests and the affected application integration test; expected PASS.
- [ ] Commit Task 3 as `feat: export notes to s3mock`.

### Task 4: Import snapshots with explicit retry behavior

**Files:** new `ImportReport`, `NoteImportService`, `ImportController`, focused service tests, application integration tests.

**Interfaces:**
- `NoteImportService.importExport(String exportId) -> ImportReport`
- `ImportReport(int imported, int skipped, List<String> conflictingIds, List<String> failedIds)`
- `POST /imports/{exportId}`

- [ ] Write tests for valid import, repeated identical import, conflicting ID, unsupported `schemaVersion`, malformed object, missing export, and storage failure partway through an import.
- [ ] Run focused import tests and confirm failure before implementation.
- [ ] Validate the full snapshot schema and each note before writes; process notes idempotently: insert missing IDs, skip identical records, collect conflicting IDs without overwriting them, and stop/report failed writes by ID.
- [ ] Return `200` with explicit counts and IDs; use `404` for missing export and `422` for invalid schema. Do not report full success when conflicts or failed IDs exist.
- [ ] Rerun focused import tests and `cd app && ./mvnw test`; expected PASS.
- [ ] Commit Task 4 as `feat: restore notes from s3 exports`.

### Task 5: Document and smoke-test the end-to-end flow

**Files:** `scripts/smoke-test-s3mock.sh`, `docs/executando.md`, `README.md`.

- [ ] Create `scripts/smoke-test-note-export.sh` to create a note, export it, download the JSON, delete the source note, import the export, verify restoration, and clean up temporary test data/objects where safe.
- [ ] Document Compose and kind configuration, the three export routes, the import route, JSON schema, S3Mock persistence, and the fact that data remains local.
- [ ] Run `cd app && ./mvnw spotless:check test`, `docker compose config`, the API/S3Mock smoke test, and `kubectl apply --dry-run=client -f k8s/`.
- [ ] Confirm tests use only DynamoDB Local and S3Mock; expected no AWS credentials or calls.
- [ ] Commit Task 5 as `docs: document local note export and import`.
