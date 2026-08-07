/**
 * Storage — the bytes that are not in Postgres, and the one row that describes them (ERD §0.1's
 * {@code attachment}, plan §8 decision round 3 #51).
 *
 * <p>Everything else in this product is a database question. This module is the exception: a
 * screenshot behind a failed result and a rendered report are files, they do not roll back with the
 * transaction that named them, and losing the directory they live in loses them permanently. That is
 * the whole of what belongs here.
 *
 * <h2>Why it exists, and why it did not exist until §125</h2>
 *
 * <p>{@link com.tcms.storage.Attachment}'s own javadoc argued for staying in {@code execution} and
 * named the exact condition for moving — <em>"a second caller to design against"</em> — because a
 * module extraction to serve one caller guesses at a shape rather than deriving it. Decision #49
 * creates that second caller twice over ({@code CASE}, {@code DOC_PAGE} and {@code ARTIFACT} owners
 * get writers), and #51 sequenced the move <strong>before</strong> them on purpose: in one diff, an
 * architectural move and a feature are indistinguishable, and the move is the half a reviewer cannot
 * check by reading the feature.
 *
 * <p>The prediction held. {@code Attachment.forExecution} / {@code forStepResult} meant no call site
 * anywhere ever spelled an {@code owner_type}, so the extraction was a package line and two
 * modifiers. <strong>The owner vocabulary is this module's now</strong>: a new owner is a factory
 * here, never a string at a call site — which is what keeps {@code V1}'s polymorphic table
 * ("<em>six near-identical attachment tables is the worse trade</em>") readable from one place.
 *
 * <h2>THIS IS A FOURTH FOUNDATION MODULE, AND THAT IS THE COST OF THE MOVE</h2>
 *
 * <p>Plan §3's rule is that a cross-module dependency points only at {@code tenancy},
 * {@code identity} or {@code project}; everything else crosses as a {@code CompanyScopedEvent}.
 * {@code execution} and {@code reporting} both need <em>bytes back, synchronously, inside a request</em>
 * — a download cannot be an event — so extracting this module and keeping that rule at three was not
 * available. {@code ArchitectureRules.FOUNDATION_MODULES} therefore names four, and
 * {@code ModuleStructureTest}'s hard-coded module list names eleven. Both edits are deliberate and
 * both are the kind §76 refused for a {@code review} module: <em>a fourth foundation module is a
 * decision, not an import</em>.
 *
 * <p>What makes it a defensible fourth rather than a precedent for a fifth is the shape of this one,
 * and it is worth stating as a rule for whoever proposes the next:
 *
 * <ul>
 *   <li><strong>It depends on {@code tenancy} alone</strong> — not on {@code identity} or
 *       {@code project}. It has no notion of a person, a permission or a gate, so it cannot become a
 *       second place authorization is decided. The only thing it takes from {@code tenancy} is
 *       {@code CompanyFilter}, because {@code attachment} is a company-owned table like any other.</li>
 *   <li><strong>It holds no domain concept.</strong> No run, no case, no report — an
 *       {@code owner_id} is a {@code UUID} this module never resolves, and the ports below take an
 *       opaque key and nothing else.</li>
 *   <li><strong>It answers a question the database cannot.</strong> That is the test the other three
 *       foundation modules pass too, and the one a feature module proposing itself as a fourth
 *       will not.</li>
 * </ul>
 *
 * <h2>Two ports, one implementation body, and deliberately not one port</h2>
 *
 * <p>{@link com.tcms.storage.EvidenceStore} and {@link com.tcms.storage.ReportStore} are the same
 * four methods over an opaque key. Until §125 that was a <em>documented copy</em> — §58's E1, the
 * copy that drifts — accepted only because {@code reporting} could not import {@code execution}, so
 * the type was unreachable rather than unnoticed. Co-located here, that argument is gone and both
 * headers say so.
 *
 * <p>They are still two types, and the reason is the failure they are between: one
 * {@code BlobStore} with two beans would make "which store am I holding" a <em>qualifier string</em>,
 * and the failure of a wrong one is evidence written into the report root — silent, and discovered
 * when somebody looks for a screenshot that a green test suite says was stored. Two interfaces make
 * it a compile error.
 *
 * <p>What was actually duplicated is the <em>body</em> — the traversal guard and the four filesystem
 * operations, written twice — and that is what the second phase of this slice collapses into one
 * class the two adapters delegate to, so the S3 adapter decision #55 defers is <strong>one</strong>
 * implementation with two roots rather than two.
 *
 * <h2>Not transactional, and cannot be</h2>
 *
 * <p>A filesystem does not roll back and neither will an object store. Each caller therefore chooses
 * its own row/bytes ordering and states why: {@code execution.EvidenceService} writes rows first
 * (an orphan row 404s and is findable; an orphan blob is unreachable and uncollectable), and
 * {@code reporting.ReportJobRunner} writes bytes first (its job row already names the work, so the
 * orphan blob <em>is</em> findable, and the failure that matters is a {@code COMPLETED} job whose
 * download 404s). <strong>The ordering is the caller's, not this module's</strong> — which is why
 * neither port has a "store this row and these bytes" method that would decide it for both.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tenancy"})
package com.tcms.storage;
