/**
 * Documentation — the specification space, and the place traceability is meant to become visible
 * (plan §6i).
 *
 * <h2>The decision this module is built around: requirements are blocks, and a block's id is
 * permanent</h2>
 *
 * <p>There is deliberately <strong>no {@code requirement} table</strong> (ERD, resolved question
 * #18). A requirement is a {@code doc_block} — a paragraph in somebody's specification — and the
 * coverage matrix is a query over {@code doc_link} joining those blocks to the cases that verify
 * them. That is what makes this module the traceability surface rather than a wiki that happens to
 * live in the product.
 *
 * <p>Everything awkward in here follows from one consequence of it. {@code V1}'s comment on
 * {@code doc_block.id} states the rule: the id is <em>immutable, generated at creation, and never
 * derived from content</em>, because links target {@code (page_id, block_id)}. So editing a page
 * cannot be "delete the blocks and write the new ones", which is the obvious implementation and the
 * one that silently voids every link on the page. {@link com.tcms.documentation.PageContent} is the
 * whole of that problem: an edit carries the ids it is editing, a block keeps its id however much
 * its text changes, and an id this page does not own is refused rather than quietly created.
 *
 * <p>The failure being prevented is specific and silent. A heading-text anchor —
 * {@code #full-refund} — breaks the moment somebody fixes a typo, and <em>nothing reports it</em>:
 * the link still resolves to the page, the reader still sees a page, and only an audit months later
 * finds that the case verifying partial refunds points at nothing. Being loud about this is why the
 * block-removal path answers 409 rather than cascading.
 *
 * <h2>Two permissions, and they are separate endpoints because they are separate gates</h2>
 *
 * <p>{@code doc:edit} writes content; {@code doc:publish} moves a page between {@code DRAFT},
 * {@code PUBLISHED} and {@code ARCHIVED}, and Test Engineer holds the first and not the second
 * (roles spec §A2). They are {@code PATCH .../doc-pages/{id}} and {@code PUT .../doc-pages/{id}/status}
 * rather than one endpoint that branches on which fields the body carried: a gate that depends on
 * the payload is one the SPA cannot render a disabled state for, and §31's whole point is that an
 * action the server will refuse must be visibly unavailable with a reason rather than inviting a
 * 403.
 *
 * <p>{@code status} is therefore absent from {@link com.tcms.documentation.DocPageInput} entirely,
 * which is also §6g's rule as applied twice already: a state an ordinary create could set is an
 * approval its author granted themselves.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy} and {@code project} — the isolation boundary and the gate
 * ({@link com.tcms.project.ProjectService#readVisible} / {@code #writeGranted}). Not
 * {@code identity}: unlike {@code repository}, {@code execution} and {@code work}, nothing here
 * renders a person. A page has {@code created_by} and {@code updated_by} columns and the contract
 * puts neither on the wire, so asking {@code identity} what somebody is called would be asking for
 * a value with no consumer.
 *
 * <h2>What is deliberately not built</h2>
 *
 * <p><strong>{@code doc_link} has no writer</strong>, so no block has a {@code coverage} marker and
 * no case has a {@code relatedDocuments} entry. The field is <em>absent</em> from responses rather
 * than serialized as a zeroed marker, which is the call
 * {@link com.tcms.repository.TestCaseDetailView} already made for the other end of the same edge:
 * a marker reading "0 cases, not tested" states that nothing verifies this block, when the true
 * statement is that nothing has been asked. The linking slice lights up both ends at once, and this
 * module already refuses to break a link it cannot yet create — the invariant is enforced from the
 * day the blocks exist rather than retrofitted onto data that grew without it.
 *
 * <p>Also not built: {@code doc_page_version} is written but never read (no diff view), page moves
 * (reparenting rewrites descendant paths, exactly as it does for a suite), spaces as a concept on
 * the wire, and the {@code DOC_UPDATE} derived board card {@code work}'s package comment names as
 * waiting for this module.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tenancy", "project"})
package com.tcms.documentation;
