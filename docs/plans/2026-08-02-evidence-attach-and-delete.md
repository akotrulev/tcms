# Evidence attach and delete — Implementation Plan

**Date:** 2026-08-02 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` (inline, the default) to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the `evidence` and `attachment` tables — both in `V1` since the beginning, both with
zero writers — their first writer, so a tester can attach a screenshot to a result and remove one
they attached themselves.

**Architecture:** A byte port (`EvidenceStore`) with a filesystem adapter, per open question #28
(§51: *storage port, filesystem adapter now, S3 later*). Because the adapter cannot issue a signed
URL, **uploads proxy through the API** and therefore need an explicit request-size limit — Spring's
default is 1 MB and nothing in `application.yml` currently sets it. Three operations: attach
(`evidence:attach`), delete (`evidence:delete`, **`OWN`-qualified**, the consumer §66 said was
missing), and a content endpoint, because `Evidence.url` is a required field and without a route
behind it the value is a lie.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Hibernate 6 · Flyway (head `V19`, **no migration in
this slice** — both tables exist) · Testcontainers Postgres 17.

---

## Decisions locked in before any code

These are the choices a reader will otherwise re-litigate. Each is written into the code it governs,
in this project's register (the comment carries the failure mode, not the mechanic).

### D1 — The port lives in `execution`, not in a new `storage` module

`ModuleStructureTest` hard-codes both the module list and the rule that **cross-module FKs and
declared dependencies point only at `tenancy`, `identity` or `project`** (plan §3). A `storage`
module owning `Attachment` would need plan §3 amended and two architecture tests changed, to serve
**one** caller.

So `EvidenceStore` (port), `FilesystemEvidenceStore` (adapter) and both entities live in
`execution`. **The cost, stated:** when `documentation` or `repository` needs attachments, the port
and `Attachment` get promoted to a shared module *then* — with two real callers to design against
rather than one imagined one. `attachment.owner_type` already spans six values, so the table is
built for that day; this slice writes only `EXECUTION` and `STEP_RESULT`, and does so through named
factories so no call site ever spells an owner type as free text.

### D2 — The storage key is server-generated and never contains the filename

Path traversal is the whole risk of a filesystem adapter. The key is
`<companyId>/<executionId>/<random uuid>` — every segment a UUID this server produced. The client's
filename is stored in `attachment.filename` (a **column**, not a path) and returned in
`Content-Disposition`. A filename of `../../../etc/passwd` therefore lands in a text column and
never touches the filesystem. `FilesystemEvidenceStore` additionally re-checks that the resolved
path stays under the configured root, because a defence that exists only in the key's construction
is one refactor from being absent.

### D3 — No run-state gate, deliberately

`V1`'s comment on `evidence.step_result_id` says evidence is *"immutable once the run closes"*.
**Not implemented here, on purpose.** `RunStatus` has no `CLOSED` value (`NOT_STARTED ·
IN_PROGRESS · COMPLETED · ABORTED`), `run:close` has no implementation, and — decisively —
`ExecutionService.record` does **not** refuse to amend a result on a `COMPLETED` run. Gating
evidence alone would make the screenshot stricter than the verdict it documents: you could change
the result and not attach the proof. The rule belongs with `run:close` when that lands, as one
invariant over both writes, and this plan records that rather than half-applying it.

### D4 — `thumbnailUrl` stays null

Nullable in the contract already. No image processing in this slice; a null is the true answer and
the SPA already treats it as optional.

---

## File structure

| Path | Responsibility |
|---|---|
| `execution/EvidenceStore.java` | **Port.** put / open / delete bytes by key. Knows nothing about JPA, HTTP or companies beyond the key it is handed. |
| `execution/FilesystemEvidenceStore.java` | **Adapter.** The port over a configured root directory. The only class that touches a `Path`. |
| `execution/StoredBlob.java` | What `open` returns: an `InputStream` + `long size`. |
| `execution/Attachment.java` | Entity over `V1`'s `attachment`. Named factories per owner type. |
| `execution/AttachmentRepository.java` | Spring Data, `@Transactional(readOnly = true)`. |
| `execution/Evidence.java` | Entity over `V1`'s `evidence`. |
| `execution/EvidenceRepository.java` | Spring Data, `@Transactional(readOnly = true)`. |
| `execution/EvidenceService.java` | Attach · delete · read-for-download. The three gates. |
| `execution/EvidenceUpload.java` | Validated upload input (filename, content type, size, bytes, caption, stepId). |
| `execution/EvidenceViews.java` | Batch renderer: evidence rows → `EvidenceView`, people resolved through `ExecutionPeople`. |
| `execution/EvidenceController.java` | The three routes under the existing run path. |
| `execution/EvidenceView.java` *(modify)* | Delete the "always empty" header; it stops being true. |
| `execution/ExecutionViews.java` *(modify)* | Populate execution-level and step-level evidence. |
| `execution/TestExecutionView.java` *(modify)* | `of` takes the evidence list instead of hard-coding `List.of()`. |
| `execution/StepResultView.java` *(modify)* | Same, for the step-level list. |
| `execution/ExecutionService.java` *(modify)* | Pass evidence through `detail`. |
| `application.yml` *(modify)* | `spring.servlet.multipart` limits + `tcms.storage.evidence.root`. |
| `docs/specs/openapi.yaml` *(modify)* | Three operations + `EvidenceUploadLimits` note. |

**Tests** (alongside code this agent wrote, per the agent's own rule — the wider suite is
`qa-api-java`'s):

| Path | Covers |
|---|---|
| `test/.../execution/EvidenceEndpointTest.java` | The three routes end to end, both refusals, the `OWN` arm, the null-author arm. |
| `test/.../execution/FilesystemEvidenceStoreTest.java` | Round-trip, delete, and the traversal refusal. |

**Fixture slug/UUID prefix:** `ev-` / UUID prefix `e5`. §19's collision defect has landed **five
times**; check with
`grep -rlE "'e5[0-9a-f]{6}-" backend/src/test` before using it and pick another if taken.

---

## Task 1 — The byte port and its filesystem adapter

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/EvidenceStore.java`
- Create: `backend/src/main/java/com/tcms/execution/StoredBlob.java`
- Create: `backend/src/main/java/com/tcms/execution/FilesystemEvidenceStore.java`
- Test: `backend/src/test/java/com/tcms/execution/FilesystemEvidenceStoreTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The adapter is the only class in the product that turns a caller's bytes into a file, so its
 * refusals are the ones a traversal attempt meets. No Spring context and no database: it is a
 * function from a key to a path, and testing it through the endpoint would hide which half failed.
 */
class FilesystemEvidenceStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("bytes written under a key come back byte-identical, and delete removes them")
    void roundTrips() throws IOException {
        FilesystemEvidenceStore store = new FilesystemEvidenceStore(root.toString());
        String key = UUID.randomUUID() + "/" + UUID.randomUUID() + "/" + UUID.randomUUID();
        byte[] bytes = "a screenshot".getBytes(StandardCharsets.UTF_8);

        store.put(key, bytes);

        try (StoredBlob blob = store.open(key)) {
            assertThat(blob.size()).isEqualTo(bytes.length);
            assertThat(blob.stream().readAllBytes()).isEqualTo(bytes);
        }

        store.delete(key);
        assertThat(store.exists(key)).isFalse();
    }

    @Test
    @DisplayName("a key climbing out of the root is refused rather than written outside it")
    void refusesTraversal() {
        FilesystemEvidenceStore store = new FilesystemEvidenceStore(root.toString());

        // Keys are server-generated UUIDs, so this is unreachable through the API today. It is
        // asserted anyway because the guarantee must survive a future caller that builds a key
        // from something a client supplied — the defence is here, not at the call site.
        assertThatThrownBy(() -> store.put("../escaped", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("opening a key nothing was written under is absent, not an empty file")
    void missingKeyIsAbsent() {
        FilesystemEvidenceStore store = new FilesystemEvidenceStore(root.toString());
        assertThat(store.exists(UUID.randomUUID().toString())).isFalse();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.FilesystemEvidenceStoreTest'`
