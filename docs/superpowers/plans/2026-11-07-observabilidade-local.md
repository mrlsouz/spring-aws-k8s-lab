# Local Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional local Prometheus and Grafana monitoring for the Notes API, Kubernetes workloads, and DynamoDB operations invoked by the API.

**Architecture:** Run Prometheus, Grafana, and kube-state-metrics inside the existing `kind` cluster. Prometheus scrapes the existing Actuator endpoint and kube-state-metrics; a small application repository layer records DynamoDB operation count, duration, and result using Micrometer. Grafana provisions Prometheus and one dashboard.

**Tech Stack:** Prometheus, Grafana, kube-state-metrics, Kubernetes manifests, Spring Boot Actuator, Micrometer, Maven.

**Spec:** `docs/superpowers/specs/2026-11-07-observabilidade-local-design.md`

## Global Constraints

- The stack is local and creates or accesses no AWS resources.
- The stack is optional and removable without affecting API, DynamoDB Local, or S3Mock.
- Metrics data may be ephemeral when the cluster is recreated.
- Keep metrics labels low-cardinality; never label by note ID, content, or request-specific data.
- Grafana is reachable only through `kubectl port-forward`; do not add Ingress or LoadBalancer.
- Monitor Kubernetes workload state in the first version; detailed node CPU/memory is out of scope.
- DynamoDB operation metrics represent calls made by the API, not internal DynamoDB Local statistics.

## Review Focus

- DynamoDB operation failures must increment failure metrics and still propagate to the API caller; cover with a repository unit test.
- Empty note listings must be recorded as successful operations; cover with a repository unit test.
- Dashboard queries must match the exact metric names and labels emitted by the app; validate against the running Prometheus target after deployment.
- A missing or restarting API Pod must appear as unavailable in Kubernetes state metrics; validate with a disposable local replica change and restore it.
- The monitoring stack must be removable without removing app or database resources; validate with namespace-scoped resource inspection before and after deleting only observability manifests.

---

## File Structure

- Create `app/src/main/java/dev/mrlptrc/notes/NoteRepository.java`: encapsulate note table operations and publish low-cardinality DynamoDB timers.
- Modify `app/src/main/java/dev/mrlptrc/notes/NoteController.java`: delegate persistence operations to `NoteRepository`.
- Create `app/src/test/java/dev/mrlptrc/notes/NoteRepositoryTests.java`: test success and failure metric behavior.
- Create `k8s/observability/namespace.yaml`: isolate monitoring resources in namespace `observability`.
- Create `k8s/observability/kube-state-metrics.yaml`: run kube-state-metrics with read-only access to required workload objects.
- Create `k8s/observability/prometheus.yaml`: run Prometheus, expose its service internally, and configure static scrape targets for the API and kube-state-metrics.
- Create `k8s/observability/grafana.yaml`: run Grafana with provisioned Prometheus datasource and dashboard ConfigMaps.
- Create `k8s/observability/dashboard.json`: show API request/error/latency, DynamoDB operation count/latency/errors, Deployment availability, and Pod restarts.
- Create `k8s/observability/kustomization.yaml`: compose the stack and generate the dashboard ConfigMap from `dashboard.json`.
- Modify `docs/executando.md`: document apply, access, what is and is not measured, and removal.
- Modify `README.md`: link to the observability instructions and mark the optional local monitoring exercise.
- Modify `.github/workflows/ci.yml`: validate top-level Kubernetes manifests and the Kustomize observability stack separately.

## Task 1: Record DynamoDB operation metrics

**Files:**
- Create: `app/src/main/java/dev/mrlptrc/notes/NoteRepository.java`
- Modify: `app/src/main/java/dev/mrlptrc/notes/NoteController.java`
- Test: `app/src/test/java/dev/mrlptrc/notes/NoteRepositoryTests.java`

**Interfaces:**
- Produces `NoteRepository(MeterRegistry, DynamoDbTable<Note>)` with `create(Note)`, `list()`, `get(String)`, and `delete(String)` methods.
- Each method records a `Timer` named `notes.dynamodb.operation` tagged with `operation` (`put`, `scan`, `get`, `delete`) and `result` (`success`, `failure`).
- Timer records duration and count. Exceptions propagate unchanged after recording `failure`.

