package com.tcms.documentation;

/**
 * {@code ArtifactEditInput} in {@code docs/specs/openapi.yaml} — the register's whole editable
 * surface, and <strong>the reason it is three fields is worth reading before adding a fourth</strong>.
 *
 * <p>Every other column {@link Artifact} maps is {@code updatable = false}, each with its own
 * recorded reason: {@code scope} and {@code planId} decide which permission verdict the row is under
 * (so an edit that moved them would move the row out from under the gate that just allowed it), and
 * {@code kind}, {@code docPageId}, {@code externalUrl} and {@code attachmentId} are the payload —
 * plan §6n's answer to <em>"this points at the wrong thing"</em> is to supersede, which produces a
 * second row naming the first, rather than to swap the target under a {@code created_at}, an author
 * and an audit trail that go on describing the document that was replaced. {@code status} has its
 * own operation for §6g's reason.
 *
 * <h2>§68's convention, applied rather than extended</h2>
 *
 * <p>Jackson gives an absent field and an explicit {@code null} the same Java value, so this record
 * — like {@code repository.CaseUpdateRequest} and {@code execution.PlanUpdateRequest} before it —
 * cannot tell them apart. The settled convention is that a scalar {@code null} means <em>clear
 * it</em>. That is honoured here, and the two {@code NOT NULL} columns are <strong>required</strong>
 * precisely because of it: a caller omitting {@code title} under this convention is asking to clear
 * a column that cannot be cleared, and answering that as a field error is more honest than
 * inventing a third reading of a {@code PATCH} body in a product that already has two.
 *
 * <p>The flow review's B1 — unify the case {@code PATCH}'s absent-clears with something safer — is
 * a decision over the whole contract and is deliberately <strong>not</strong> pre-empted here.
 *
 * @param title       required; trimmed, capped at {@link Artifact#MAX_TITLE}
 * @param category    required; free text, because plan §6n calls the list project-configurable
 * @param description the one clearable field. {@code null} removes it, which is the ordinary fate
 *                    of a note that is no longer needed
 */
public record ArtifactEditInput(String title, String category, String description) {
}
