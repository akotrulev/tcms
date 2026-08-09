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
 * <p>{@code tenancy}, {@code project}, — since the artifact register — {@code identity}, and —
 * since #49 gave {@code attachment.owner_type = 'DOC_PAGE'} a writer — {@code storage}.
 *
 * <p>{@code storage} is the fourth foundation module (§125) and this is the second module to declare
 * it after {@code repository}. What is asked of it is <em>hold these bytes and give them back</em>:
 * {@link com.tcms.documentation.DocPageAttachmentService} names {@code Attachment},
 * {@code AttachmentRepository} and {@code AttachmentStore} and nothing else, and the download cannot
 * be an event because a request has to answer with the bytes — which is the whole argument for
 * {@code storage} being a foundation module rather than an event peer.
 *
 * <p>~~Not {@code identity}: unlike {@code repository}, {@code execution} and {@code work}, nothing
 * here renders a person. A page has {@code created_by} and {@code updated_by} columns and the
 * contract puts neither on the wire, so asking {@code identity} what somebody is called would be
 * asking for a value with no consumer.~~ <strong>→ superseded.</strong> That was true of the
 * documentation space and was never true of the register beside it: the contract's {@code Artifact}
 * has {@code updatedBy} in its {@code required} array, because "who changed the environment guide,
 * and when" is the question a stale-artifact conversation opens with. The claim was accurate about
 * the part of the module that existed and was stated about the module, which is the difference
 * between a scoped observation and a rule.
 *
 * <p>The dependency is narrow and it is worth naming what it is not. What
 * {@link com.tcms.documentation.ArtifactPeople} asks {@code identity} is <em>what is this person
 * called</em>. It is never <em>may they</em>: the gate stays
 * {@link com.tcms.project.ProjectService}, and no class in this package computes an authorization
 * answer. That is the same line {@code repository.CaseOwners} draws, and the reason the port-and-
 * adapter shape used for {@code ProjectAccess} is not repeated — {@code project} cannot depend on
 * {@code identity} without a cycle, and this module can, so a port here would mean making
 * {@code identity} depend on the documentation module to hand back a display name.
 *
 * <p>{@code execution} is <strong>still not</strong> a dependency, and a plan-scope artifact needs a
 * fact from it. {@link com.tcms.documentation.PlanFacts} is that edge, concentrated in one file and
 * reading {@code test_plan} as native SQL — the pattern {@code work.BoardFacts} names, and its
 * fourth instance.
 *
 * <h2>What is deliberately not built</h2>
 *
 * <p>~~<strong>{@code doc_link} has no writer</strong>, so no block has a {@code coverage} marker
 * and no case has a {@code relatedDocuments} entry. The field is <em>absent</em> from responses
 * rather than serialized as a zeroed marker, which is the call
 * {@link com.tcms.repository.TestCaseDetailView} already made for the other end of the same edge:
 * a marker reading "0 cases, not tested" states that nothing verifies this block, when the true
 * statement is that nothing has been asked. The linking slice lights up both ends at once, and this
 * module already refuses to break a link it cannot yet create — the invariant is enforced from the
 * day the blocks exist rather than retrofitted onto data that grew without it.~~ →
 * <strong>superseded by §93, corrected here in §136.</strong>
 *
 * <p>{@code BlockLinkService} is the writer, {@code POST …/blocks/{blockId}/links} is the endpoint,
 * and every block serves its {@code links}. The paragraph above was stale for over forty sections
 * while {@code DocBlockView}'s own header recorded the supersession correctly — and this is the
 * file an agent reads <em>first</em> to orient in the module, so it was the one place the stale
 * claim did the most damage. §135 found it; the shape is §110's, where four documents went on
 * calling the Linux gate inert for two days after it started working: a supersession written
 * somewhere true and not everywhere the claim appears.
 *
 * <p>What is still absent is {@code coverage}, for a different reason stated on the field itself:
 * this module can see <em>which</em> cases claim a statement but not how they are <strong>doing</strong>,
 * and that join is §81's rule putting it in {@code work}. The zeroed-marker argument above still
 * holds for it.
 *
 * <p>Also not built: {@code doc_page_version} is written but never read (no diff view), page moves
 * (reparenting rewrites descendant paths, exactly as it does for a suite), spaces as a concept on
 * the wire, and the {@code DOC_UPDATE} derived board card {@code work}'s package comment names as
 * waiting for this module.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "project", "identity", "storage"})
package com.tcms.documentation;