- [ ] Write repository tests asserting successful calls record count/duration with the exact operation and `result=success` tags.
- [ ] Write failure test asserting an SDK exception is propagated and recorded with `result=failure`.
- [ ] Write empty scan test asserting it records a successful operation with count 1.
- [ ] Run focused tests and confirm they fail because `NoteRepository` does not exist yet.
- [ ] Implement the repository with `Timer.Sample` and `MeterRegistry`, recording the result in `finally` while preserving exceptions.
- [ ] Change `NoteController` to delegate to the repository and preserve existing HTTP behavior.
- [ ] Run `cd app && ./mvnw -B -Dtest=NoteRepositoryTests,NotesApiApplicationTests test`; expect all focused tests to pass.

## Task 2: Add the Kubernetes metrics collector

**Files:**
- Create: `k8s/observability/namespace.yaml`
- Create: `k8s/observability/kube-state-metrics.yaml`

**Interfaces:**
- Exposes a `kube-state-metrics` Service on port 8080 in namespace `observability`.
- Limits collection to namespace `lab` and grants read-only access only to Pods, Deployments, and ReplicaSets needed for the dashboard.

- [ ] Add namespace, pinned kube-state-metrics image, ServiceAccount, namespace Role/RoleBinding, Deployment, and Service.
- [ ] Validate manifests with `kubectl apply --dry-run=client -k k8s/observability/`; expect no validation errors.
- [ ] Check the RBAC rules contain only get/list/watch and only the required resource kinds.

## Task 3: Add Prometheus and Grafana

**Files:**
- Create: `k8s/observability/prometheus.yaml`
- Create: `k8s/observability/grafana.yaml`
- Create: `k8s/observability/dashboard.json`

**Interfaces:**
- Prometheus service DNS is `prometheus.observability.svc.cluster.local:9090`.
- Grafana service DNS is `grafana.observability.svc.cluster.local:3000`.
- Prometheus scrapes `notes-api.lab.svc.cluster.local:8080/actuator/prometheus` and `kube-state-metrics.observability.svc.cluster.local:8080/metrics`.
- Grafana provisions Prometheus as its default datasource and loads the local dashboard from a ConfigMap.

- [ ] Add Prometheus ConfigMap and Deployment with the two scrape jobs, short local retention, modest CPU/memory requests, and no public Service.
- [ ] Add Grafana datasource/dashboard provisioning, pinned image, modest resource requests, and ClusterIP Service.
- [ ] Add dashboard panels using the API and Kubernetes metric names provided by the configured exporters; include `notes_dynamodb_operation_seconds_count` and corresponding duration/error views.
- [ ] Validate all resources using `kubectl apply --dry-run=client -k k8s/observability/`; expect no validation errors.
- [ ] Validate `dashboard.json` as JSON and ensure datasource UID and query metric names match the provisioning files and Task 1 interface.

## Task 4: Document local operation and verify end to end

**Files:**
- Modify: `docs/executando.md`
- Modify: `README.md`

- [ ] Document optional installation with `kubectl apply -k k8s/observability/`, rollout status commands, Grafana port-forward on local port 3000, and dashboard use.
- [ ] Document removal using only `kubectl delete -k k8s/observability/`; make clear the lab namespace and app resources stay in place.
- [ ] Document that DynamoDB panels measure API calls and that internal DynamoDB Local statistics, logs, traces, and node resource metrics are not included.
- [ ] Run applicable repository checks: `cd app && ./mvnw -B spotless:check verify`, `git diff --check`, and `kubectl apply --dry-run=client -k k8s/observability/`.
- [ ] Update CI manifest validation so it does not treat `dashboard.json` or `kustomization.yaml` as standalone Kubernetes resources; server-dry-run both the existing manifests and the observability Kustomization.
- [ ] In a local `kind` cluster, apply the observability manifests, wait for all monitoring Pods, port-forward Grafana, and verify Prometheus targets are up and dashboard panels return data after API requests.
- [ ] Delete only `k8s/observability/` resources and confirm API and DynamoDB Local remain running.
