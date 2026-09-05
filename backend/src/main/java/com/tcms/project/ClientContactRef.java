package com.tcms.project;

import java.util.UUID;

/**
 * A client's contact as a module that may not import {@code crm} receives it — the shape
 * {@link ClientDirectory#contactsOf} answers with.
 *
 * <p><strong>Contact information, and not an identity.</strong> There is no user id here and there
 * never will be one without reopening scope D1; {@code crm.ClientContactView} says the same thing
 * about the same five fields, and a reader checking whether a contact is an account should find the
 * answer by reading either record and finding nothing that could be one.
 *
 * <p><strong>Two records, one schema, and that is deliberate</strong> (019 D-u). {@code crm} serves
 * {@code ClientContact} on the client page and {@code billing} serves it as an invoice's
 * <em>Bill to</em>, and neither module may import the other — the {@code Attachment} case
 * {@code ContractConventionTest} already carries, where two identical records in two modules are
 * pinned to one schema so the pair cannot drift apart. It is <strong>not</strong> in
 * {@code com.tcms.api}: putting it there would make {@code crm.ClientContactView} a copy of an api
 * shape, which {@code ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape} correctly forbids,
 * and repairing that means renaming a public wire type across {@code crm} for a field on an invoice.
 * The day a third module needs a client's contact, that rename is the right answer and this comment
 * is where the argument for making it lives.
 *
 * <p>It is here rather than in {@code crm} for {@link ClientDirectory}'s reason: the inversion's
 * declaration lives in the foundation module and the answer lives in {@code crm}.
 */
public record ClientContactRef(UUID id, String name, String role, String email, String phone) {
}
