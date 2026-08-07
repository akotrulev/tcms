package com.tcms.storage;

import java.util.Optional;

/**
 * Where attachment bytes live — <strong>the port</strong>, so an S3 adapter is additive (plan §8
 * open question #28, resolved in §51: filesystem now, object storage later).
 *
 * <h2>~~{@code EvidenceStore}~~ → renamed in §126, and the property key deliberately was not</h2>
 *
 * <p>This was called {@code EvidenceStore} while evidence was the only thing anybody stored. #49
 * gives {@code attachment} three more owners — a case's spec extract, a documentation page's image,
 * the bytes behind a {@code FILE} artifact — and they land <em>here</em>, in the same root, because
 * the two roots are split on retention and loss consequences (see this package's header) and all
 * four owners are on the same side of that split: not regenerable, backup-critical. A port serving
 * four owner types under the name of one is a question every future reviewer has to ask once.
 *
 * <p><strong>{@code tcms.storage.evidence.root} keeps its name.</strong> That is the decision, not an
 * oversight: renaming a configuration key relocates the root on the next deploy, and the old
 * directory goes on existing, unreferenced, while every existing row's bytes become unreachable and
 * the product renders attachments that all 404. §107 destroyed every evidence file on a live box
 * through the mount that was not there; this is the same loss bought for a naming nicety. The
 * property is the <em>deployment's</em> identity and the type is the <em>code's</em>, and only one of
 * them is free to change.
 *
 * <h2>Four methods over an opaque key, and nothing else</h2>
 *
 * <p>This interface has no notion of a company, a case, an execution or an attachment row.
 * Everything that decides <em>which</em> bytes a caller may reach is a database question, answered by
 * the owning module's service before any of these methods is called — in another module in every
 * case, which is a property rather than a coincidence: nothing here can see a person or a project. An
 * implementation that could be asked "list this company's files" would be one refactor away from
 * being the place that decides, and the decision would then exist in two places — which is the shape
 * §65 spent a slice collapsing one module over.
 *
 * <p>Keys are built in exactly one method, {@link BlobUpload#key}, which is the only code that knows
 * what a key <em>means</em>. Everything here treats it as opaque. Until §126 that method was
 * {@code execution.EvidenceService#storageKey} and the sentence was equally true; four callers is
 * what turned "one place" from an observation into something worth enforcing by having nowhere else
 * to put it.
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
public interface AttachmentStore {

    /** Writes {@code bytes} under {@code key}, replacing anything already stored there. */
    void put(String key, byte[] bytes);

    /** The bytes, or empty when the key names nothing. <strong>The caller closes it.</strong> */
    Optional<StoredBlob> open(String key);

    /** Removes the bytes. A key naming nothing is not an error — delete is idempotent. */
    void delete(String key);

    /** Whether anything is stored under {@code key}. */
    boolean exists(String key);
}
