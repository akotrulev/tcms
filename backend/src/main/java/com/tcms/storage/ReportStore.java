package com.tcms.storage;

import java.util.Optional;

/**
 * Where generated report bytes live — <strong>the port</strong>, so an S3 adapter is additive, the
 * same shape and for the same reason as {@link EvidenceStore} (plan §8 open question #28, resolved
 * in §51: filesystem now, object storage later).
 *
 * <h2>~~THIS IS A DELIBERATE COPY OF {@code execution.EvidenceStore}~~ → the reason expired (§125)</h2>
 *
 * <p>Four methods, identical signatures, an identical opaque-key contract, and a {@link ReportBlob}
 * that is {@link StoredBlob} with a different name. That was §58's E1 — the copy that drifts — and
 * it was accepted rather than hidden on one argument: <em>"{@code reporting} may not import
 * {@code execution} …, so the existing interface is unreachable <strong>as a type</strong>"</em>,
 * with a {@code shared} module for two interfaces and a record refused as "a fourth foundation
 * module is a decision, not an import" (§76's argument against a {@code review} module, slice-plan
 * decision E).
 *
 * <p><strong>#51 took that decision, and this module is it.</strong> Both ports are now siblings in
 * {@code storage}, so nothing is unreachable and nothing is a copy for want of a home. They remain
 * two <em>types</em> for a reason this package's header sets out — one interface with two beans puts
 * "which store is this" in a qualifier string, and a wrong one writes evidence into the report root
 * without failing — and the duplicated <em>body</em> is what the move actually removed.
 *
 * <h2>Four methods over an opaque key, and nothing else</h2>
 *
 * <p>No notion of a company, a job or an artifact row. Everything that decides <em>which</em> bytes
 * a caller may reach is a database question, answered before any of these methods is called. An
 * implementation that could be asked "list this company's files" would be one refactor away from
 * being the place that decides, and the decision would then exist in two places.
 *
 * <h2>Not transactional, and cannot be — and the ordering here is the INVERSE of evidence's</h2>
 *
 * <p>A filesystem does not roll back with the transaction that wrote the row.
 * {@code execution.EvidenceService} therefore writes rows first and bytes second, because a row
 * whose blob is missing is findable by an operator and a blob no row names is unreachable.
 * {@code reporting.ReportJobRunner} writes <strong>bytes first, row second</strong>, and the reason
 * it is not a contradiction is that a report job already has a row naming the work: an orphan blob
 * here <em>is</em> findable — it belongs to the job that failed — while the failure that matters is
 * a {@code COMPLETED} job whose download 404s. The argument lives in {@code ReportJobRunner}'s
 * header too, because a reader who knows {@code EvidenceService} will otherwise read this as a bug.
 *
 * <p><strong>The ordering is the caller's and neither port has an opinion</strong>, which is why
 * there is no "row and bytes" method here that would decide it for both — the two callers now sit
 * in two other modules and disagree on purpose.
 */
public interface ReportStore {

    /** Writes {@code bytes} under {@code key}, replacing anything already stored there. */
    void put(String key, byte[] bytes);

    /** The bytes, or empty when the key names nothing. <strong>The caller closes it.</strong> */
    Optional<ReportBlob> open(String key);

    /** Removes the bytes. A key naming nothing is not an error — delete is idempotent. */
    void delete(String key);

    /** Whether anything is stored under {@code key}. */
    boolean exists(String key);
}
