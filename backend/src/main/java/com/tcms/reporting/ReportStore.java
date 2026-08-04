package com.tcms.reporting;

import java.util.Optional;

/**
 * Where generated report bytes live — <strong>the port</strong>, so an S3 adapter is additive, the
 * same shape and for the same reason as {@code execution.EvidenceStore} (plan §8 open question #28,
 * resolved in §51: filesystem now, object storage later).
 *
 * <h2>THIS IS A DELIBERATE COPY OF {@code execution.EvidenceStore} — the debt, named</h2>
 *
 * <p>Four methods, identical signatures, an identical opaque-key contract, and a
 * {@link ReportBlob} that is {@code execution.StoredBlob} with a different name. That is §58's E1 —
 * the copy that drifts — and it is accepted here rather than hidden: {@code reporting} may not
 * import {@code execution} (ArchUnit refuses the edge, and the module list is the product's layering
 * decision, not an accident), so the existing interface is unreachable <em>as a type</em>. The
 * alternatives were both worse: a {@code shared} module for two interfaces and a record is "a fourth
 * foundation module is a decision, not an import" (§76's argument against a {@code review} module,
 * slice-plan decision E), and depending on {@code execution} from here would give the reporting
 * module the run recorder's whole surface to reach for.
 *
 * <p><strong>{@code EvidenceStore}'s javadoc carries the reciprocal line.</strong> Without both
 * halves the pairing is invisible from whichever file you happen to open, and the day either port
 * grows a fifth method the other is where to look.
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
 * <p>A filesystem does not roll back with the transaction that wrote the row. {@code EvidenceService}
 * therefore writes rows first and bytes second, because a row whose blob is missing is findable by
 * an operator and a blob no row names is unreachable. {@code ReportJobRunner} writes <strong>bytes
 * first, row second</strong>, and the reason it is not a contradiction is that a report job already
 * has a row naming the work: an orphan blob here <em>is</em> findable — it belongs to the job that
 * failed — while the failure that matters is a {@code COMPLETED} job whose download 404s. The
 * argument lives in {@code ReportJobRunner}'s header too, because a reader who knows
 * {@code EvidenceService} will otherwise read this as a bug.
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