Expected: FAIL — `EvidenceStore`, `StoredBlob`, `FilesystemEvidenceStore` do not exist
(compilation error).

- [ ] **Step 3: Write `StoredBlob`**

```java
package com.tcms.execution;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bytes on their way out, with the length the response needs in front of them.
 *
 * <p>A stream rather than a {@code byte[]}: a download must not require the whole file in heap, and
 * an evidence file is the one thing a caller uploads whose size the product does not choose. The
 * size travels alongside because {@code Content-Length} has to be set before the first byte is
 * written, and asking the stream would consume it.
 */
public record StoredBlob(InputStream stream, long size) implements Closeable {

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
```

- [ ] **Step 4: Write the port**

```java
package com.tcms.execution;

/**
 * Where evidence bytes live — <strong>the port</strong>, so the S3 adapter is additive (open
 * question #28, resolved in §51: filesystem now, S3 later).
 *
 * <p>The interface is deliberately four methods over an opaque {@code String} key and nothing else.
 * It has no notion of a company, an execution or an attachment row: everything that decides
 * <em>which</em> bytes a caller may reach is a database question answered before this is called, and
 * an implementation that could be asked "list this company's files" would be one refactor away from
 * being the place that decides. Keys are built by {@link EvidenceService}, which is the only class
 * that knows what a key means.
 *
 * <p><strong>Not transactional, and cannot be.</strong> A filesystem does not roll back with the
 * transaction that wrote the row. {@link EvidenceService} therefore writes the row first and the
 * bytes second, so the failure mode is a row whose blob is missing (a download 404s) rather than a
 * blob no row names (an orphan nothing will ever collect).
 */
public interface EvidenceStore {

    /** Writes {@code bytes} under {@code key}, replacing anything already there. */
    void put(String key, byte[] bytes);

    /** The bytes, or empty when the key names nothing. The caller closes it. */
    java.util.Optional<StoredBlob> open(String key);

    /** Removes the bytes. A key naming nothing is not an error — delete is idempotent. */
    void delete(String key);

    /** Whether anything is stored under {@code key}. */
    boolean exists(String key);
}
```

- [ ] **Step 5: Write the adapter**

```java
package com.tcms.execution;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link EvidenceStore} over a directory on the box — the adapter open question #28 chose, and the
 * reason evidence uploads proxy through the API instead of going browser-direct to a signed URL
 * (§51: a filesystem cannot issue one).
 *
 * <p><strong>The root directory is stateful and backup-critical.</strong> Losing it loses every
 * screenshot behind every failed result, and no migration will bring them back — the Hetzner runbook
 * has to cover it. That is the price of the single-deployable story, and it was stated when the
 * option was chosen rather than discovered afterwards.
 *
 * <h2>The traversal check is here as well as in the key</h2>
 *
 * <p>Keys are server-generated UUID triples ({@link EvidenceService}), so nothing a client sends
 * reaches a path today. The resolved path is re-checked against the root anyway: a guarantee that
 * lives only in how the caller builds its argument is one refactor from being gone, and the failure
 * it prevents — writing outside the root — is not one a test of the endpoint would ever show.
 */
@Component
class FilesystemEvidenceStore implements EvidenceStore {

    private final Path root;

    FilesystemEvidenceStore(@Value("${tcms.storage.evidence.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not store evidence under " + key, failure);
        }
    }

    @Override
    public Optional<StoredBlob> open(String key) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            long size = Files.size(source);
            InputStream stream = Files.newInputStream(source);
            return Optional.of(new StoredBlob(stream, size));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read evidence under " + key, failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            // deleteIfExists, not delete: the row is gone by the time this runs, and a second
            // delete of the same evidence must not answer 500 for bytes that are already absent.
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not delete evidence under " + key, failure);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Evidence key resolves outside the storage root");
        }
        return resolved;
    }
}
```

- [ ] **Step 6: Add the property so the bean can construct**

Modify `backend/src/main/resources/application.yml` — add under the existing `tcms:` block (create
the block if absent, at the same indentation as `spring:`):

