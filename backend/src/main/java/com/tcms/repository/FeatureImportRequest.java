package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code FeatureImportRequest} in {@code docs/specs/openapi.yaml} — a folder of {@code .feature}
 * files, and what to do with the scenarios in them.
 *
 * <h2>The files arrive as text, not as an upload</h2>
 *
 * <p>{@code multipart/form-data} would have been the obvious shape and is the wrong one here. The
 * server never wants the bytes: it wants the scenarios, and it wants the caller's own relative path
 * beside each one as provenance ({@code test_case.imported_path}). A multipart part carries a
 * filename, not a path, so a browser dropping {@code features/orders/cancel.feature} would arrive
 * as {@code cancel.feature} and the provenance column would record something nobody can find. The
 * SPA reads the files client-side and sends what it read.
 *
 * <h2>This endpoint has no {@code Idempotency-Key}, and that is not an omission</h2>
 *
 * <p>What this repository calls idempotency is a unique {@code idempotency_key} column on a
 * <em>created row</em>, and an import creates many rows or none. Determinism replaces it: a replay
 * finds every scenario by {@code automationKey} and every condition by {@code imported_key},
 * answers {@code SKIP}/{@code UNCHANGED}, and writes nothing — which is a stronger property than a
 * key, because it also holds for a folder imported twice from two different machines.
 *
 * @param files      1–200 files, ≤ 1 MiB each and ≤ 8 MiB together, paths unique within the
 *                   request. The caps are checked in Java rather than left to
 *                   {@code max-request-size} (12 MB, {@code application.yml}) so the refusal is a
 *                   field error on {@code files} rather than a container 413 with no body.
 * @param suiteId    when set, EVERY scenario files into this suite and no suite is matched or
 *                   created — the escape hatch for a repository whose feature names do not match
 *                   the tree. Must be a live suite of this project.
 * @param onExisting what to do with a scenario whose {@code automationKey} already names a case in
 *                   this project (blueprint DEC-1).
 * @param mode       {@code PREVIEW} plans and writes nothing; {@code APPLY} plans and executes, in
 *                   one transaction. The two answer the identical shape, which is what lets the
 *                   page render one component for both.
 */
public record FeatureImportRequest(
        List<FeatureFile> files,
        UUID suiteId,
        OnExisting onExisting,
        Mode mode) {

    /**
     * {@code FeatureFile} — one file's path and text.
     *
     * @param path    provenance only, ≤ 1024 chars. Nothing resolves it to content: the file lives
     *                in somebody's automation repository and this instance never held it.
     * @param content the file, verbatim, as UTF-8 text
     */
    public record FeatureFile(String path, String content) {
    }

    /** What an existing case does to the row that would have written it. */
    public enum OnExisting {
        /** Leave it exactly as it is — the row reads {@code SKIP "exists (SKIP)"}. */
        SKIP,
        /** Rewrite it from the file, keeping step ids, lifecycle, priority and classification. */
        UPDATE
    }

    /** Whether this request is a question or an act. */
    public enum Mode {
        PREVIEW,
        APPLY
    }
}
