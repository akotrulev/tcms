package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code BulkPayload} in {@code docs/specs/openapi.yaml} — the argument to a bulk operation, typed.
 *
 * <h2>Why every member is nullable and none of them is validated here</h2>
 *
 * <p>Which member is required is decided by the sibling {@code action}: {@code lifecycleStatus} for
 * {@code SET_STATUS}, {@code suiteId} for {@code MOVE_TO_SUITE}, nothing at all for
 * {@code SUBMIT_FOR_REVIEW}. There is no member present across all eight, so a bean-validation
 * annotation on any of them would be a claim about seven actions it is wrong about.
 * {@link BulkOperations#parse} resolves the combination once, with the action in hand, and hands
 * back a {@link BulkCommand} whose members are non-null by construction.
 *
 * <p>This replaced an untyped {@code additionalProperties: true} bag. The bag type-checked at
 * neither end — the SPA built {@code &#123;lifecycleStatus&#125;} or {@code &#123;conditionId&#125;}
 * as a free-form object, so a misspelled key was a silently ignored argument and the server would
 * have applied the action with a default. Naming the members is what makes a typo a compile error
 * on one side and a 400 on the other.
 *
 * @param notAutomatableReason required when {@code automationStatus} is {@code NOT_AUTOMATABLE}, by
 *                             the same {@code V1} check constraint the single-case editor obeys
 * @param ownerId              {@code null} is meaningful here and is not "absent" — it clears the
 *                             owner. The two are told apart by the action: {@code SET_OWNER} with a
 *                             null id unassigns, and no other action reads the member at all.
 * @param conditionKind        which of the two lists an attachment lands in; defaults to
 *                             {@code PRE} when absent
 */
record BulkPayloadInput(
        LifecycleStatus lifecycleStatus,
        AutomationStatus automationStatus,
        String notAutomatableReason,
        UUID ownerId,
        UUID suiteId,
        List<String> tags,
        UUID conditionId,
        ConditionKind conditionKind) {

    /**
     * An absent {@code payload} read as an empty one.
     *
     * <p>So {@link BulkOperations#parse} never branches on a null payload before branching on the
     * action: an action that needs an argument reports the <em>missing field</em> either way, which
     * is the message the form can land on, rather than a generic "payload required" that names no
     * control.
     */
    static BulkPayloadInput empty() {
        return new BulkPayloadInput(null, null, null, null, null, null, null, null);
    }
}