```yaml
tcms:
  storage:
    evidence:
      # Where evidence bytes land (open question #28 — filesystem adapter, S3 later). STATEFUL AND
      # BACKUP-CRITICAL: losing this directory loses every screenshot behind every failed result,
      # and no migration restores them. Overridden per environment; the default is deliberately
      # inside the container's writable area rather than /tmp, which some images clear on restart.
      root: ${TCMS_EVIDENCE_ROOT:/var/lib/tcms/evidence}
```

And add the upload limits under `spring:` (this is the request-size limit §51 said the
filesystem adapter would force — Spring's defaults are 1 MB file / 10 MB request, which would
refuse an ordinary screenshot):

```yaml
  servlet:
    multipart:
      # Evidence proxies through the API because a filesystem adapter cannot sign a URL (§51), so
      # THIS is the only thing standing between one caller and the disk the whole instance shares.
      # 10 MB holds a full-page screenshot or a log excerpt and refuses a video, which is the line
      # the product wants until per-project quota (#28's other half) exists.
      max-file-size: 10MB
      max-request-size: 12MB
```

- [ ] **Step 7: Point the test's own context at a temp root**

Modify `backend/src/test/resources/application.yml` (or add the property to `IntegrationTestBase`'s
property source if that file does not exist — check first with
`ls backend/src/test/resources/application*.yml`):

```yaml
tcms:
  storage:
    evidence:
      root: ${java.io.tmpdir}/tcms-evidence-test
```

- [ ] **Step 8: Run the test and watch it pass**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.FilesystemEvidenceStoreTest'`
Expected: PASS, 3 tests.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/tcms/execution/EvidenceStore.java \
        backend/src/main/java/com/tcms/execution/StoredBlob.java \
        backend/src/main/java/com/tcms/execution/FilesystemEvidenceStore.java \
        backend/src/test/java/com/tcms/execution/FilesystemEvidenceStoreTest.java \
        backend/src/main/resources/application.yml \
        backend/src/test/resources/application.yml
git commit -m "the evidence byte port, and the filesystem adapter behind it"
```

---

## Task 2 — The two entities

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/Attachment.java`
- Create: `backend/src/main/java/com/tcms/execution/AttachmentRepository.java`
- Create: `backend/src/main/java/com/tcms/execution/Evidence.java`
- Create: `backend/src/main/java/com/tcms/execution/EvidenceRepository.java`

> **No migration.** Both tables are in `V1` (`attachment` at line 675, `evidence` at line 885).
> `hibernate.ddl-auto: validate` means a column name that does not match fails the context boot, so
> booting any integration test is itself the check on this task.

- [ ] **Step 1: Write `Attachment`**

Match `V1`'s columns exactly: `id · company_id · owner_type · owner_id · storage_key · filename ·
content_type · size_bytes · checksum · created_at · created_by`.

```java
package com.tcms.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored blob's metadata row — {@code V1}'s polymorphic {@code attachment} table, getting its
 * first writer here.
 *
 * <h2>Why this entity is in {@code execution} and not in a module of its own</h2>
 *
 * <p>{@code owner_type} spans six values and only two of them are this module's. The table is built
 * to serve doc pages and artifacts too, and {@code V1}'s own comment says why it is one table:
 * <em>six near-identical attachment tables is the worse trade</em>. A {@code storage} module owning
 * it would be the tidier home — and would need plan §3 amended and two architecture tests changed
 * (`ModuleStructureTest` hard-codes both the module list and the tenancy/identity/project FK rule),
 * to serve exactly one caller. So it lives here until there is a second caller to design against.
 *
 * <p>What keeps that honest is {@link #forExecution} and {@link #forStepResult}: no call site ever
 * spells an owner type, so the day this moves, the writers that must move with it are the ones this
 * file names.
 */
@Entity
@Table(name = "attachment")
public class Attachment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "owner_type", nullable = false, updatable = false)
    private String ownerType;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "filename", nullable = false, updatable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "checksum", updatable = false)
    private String checksum;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected Attachment() {
        // JPA.
    }

    /** Evidence attached to the execution as a whole. */
    static Attachment forExecution(
            UUID companyId,
            UUID executionId,
            String storageKey,
            EvidenceUpload upload,
            UUID userId,
            Instant now) {

        return create(companyId, "EXECUTION", executionId, storageKey, upload, userId, now);
    }

    /** Evidence attached to one step's result. */
    static Attachment forStepResult(
            UUID companyId,
            UUID stepResultId,
            String storageKey,
            EvidenceUpload upload,
            UUID userId,
            Instant now) {

        return create(companyId, "STEP_RESULT", stepResultId, storageKey, upload, userId, now);
    }

    private static Attachment create(
            UUID companyId,
            String ownerType,
            UUID ownerId,
            String storageKey,
            EvidenceUpload upload,
            UUID userId,
            Instant now) {

        Attachment attachment = new Attachment();
        attachment.id = UUID.randomUUID();
        attachment.companyId = companyId;
        attachment.ownerType = ownerType;
        attachment.ownerId = ownerId;
        attachment.storageKey = storageKey;
        attachment.filename = upload.filename();
        attachment.contentType = upload.contentType();
        attachment.sizeBytes = upload.sizeBytes();
        attachment.checksum = upload.checksum();
        attachment.createdAt = now;
        attachment.createdBy = userId;
        return attachment;
    }

    UUID getId() {
        return id;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getFilename() {
        return filename;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }
}
```

- [ ] **Step 2: Write `Evidence`**

Columns from `V1`: `id · company_id · execution_id · step_result_id · attachment_id · caption ·
created_at · created_by`.

```java
package com.tcms.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One screenshot or log attached to a result — {@code V1}'s {@code evidence} table, first writer.
 *
 * <p>{@code step_result_id} is nullable because evidence attaches at either level, and the two are
 * genuinely different claims: a file on the execution documents the verdict, a file on a step
 * documents where it went wrong. {@code V1}'s comment adds that it is immutable once the run closes
 * — <strong>not enforced in this slice, deliberately</strong>: there is no {@code CLOSED} run status
 * and {@code ExecutionService.record} amends results on a {@code COMPLETED} run, so gating evidence
 * alone would make the screenshot stricter than the verdict it documents. That invariant belongs
 * with {@code run:close}, over both writes at once.
 *
 * <p>{@code created_by} is what the {@code OWN} qualifier on {@code evidence:delete} resolves
 * against (roles spec §A5) — the same column, and the same nullable arm, as
 * {@code test_case.created_by} in {@code repository.CaseDeleteService}.
 */
