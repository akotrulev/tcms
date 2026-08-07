package com.tcms.storage;

import java.util.Optional;

/**
 * Where evidence bytes live — <strong>the port</strong>, so an S3 adapter is additive (plan §8 open
 * question #28, resolved in §51: filesystem now, object storage later).
 *
 * <h2>Four methods over an opaque key, and nothing else</h2>
 *
 * <p>This interface has no notion of a company, an execution or an attachment row. Everything that
 * decides <em>which</em> bytes a caller may reach is a database question, answered by
 * {@code execution.EvidenceService} before any of these methods is called — in another module now,
 * which is a property rather than a coincidence: nothing here can see a person or a project. An
 * implementation that could be asked "list this company's files" would be one refactor away from
 * being the place that decides, and the decision would then exist in two places — which is the shape
 * §65 spent a slice collapsing one module over.
 *
 * <p>Keys are built in exactly one method, {@code execution.EvidenceService#storageKey}, which is
 * the only code that knows what a key <em>means</em>. Everything here treats it as opaque.
 *
 * <h2>~~{@code reporting.ReportStore} IS THIS INTERFACE, COPIED~~ → they are siblings now (§125)</h2>
 *
 * <p>Same four methods and the same signatures, and until §125 that was a copy whose only
 * justification was a module boundary: {@code reporting} may not import {@code execution}, so this
 * type was unreachable there — §58's E1, the copy that drifts, accepted rather than hidden
 * (slice-plan decision C; a {@code shared} module for two interfaces and a record was refused as
 * decision E). <strong>Both now live in {@code storage}, so that argument is gone</strong>, and
 * what is left is a deliberate choice rather than an inherited one: see this package's header for
 * why they stay two types (a wrong qualifier would put evidence in the report root, silently, where
 * a wrong type does not compile) and where the duplicated <em>body</em> went.
 *
 * <p>One thing does <em>not</em> carry across and both headers say so: the write ordering below is
 * inverted there, deliberately.
 *
 * <h2>Not transactional, and cannot be</h2>
 *
 * <p>A filesystem does not roll back with the transaction that wrote the row, and neither does an
 * object store. {@code execution.EvidenceService} therefore writes the rows first and the bytes
 * second, so the failure mode is a row whose blob is missing — a download that 404s, findable by an
 * operator — and never a blob no row names, which is unreachable and uncollectable. The delete path
 * mirrors it in the same order and for the same reason.
 */
public interface EvidenceStore {

    /** Writes {@code bytes} under {@code key}, replacing anything already stored there. */
    void put(String key, byte[] bytes);

    /** The bytes, or empty when the key names nothing. <strong>The caller closes it.</strong> */
    Optional<StoredBlob> open(String key);

    /** Removes the bytes. A key naming nothing is not an error — delete is idempotent. */
    void delete(String key);

    /** Whether anything is stored under {@code key}. */
    boolean exists(String key);
}
