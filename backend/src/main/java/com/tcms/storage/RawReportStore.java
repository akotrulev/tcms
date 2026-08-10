package com.tcms.storage;

import java.util.Optional;

/**
 * Where the <strong>verbatim CI upload</strong> lives — the file a build posted to
 * {@code POST /ingestions}, byte for byte, before this server understood any of it.
 *
 * <h2>This is NOT {@link ReportStore}, and the names are one letter apart on purpose</h2>
 *
 * <p>{@link ReportStore} holds a report this product <em>generated</em> — regenerable from
 * {@code execution_fact}, rendered by {@code reporting}. This holds a report this product
 * <em>received</em> and can never reproduce. They are opposite in every property that matters to an
 * operator: one is a cache of work, the other is the only copy of what a third party said.
 *
 * <p>They are two types for this package header's reason — one interface with three beans puts
 * "which store is this" into a qualifier string, and a wrong one writes a CI upload into the report
 * root without failing. A wrong <em>type</em> is a compile error. That the two read similarly at an
 * injection site is the cost of the naming, and it is the cheaper half of the trade.
 *
 * <h2>Why this is a third root and not a subdirectory of either existing one</h2>
 *
 * <p>{@code tcms.storage.raw-reports.root}, under the rule this package states: a separate root
 * exists when retention and loss consequences differ, and this profile matches neither sibling.
 *
 * <ul>
 *   <li><strong>Volume is per build, not per act.</strong> Evidence arrives when a person attaches a
 *       screenshot; a report is rendered when somebody asks. This arrives on every CI run of every
 *       project, which is the highest write rate in the product and the only root an operator will
 *       plausibly want to prune.</li>
 *   <li><strong>Losing it is not losing the results.</strong> {@code ingestion_job.raw_payload}
 *       keeps the normalized parse and every match outcome in Postgres, so the history screen and
 *       the binder survive intact. What is lost is the ability to answer <em>"is that what CI
 *       actually sent?"</em> — an audit question, not an operating one.</li>
 *   <li><strong>It is not regenerable and not reproducible.</strong> Unlike a report, nothing in
 *       this system can produce these bytes again; unlike evidence, they are not the proof behind a
 *       human verdict. It is a third thing and gets a third root.</li>
 * </ul>
 *
 * <p><strong>Nothing prunes it in v1</strong>, exactly as nothing deletes the other two roots. That
 * is a decision and not an oversight: a retention sweep is a scheduled job with a company-scoped
 * context and a policy nobody has set, and shipping one here would make the first retention rule in
 * the product a side effect of adding a download. §6n is where it will eventually be governed.
 *
 * <h2>Four methods over an opaque key, and nothing else</h2>
 *
 * <p>{@link AttachmentStore}'s contract verbatim: no notion of a company, a project or a job. What a
 * caller may reach is a database question, answered before any of these is called.
 *
 * <h2>Not transactional, and the ordering here follows EVIDENCE, not the report runner</h2>
 *
 * <p>{@code execution.IngestionService} writes the job row first and the bytes second, which is
 * {@code EvidenceService}'s ordering for {@code EvidenceService}'s reason: a row whose blob is
 * missing is findable by an operator and downloads as a 404, while a blob no row names is
 * unreachable and uncollectable. {@code ReportJobRunner}'s inverse does not apply — there is no
 * prior row naming this work, because the job row is the thing being created.
 */
public interface RawReportStore {

    /** Writes {@code bytes} under {@code key}, replacing anything already stored there. */
    void put(String key, byte[] bytes);

    /** The bytes, or empty when the key names nothing. <strong>The caller closes it.</strong> */
    Optional<StoredBlob> open(String key);

    /** Removes the bytes. A key naming nothing is not an error — delete is idempotent. */
    void delete(String key);

    /** Whether anything is stored under {@code key}. */
    boolean exists(String key);
}