@Entity
@Table(name = "evidence")
public class Evidence {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "step_result_id", updatable = false)
    private UUID stepResultId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "caption")
    private String caption;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected Evidence() {
        // JPA.
    }

    static Evidence attach(
            UUID companyId,
            UUID executionId,
            UUID stepResultId,
            UUID attachmentId,
            String caption,
            UUID userId,
            Instant now) {

        Evidence evidence = new Evidence();
        evidence.id = UUID.randomUUID();
        evidence.companyId = companyId;
        evidence.executionId = executionId;
        evidence.stepResultId = stepResultId;
        evidence.attachmentId = attachmentId;
        evidence.caption = caption;
        evidence.createdAt = now;
        evidence.createdBy = userId;
        return evidence;
    }

    UUID getId() {
        return id;
    }

    UUID getExecutionId() {
        return executionId;
    }

    UUID getStepResultId() {
        return stepResultId;
    }

    UUID getAttachmentId() {
        return attachmentId;
    }

    String getCaption() {
        return caption;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    /** Nullable — an unattributable row is not the caller's own work. See {@link AuthorshipGate}. */
    UUID getCreatedBy() {
        return createdBy;
    }
}
```

- [ ] **Step 3: Write both repositories**

**`@Transactional(readOnly = true)` on the interface is mandatory** — without it the derived methods
below run outside any transaction, `app.current_company` is never set, and every read returns zero
rows (fail-closed and silent). `RepositoryTransactionTest` derives its list from the live context, so
omitting it fails the build.

```java
package com.tcms.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** @see com.tcms.tenancy.CompanyAwareTransactionManager for why the annotation is not optional. */
@Transactional(readOnly = true)
public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {

    List<Evidence> findByExecutionIdIn(List<UUID> executionIds);

    List<Evidence> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    java.util.Optional<Evidence> findByIdAndExecutionId(UUID id, UUID executionId);
}
```

```java
package com.tcms.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** @see EvidenceRepository for why the annotation is not optional. */
@Transactional(readOnly = true)
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByIdIn(List<UUID> ids);
}
```

- [ ] **Step 4: Verify the mapping against the real schema**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.ExecutionEndpointTest'`
Expected: PASS. This proves nothing about evidence yet — it proves `ddl-auto: validate` accepted
both new entities against `V1`. A column-name typo fails **context startup**, so every test in the
class errors rather than one assertion failing; that is the signal to read.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/execution/Attachment.java \
        backend/src/main/java/com/tcms/execution/AttachmentRepository.java \
        backend/src/main/java/com/tcms/execution/Evidence.java \
        backend/src/main/java/com/tcms/execution/EvidenceRepository.java
