package com.tcms.execution;

import java.util.Optional;

/**
 * Where evidence bytes live — <strong>the port</strong>, so an S3 adapter is additive (plan §8 open
 * question #28, resolved in §51: filesystem now, object storage later).
 *
 * <h2>Four methods over an opaque key, and nothing else</h2>
 *
 * <p>This interface has no notion of a company, an execution or an attachment row. Everything that
 * decides <em>which</em> bytes a caller may reach is a database question, answered by
 * {@link EvidenceService} before any of these methods is called. An implementation that could be
 * asked "list this company's files" would be one refactor away from being the place that decides,
 * and the decision would then exist in two places — which is the shape §65 spent a slice collapsing
 * one module over.
 *
 * <p>Keys are built in exactly one method, {@code EvidenceService#storageKey}, which is the only
 * code that knows what a key <em>means</em>. Everything here treats it as an opaque string.
 *
 * <h2>Not transactional, and cannot be</h2>
 *
 * <p>A filesystem does not roll back with the transaction that wrote the row, and neither does an
 * object store. {@link EvidenceService} therefore writes the rows first and the bytes second, so the
 * failure mode is a row whose blob is missing — a download that 404s, findable by an operator — and
 * never a blob no row names, which is unreachable and uncollectable. The delete path mirrors it in
 * the same order and for the same reason.
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
