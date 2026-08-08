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
 * <h2>~~Four~~ five methods over an opaque key, and nothing else</h2>
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

    /**
     * Copies the bytes under {@code from} to {@code to}, replacing anything already there.
     *
     * <p><strong>The fifth method, added in §129 for clone, and it is on the port rather than in the
     * caller on purpose.</strong> A caller can express this as {@link #open} then {@link #put} — and
     * that spelling forces every byte of every file through the JVM heap for ever, on every adapter,
     * because {@code put} takes a {@code byte[]}. The filesystem adapter does it with
     * {@link java.nio.file.Files#copy}; an S3 adapter does it with a server-side {@code CopyObject}
     * and moves nothing at all. Writing it in the caller would foreclose the one operation object
     * storage makes cheap, in the one code path whose input size the product does not choose.
     *
     * <p><strong>{@link ReportStore} deliberately does not get this.</strong> The two ports are two
     * types precisely so they can differ: nothing clones a generated report, and a method on a port
     * with no caller is a shape the next reader has to work out the purpose of. Their bodies are
     * still one class — {@code FilesystemBlobStore} carries the implementation and only this port
     * exposes it.
     *
     * <p><strong>Why a reference copy is not an option, stated because plan §6v asks for one.</strong>
     * §6v's clone table says attachments come across "by reference, not byte copy". Two
     * {@code attachment} rows sharing one {@code storage_key} means deleting either one deletes the
     * bytes the other names — {@link #delete} is keyed and holds no reference count, and no caller
     * can acquire one without this interface learning what a row is (see the header). The other row
     * then renders as a file that 404s, with nothing having failed. So the choice was a byte copy or
     * nothing, and §6v's stated preference is the one option that is wrong here.
     *
     * @return {@code false} when {@code from} names nothing, so a caller can skip an already-orphaned
     *         row rather than creating a second one. Not an exception: the write ordering this
     *         package documents produces exactly that orphan on purpose, and a clone must not turn
     *         one operator's missing file into two.
     */
    boolean copy(String from, String to);
}