git commit -m "the two evidence tables get their first entities, ten months after V1 declared them"
```

---

## Task 3 — The contract

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Regenerate: `frontend/src/api/schema.d.ts`

> `openapi.yaml` is the contract: change it there, regenerate, then adapt both sides. **Every schema
> gets a `required` array, including inline nested objects** — `required` does not descend, and
> without it every generated property is optional and no consumer can use it. **A path item must
> declare no query parameters at all** (§50) — they are inherited by every operation under it.

- [ ] **Step 1: Add the three operations**

Under `paths:`, after the existing
`/projects/{projectId}/runs/{runId}/executions/{executionId}` block. Note the path-level
`parameters` are **path** parameters only, never query — §50's rule.

```yaml
  /projects/{projectId}/runs/{runId}/executions/{executionId}/evidence:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: runId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: executionId, in: path, required: true, schema: { type: string, format: uuid } }
    post:
      operationId: attachEvidence
      summary: Attach a screenshot or log to a result
      description: |
        Requires `evidence:attach`. The upload **proxies through the API** rather than going
        browser-direct to a signed URL: the storage port's filesystem adapter cannot sign one
        (open question #28). `max-file-size` is therefore a real limit and a file over it is a
        **413**, not a 400 — the request never reaches the handler.
      tags: [execution]
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required: [file]
              properties:
                file:
                  type: string
                  format: binary
                  description: The bytes. Filename and content type travel in the part's headers.
                caption:
                  type: string
                  nullable: true
                  description: Optional one-line description shown under the thumbnail.
                stepId:
                  type: string
                  format: uuid
                  nullable: true
                  description: |
                    Attaches to that step's result rather than to the execution as a whole. Must be
                    a step of the **pinned** version and must already have a recorded result — a
                    step nobody has marked has no `step_result` row to hang evidence on.
      responses:
        '201':
          description: Attached.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Evidence' }
        '400': { $ref: '#/components/responses/ValidationProblem' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '413':
          description: The file is larger than the instance's upload limit.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /projects/{projectId}/runs/{runId}/executions/{executionId}/evidence/{evidenceId}:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: runId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: executionId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: evidenceId, in: path, required: true, schema: { type: string, format: uuid } }
    delete:
      operationId: deleteEvidence
      summary: Remove evidence
      description: |
        Requires `evidence:delete`, which is **`OWN`-qualified for Test Engineer** (roles spec §A5):
        they may remove evidence they attached and not a colleague's. The two refusals carry
        different sentences, because "you do not have permission" is false for a caller who removes
        their own attachments all day.
      tags: [execution]
      responses:
        '204': { description: Removed. }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /projects/{projectId}/runs/{runId}/executions/{executionId}/evidence/{evidenceId}/content:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: runId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: executionId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: evidenceId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      operationId: downloadEvidence
      summary: The evidence bytes
      description: |
        What `Evidence.url` points at. It is an ordinary session-authenticated API route rather than
        a public link, because the filesystem adapter cannot issue an expiring signed URL and a
        guessable permanent one would be an unauthenticated read of a customer's screenshots.
        Served `inline` with the stored content type so the SPA can render an image in place.
      tags: [execution]
      responses:
        '200':
          description: The bytes.
          content:
            application/octet-stream:
              schema: { type: string, format: binary }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [ ] **Step 2: Check the shared responses exist under the names used above**

Run: `grep -n "ValidationProblem:\|Unauthorized:\|Forbidden:\|NotFound:" docs/specs/openapi.yaml`
Expected: each appears once under `components.responses`. **If a name differs, use the existing
one** — do not add a duplicate response component.

- [ ] **Step 3: Regenerate the frontend types**

```bash
cd frontend && npm run gen:api
```

Expected: `src/api/schema.d.ts` changes; `git diff --stat` shows only that file.

- [ ] **Step 4: Run the contract-hygiene checks**

Run: `cd frontend && npm run test:run -- contract`
Expected: PASS. These are §48's and §50's computed checks — they will fail on a missing `required`
array, an undeclared 4xx, or a query parameter written on a path item.

- [ ] **Step 5: Commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts
git commit -m "the contract learns how evidence is attached, fetched and removed"
```

---

## Task 4 — Attach

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/EvidenceUpload.java`
- Create: `backend/src/main/java/com/tcms/execution/EvidenceService.java`
- Create: `backend/src/main/java/com/tcms/execution/EvidenceController.java`
- Test: `backend/src/test/java/com/tcms/execution/EvidenceEndpointTest.java`

- [ ] **Step 1: Write the failing test for the happy path and the plain refusal**

```java
    @Test
    @DisplayName("a tester attaches a screenshot to a result and it comes back on the execution")
    void attaches() {
        // POST multipart to .../evidence as a Test Engineer, 201.
        // Then GET the execution and assert the evidence array has one entry whose filename,
        // contentType and sizeBytes match what was sent, and whose createdBy is the caller.
    }

    @Test
    @DisplayName("a Viewer cannot attach evidence")
    void refusesWithoutThePermission() {
        // 403, and the execution's evidence array stays empty — asserted through a second GET,
        // because a refusal that still wrote the row is the failure this test exists for.
    }
```

Write these out fully against `EvidenceEndpointTest`'s harness, matching
`ExecutionEndpointTest`'s existing fixture style (own slug namespace `ev-`, own company).

- [ ] **Step 2: Run and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.EvidenceEndpointTest'`
Expected: FAIL — 404 on POST, the route does not exist.

- [ ] **Step 3: Write `EvidenceUpload`**

```java
package com.tcms.execution;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A validated upload, so nothing downstream re-decides what a filename or a content type is.
 *
 * <p>{@code filename} is sanitized to its last path segment on the way in. It is only ever stored
 * in a column and echoed in {@code Content-Disposition} — it never becomes part of a storage key
 * (see {@link EvidenceService}) — but a client-supplied string that *looks* like a path invites the
 * next reader to use it as one, and the cheapest place to make that impossible is here.
 */
record EvidenceUpload(
        String filename, String contentType, long sizeBytes, byte[] bytes, String caption) {

    static final long MAX_BYTES = 10L * 1024 * 1024;

    /** SHA-256 of the bytes, for {@code attachment.checksum} — corruption is otherwise silent. */
    String checksum() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "evidence";
        }
        String tail = raw.replace('\\', '/');
        tail = tail.substring(tail.lastIndexOf('/') + 1);
        return tail.isBlank() ? "evidence" : tail;
    }
}
```

- [ ] **Step 4: Write `EvidenceService.attach`**

The gate is `writeGranted` with `evidence:attach` (unqualified — every role but Viewer holds it,
roles spec line 173). The order is load-bearing and mirrors `ExecutionService`: resolve the
execution through **both** read gates first (project visible → run in project → execution in run),
so an unreachable target is a bodiless 404 before any permission is consulted.

```java
    @Transactional
    public Outcome attach(
            UUID userId, UUID projectId, UUID runId, UUID executionId, EvidenceUpload upload) {

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        Optional<TestExecution> found = resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestExecution execution = found.get();

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, ATTACH_EVIDENCE,
                project -> write(companyId, userId, execution, upload, now));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    private Outcome write(
            UUID companyId, UUID userId, TestExecution execution, EvidenceUpload upload,
            Instant now) {

        // The key is three server-generated UUIDs and nothing the client sent. The filename lives
        // in a column; if it ever reached this string, a caller would choose a path on our disk.
        String key = companyId + "/" + execution.getId() + "/" + UUID.randomUUID();

        Attachment attachment = Attachment.forExecution(
                companyId, execution.getId(), key, upload, userId, now);
        attachments.saveAndFlush(attachment);

        Evidence evidence = Evidence.attach(
                companyId, execution.getId(), null, attachment.getId(), upload.caption(), userId,
                now);
        evidenceRows.saveAndFlush(evidence);

        // Bytes AFTER the rows, and outside no transaction that can undo them. A filesystem does
        // not roll back: writing first would leave an orphan blob nothing names on any failure
        // after it, and an orphan is unreachable and uncollectable. This way the failure mode is a
        // row whose blob is missing, which the download answers 404 for and an operator can find.
        store.put(key, upload.bytes());

        return new Outcome.Attached(views.render(companyId, List.of(evidence)).getFirst());
    }
```

> **`stepId` is deliberately not implemented in this task.** It needs the `step_result` row to exist
> and be looked up, which is Task 7. The contract declares it; the handler must therefore **refuse**
> a supplied `stepId` with a 400 rather than silently ignoring it — an ignored parameter is exactly
> §50's defect class. Task 7 replaces the refusal with the behaviour.

- [ ] **Step 5: Write `EvidenceController.attach`**

```java
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> attach(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "stepId", required = false) UUID stepId) throws IOException {

        if (stepId != null) {
            return ResponseEntity.badRequest().body(validationProblem(Map.of(
                    "stepId", List.of("Step-level evidence is not available yet."))));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(validationProblem(Map.of(
                    "file", List.of("Choose a file to attach."))));
        }

        EvidenceUpload upload = new EvidenceUpload(
                EvidenceUpload.sanitizeFilename(file.getOriginalFilename()),
                file.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType(),
                file.getSize(),
                file.getBytes(),
                caption);

        EvidenceService.Outcome outcome = evidence.attach(
                UUID.fromString(jwt.getSubject()), projectId, runId, executionId, upload);

        return switch (outcome) { /* NotFound → 404, Forbidden → 403 with the sentence,
                                     Attached → 201 with the view */ };
    }
```

Write the switch out fully — it is exhaustive over the sealed `Outcome`, which is what makes a new
arm a compile error.

- [ ] **Step 6: Add `MaxUploadSizeExceededException` → 413**

Spring throws this **before** the handler runs, so it cannot be an `Outcome`. Add to
`EvidenceController`:

```java
    /**
     * A file over {@code spring.servlet.multipart.max-file-size}. Without this handler Spring
     * answers a bare 500 and the SPA reports "something went wrong" for a file the user could
     * simply have made smaller — the one upload failure that is entirely the caller's to fix.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> onTooLarge(MaxUploadSizeExceededException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle("File too large");
        problem.setDetail("Evidence files are limited to 10 MB. Attach a smaller file.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }
```

- [ ] **Step 7: Run and watch both tests pass**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.EvidenceEndpointTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/tcms/execution/Evidence*.java \
        backend/src/test/java/com/tcms/execution/EvidenceEndpointTest.java
git commit -m "evidence:attach gets its first consumer in Java"
```

---

## Task 5 — Read: evidence on the execution, and the content route

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/EvidenceViews.java`
- Modify: `execution/EvidenceView.java`, `ExecutionViews.java`, `TestExecutionView.java`,
  `ExecutionService.java`, `EvidenceController.java`

- [ ] **Step 1: Write `EvidenceViews`** — one batch renderer, people resolved through the existing
  `ExecutionPeople`, following `ExecutionViews`' "four bulk lookups, never per row" rule. Two
  queries for any number of rows: the attachments by id, the people by id.

```java
    List<EvidenceView> render(UUID companyId, List<Evidence> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Attachment> byId = attachments
                .findByIdIn(rows.stream().map(Evidence::getAttachmentId).toList())
                .stream().collect(Collectors.toMap(Attachment::getId, a -> a));

        Set<UUID> userIds = rows.stream()
                .map(Evidence::getCreatedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserRefView> people = this.people.resolve(companyId, userIds);

        List<EvidenceView> views = new ArrayList<>(rows.size());
        for (Evidence row : rows) {
            Attachment file = byId.get(row.getAttachmentId());
            if (file == null) {
                // The attachment row is gone while the evidence row is not. Skip rather than
                // render blanks: an entry with no filename, size or link is a broken thumbnail the
                // user can neither open nor remove.
                continue;
            }
            views.add(new EvidenceView(
                    row.getId(),
                    file.getFilename(),
                    file.getContentType(),
                    file.getSizeBytes(),
                    contentUrl(row),
                    null, // thumbnailUrl — no image processing in this slice; null is the truth.
                    row.getCaption(),
                    row.getCreatedBy() == null ? null : people.get(row.getCreatedBy()),
                    row.getCreatedAt()));
        }
        return List.copyOf(views);
    }
```

`contentUrl` builds the relative API path declared in Task 3 — one method, so the route is written
once and the client never assembles it.

- [ ] **Step 2: Thread it through the execution detail**

`TestExecutionView.of` takes `List<EvidenceView> evidence` instead of hard-coding `List.of()`;
delete the "Nothing uploads evidence yet" comment. `ExecutionService.detail` loads
`evidenceRows.findByExecutionIdOrderByCreatedAtAsc(...)` and renders it. **Remove
`EvidenceView`'s "Always an empty list in this slice" header** — it stops being true, and a stale
comment asserting the opposite of the code is worse than none.

- [ ] **Step 3: Write the content route**

```java
    @GetMapping("/{evidenceId}/content")
    public ResponseEntity<Resource> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @PathVariable UUID evidenceId) {

        return evidence.open(UUID.fromString(jwt.getSubject()), projectId, runId, executionId,
                        evidenceId)
                .map(download -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(download.contentType()))
                        .contentLength(download.blob().size())
                        // `inline` so the SPA can render an image in place, and the filename is
                        // quoted and stripped of quotes/newlines: it is the one client-supplied
                        // string that reaches a response HEADER, where a newline is a header
                        // injection rather than a bad filename.
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + safeHeaderFilename(download.filename()) + "\"")
                        .body((Resource) new InputStreamResource(download.blob().stream())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

`EvidenceService.open` uses **the read gate only** (`resolve`, exactly as `findOne` does) — anyone
who may see the execution may see its evidence; there is no separate `evidence:read` in the
catalogue. A key with no bytes behind it returns empty → 404, which is the orphan case Task 4's
write ordering deliberately produces instead of the reverse.

- [ ] **Step 4: Extend the test — the attached file downloads byte-identically**

```java
    @Test
    @DisplayName("the attached bytes come back through the url the execution advertises")
    void downloads() {
        // POST the file, read `url` off the returned Evidence, GET it, assert the bytes match and
        // the content type is the one that was uploaded. Asserting through the ADVERTISED url is
        // the point: a url the server builds and the test hard-codes would agree with itself.
    }
```

- [ ] **Step 5: Run, then commit**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.EvidenceEndpointTest'`

```bash
git add backend/src/main/java/com/tcms/execution/
git commit -m "evidence reaches the execution it documents, and the url resolves"
```

---

## Task 6 — Delete, and the `OWN` arm

**Files:**
- Modify: `execution/EvidenceService.java`, `EvidenceController.java`
- Test: `EvidenceEndpointTest`

This is the consumer §66 said was missing. It is `CaseDeleteService` one entity over — read that
file first; the shape, the two sentences and the ordering are all established there.

- [ ] **Step 1: Write the four failing tests**

The fourth is the one that is invisible in seeded data and the one most likely to be wrong.

```java
    @Test @DisplayName("a tester removes evidence they attached themselves")
    void deletesOwn() { /* 204, and a second GET shows the evidence array empty */ }

    @Test @DisplayName("a tester cannot remove a colleague's evidence, and is told why")
    void refusesSomeoneElses() {
        // 403 whose detail says they may only remove their own — NOT "you do not have permission",
        // which is false for a caller who removes their own attachments all day.
        // And the row survives: asserted by a GET, because a refusal that still deleted is the
        // failure this test exists for.
    }

    @Test @DisplayName("a lead removes anyone's evidence")
    void leadDeletesAnyones() {
        // Without this, the two above pass equally well against a page that refuses everybody.
    }

    @Test @DisplayName("evidence with no recorded author is not anybody's own work")
    void refusesUnattributedForAnOwnGrant() {
        // created_by IS NULL. An OWN grant must not stretch to work nobody can attribute.
        // Set the column directly — nothing the API offers can produce this row, which is exactly
        // why it is the arm no fixture happens to contain.
    }
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.EvidenceEndpointTest'`
Expected: FAIL — 404/405 on DELETE.

- [ ] **Step 3: Implement `delete` with `writeGrantedByAuthorship`**

```java
    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID runId, UUID executionId, UUID id) {
        Optional<TestExecution> found = resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestExecution execution = found.get();

        GateResult<Outcome> gated = projects.writeGrantedByAuthorship(
                userId, projectId, DELETE_EVIDENCE,
                (project, authorship) -> remove(execution, id, authorship));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome remove(TestExecution execution, UUID id, AuthorshipGate authorship) {
        Optional<Evidence> found = evidenceRows.findByIdAndExecutionId(id, execution.getId());
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Evidence evidence = found.get();

        // AFTER the lookup, because the answer depends on the row — and a caller who fails this
        // has already read the evidence, so 403 discloses nothing a 404 would conceal.
        if (!authorship.mayActOn(evidence.getCreatedBy())) {
            return new Outcome.NotTheAuthor();
        }

        Optional<Attachment> file = attachments.findById(evidence.getAttachmentId());
        evidenceRows.delete(evidence);
        file.ifPresent(attachments::delete);
        // Bytes last, mirroring the write: if this throws, the rows are already gone and what is
        // left is an unreferenced blob — recoverable by an operator sweep. Deleting bytes first
        // and failing on the row would leave a thumbnail that renders and never opens.
        file.ifPresent(f -> store.delete(f.getStorageKey()));

        return new Outcome.Deleted();
    }
```

> **Hard delete, not soft** — unlike a case. A screenshot removed on request must actually leave the
> disk; a soft-deleted row pointing at retained bytes is a deletion that did not delete, which is the
> wrong answer for the one thing in the product a customer may need removed for privacy reasons.

- [ ] **Step 4: Map the two refusals in the controller** — the same shape as
  `CaseController`'s delete, with evidence's own sentences:

```java
            case EvidenceService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to remove evidence in this project."));
            case EvidenceService.Outcome.NotTheAuthor ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You can only remove evidence you attached. Ask a test lead to remove "
                                    + "this one."));
```

- [ ] **Step 5: Run and watch all four pass**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.EvidenceEndpointTest'`

- [ ] **Step 6: Run the mutation that proves the `OWN` arm is load-bearing**

Temporarily replace `authorship.mayActOn(evidence.getCreatedBy())` with `true`.

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.EvidenceEndpointTest'`
Expected: **exactly 2 red** — `refusesSomeoneElses` and `refusesUnattributedForAnOwnGrant` — and
nothing else. Revert. **If `deletesOwn` or `leadDeletesAnyones` also goes red, the test is asserting
the gate rather than the authorship** and needs rewriting. Record the result in
`docs/verification/backend-mutation-checks.md`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/execution/ backend/src/test/java/com/tcms/execution/
git commit -m "evidence:delete reaches the row — the OWN consumer §66 said was missing"
```

---

## Task 7 — Step-level evidence

**Files:** `execution/EvidenceService.java`, `EvidenceViews.java`, `ExecutionViews.java`,
`EvidenceController.java`, `EvidenceEndpointTest`

Replaces Task 4's deliberate 400 on `stepId` with the behaviour the contract declares.

- [ ] **Step 1: Write the failing tests**

```java
    @Test @DisplayName("evidence attached to a step renders on that step and not on the execution")
    void attachesToAStep() { /* the execution-level array stays empty; the step's has one */ }

    @Test @DisplayName("a step with no recorded result cannot carry evidence")
    void refusesAStepWithNoResult() {
        // 400 on `stepId`. There is no step_result row to hang it on, and inventing one would
        // record a verdict nobody gave.
    }

    @Test @DisplayName("a step id from a different case is refused rather than stored")
    void refusesAForeignStep() {
        // §64's defect one table over: an id that satisfies the foreign key and belongs to
        // somebody else's case must be a field error, not a 500 and not a stored row.
    }
```

- [ ] **Step 2: Implement** — look the `step_result` row up by `(execution_id, step_id)` via
  `StepOutcomeRepository`, 400 on `stepId` when absent, then
  `Attachment.forStepResult(...)` and `Evidence.attach(..., stepResultId, ...)`.

- [ ] **Step 3: Render it** — `ExecutionViews.steps` currently passes `List.of()` as each step's
  evidence. Group the execution's evidence by `stepResultId` and look it up per step; the
  execution-level list becomes the rows whose `stepResultId` **is null**. One partition, no extra
  query.

- [ ] **Step 4: Run, mutate, commit**

Run: `cd backend && ./gradlew test --tests 'com.tcms.execution.EvidenceEndpointTest'`
Mutation: make the execution-level list return *all* rows rather than the null-step ones.
Expected: `attachesToAStep` goes red. Revert.

```bash
git add backend/src/main/java/com/tcms/execution/ backend/src/test/java/com/tcms/execution/
git commit -m "evidence lands on the step it documents"
```

---

## Task 8 — The backend gate

- [ ] **Step 1: Full build**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL, **0 failures, 0 skipped**, test count = 1206 + the new tests.

`-Werror` is on: an unused import or a non-exhaustive switch fails here and not in `test`.

- [ ] **Step 2: If the build hangs with no output**

Run: `find . -name "* 2*" -not -path "./.git/*"` — **note the missing trailing dot**, which is the
§47 correction: conflict-copy *directories* have no extension and the old pattern could not match
them. Also check for a second `gradle` process, which blocks on the build lock and looks identical.

- [ ] **Step 3: Commit**

```bash
git commit --allow-empty -m "backend gate green for the evidence slice"
```

---

## Task 9 — The frontend half

> Load `fe-react:fe-react` before this task — the frontend is built through its phased pipeline, not
> improvised. What follows is the slice's shape, not a substitute for those skills.

**Files:**
- Modify: `frontend/src/api/queries/runs.ts` — `useAttachEvidence`, `useDeleteEvidence`.
- Modify: the execution screen under `frontend/src/features/runs/` — the upload control and the
  evidence strip.
- Modify: `frontend/src/mocks/handlers/runs.ts` + `frontend/src/mocks/db/executions.ts` — the mock
  must **model the `OWN` refusal**, reading `createdBy` exactly as the server does, in the same
  order. §66's rule: a mock that disables the button and accepts the write teaches the wrong thing
  more convincingly than no mock at all.
- Modify: `frontend/src/mocks/fixtures/` — seed evidence whose `createdBy` **disagrees** with the
  execution's `executedBy` on most rows, plus one row with a null author. §64's rule: seeded to the
  answer, both implementations pass.

- [ ] **Step 1: The mutation hooks** — `useAttachEvidence` posts `FormData` (never a JSON body;
  the endpoint is `multipart/form-data`) and must **not** set `Content-Type` by hand, or the
  boundary is lost. Invalidate the execution key on success.

- [ ] **Step 2: The delete affordance is gated with `authorId`**

```tsx
<PermissionGate permission="evidence:delete" projectId={projectId} authorId={item.createdBy?.id ?? null}>
```

**`?? null`, not `?? undefined`** — §66: `PermissionGate` switches on `authorId !== undefined`, so
`undefined` means "this gate is not author-qualified" and would hand every row the unqualified
verdict. A null author is a *supplied* author and must reach the arm that refuses it.

- [ ] **Step 3: A test that proves the page passes the author**

§66's finding, one screen over: `PermissionGate`'s own tests cover the verdict; nothing proves the
**call site** supplies `authorId`, and a call site that drops it fails silently. The mutation is
deleting the prop from the JSX. **Use a `permissionsResolved()` barrier** — `useCan` is fail-closed
while pending, so `waitFor(...toBeDisabled())` is satisfied by the loading state and the test passes
against the mutation in 44 ms.

- [ ] **Step 4: The size limit is enforced client-side too, as copy rather than as security**

Refuse a file over 10 MB in the control with a sentence naming the limit. The server is the gate;
this exists so the user learns before a 10 MB upload completes and 413s.

- [ ] **Step 5: Gate**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Expected: build clean, **0** lint warnings, all tests pass. **`npm run build`, not
`tsc --noEmit`** — the latter has reported 0 errors while the build failed with nine.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "the execution screen can finally attach the screenshot it has always rendered"
```

---

## Task 10 — Close the slice

- [ ] **Step 1: Append to both verification queues** — batching is the standing policy (§65), and a
  slice that lands without adding its rows has skipped the check outright.

`docs/verification/backend-mutation-checks.md`: the mutations run (Task 6 step 6, Task 7 step 4) and
those queued — notably *make the storage key include the client filename* (should redden the
traversal test) and *write the bytes before the rows* (should redden nothing, which is the finding:
the ordering is a recovery property no test asserts).

`docs/verification/frontend-browser-checks.md`: the fixture SQL, and the rows to drive — attach on a
result, the image rendering inline from the content route, delete of your own, **delete refused on a
colleague's with the right sentence**, **delete refused on a null-author row**, a lead deleting both,
a Viewer seeing neither control, and a >10 MB file answering 413 with readable copy. Flag the
null-author and lead rows as highest-risk for the same reason §66 did: every "disabled" observation
is also what a broken-for-everyone gate produces.

- [ ] **Step 2: Write the handoff section**

`docs/handoff/67-evidence-attach-and-delete.md`, then **one row** in `docs/HANDOFF.md`'s slice-log
table and an update to the header summary. **Never append a section body to `HANDOFF.md`** — it is
an index.

Lead with the findings, not the feature: the port lives in `execution` and why (D1), the write
ordering and what it trades (rows-then-bytes), the run-state invariant deliberately not implemented
(D3), and whatever the build actually taught — which is not knowable from this plan.

- [ ] **Step 3: Update the orientation cache**

`docs/agent-context/be-java.md` — add the seams this slice created (`EvidenceStore` port + adapter,
the two entities, the three routes, the multipart limits), re-stamp `Last verified` and
`Baseline commit`, and prepend one session-log line. Correct the "known unknowns" line that says
`/reports` is the only dead contract — evidence was a fourth dead read shape and is no longer one.

- [ ] **Step 4: Final gate on both stacks, then commit**

```bash
cd backend && ./gradlew build
cd ../frontend && npm run build && npm run lint && npm run test:run
```

---

## Self-review

**Spec coverage.** Open question #28 (storage port + filesystem adapter) → Task 1. The request-size
limit §51 said it forces → Task 1 step 6 + Task 4 step 6. `evidence:attach`'s first Java consumer →
Task 4. `evidence:delete`'s `OWN` gate, the consumer §66 named → Task 6. Both `V1` tables' first
writers → Task 2. The upload control §5 recorded as missing → Task 9. `Evidence.url` resolving →
Task 5.

**Deliberately out of scope, each with its reason recorded in the code:** the run-closed invariant
(D3 — belongs with `run:close`, over both writes), thumbnails (D4), per-project quota (#28's other
half, still open, and the filesystem adapter makes it *more* pressing not less — it belongs in the
handoff's carry-overs), and promoting the port to a shared module (D1 — when there is a second
caller).

**Type consistency.** `EvidenceUpload` is constructed in `EvidenceController.attach` and consumed by
`Attachment.forExecution` / `forStepResult`; `EvidenceStore.put(String, byte[])` matches every call
site; `StoredBlob.size()`/`stream()` match the content route; `Outcome` arms (`NotFound ·
Forbidden · NotTheAuthor · Attached · Deleted`) are switched exhaustively in the controller and
nowhere else.

**Known risk this plan cannot remove.** Task 9's mock must refuse on `createdBy` in the same order
the server does. If it refuses on a different fact, mock mode will agree with itself and live mode
will not — the §52 defect class, and §66 hit it on this exact permission family.
