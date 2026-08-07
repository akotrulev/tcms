package com.tcms.provisioning;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@link GrantInstanceAdminService} did, in enough detail for the CLI to tell an
 * operator which of four situations they are in.
 *
 * <p>Four outcomes rather than a boolean, for the same reason
 * {@link IssueInvitationResult} has four: each needs a <em>different next action</em> from
 * the person at the terminal, and "failed" would hide which.
 *
 * <h2>{@link Outcome#ALREADY_HELD} is a SUCCESS here, and that is the one deliberate
 * divergence from {@code issue-invitation}</h2>
 *
 * <p>{@code issue-invitation} exits non-zero on {@code ALREADY_ACTIVE} because the operator
 * asked for an <em>artifact</em> — a link — and did not receive one, so a script must not
 * read it as success. A grant is not an artifact: the operator asked for a person to
 * <em>be</em> an Instance Admin, and after this run they are. Re-running is the supported
 * way to make sure, which is the same property {@code provision-company} has and for the
 * same reason — an operator repairing an instance should be able to re-run the whole
 * sequence without reading exit codes to find out which steps were no-ops.
 */
public record GrantInstanceAdminResult(
        Outcome outcome,
        Optional<UUID> userId,
        Optional<String> canonicalName,
        Optional<Boolean> active) {

    public enum Outcome {
        /** The row was written. This account holds Instance Admin now and did not before. */
        GRANTED,
        /**
         * The account already held it. Nothing was written and nothing needed to be —
         * {@code uq_instance_role_assignment (user_id, role_id)} makes a second row
         * impossible anyway, so this is the check that turns a constraint violation into a
         * sentence.
         */
        ALREADY_HELD,
        /**
         * No account with that address. Deliberately NOT split from "exists but you typo'd
         * it": there is nothing to disclose here — unlike {@code issue-invitation}, this
         * command is reachable only by someone with a shell on the box and the database
         * password, so the reticence that shapes {@link IssueInvitationResult.Outcome} buys
         * nothing. It is one outcome because the next action is one action: create the
         * account first.
         */
        NO_SUCH_USER,
        /**
         * The account is {@code DEACTIVATED}. Refused rather than written, even though the
         * grant would be inert — {@code PermissionEvaluator.systemTermIfAccountActive}
         * resolves SYSTEM permissions only for an {@code ACTIVE} account, so the row would
         * confer nothing today and everything the moment somebody reactivated the user,
         * with no second look. An operator who types a deactivated colleague's address has
         * made a mistake, and a command that silently succeeds is how it survives to become
         * a live grant later.
         */
        USER_DEACTIVATED
    }

    static GrantInstanceAdminResult granted(UUID userId, String canonicalName, boolean active) {
        return new GrantInstanceAdminResult(Outcome.GRANTED,
                Optional.of(userId), Optional.of(canonicalName), Optional.of(active));
    }

    static GrantInstanceAdminResult alreadyHeld(UUID userId, String canonicalName, boolean active) {
        return new GrantInstanceAdminResult(Outcome.ALREADY_HELD,
                Optional.of(userId), Optional.of(canonicalName), Optional.of(active));
    }

    static GrantInstanceAdminResult noSuchUser() {
        return new GrantInstanceAdminResult(Outcome.NO_SUCH_USER,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    static GrantInstanceAdminResult userDeactivated(UUID userId, String canonicalName) {
        return new GrantInstanceAdminResult(Outcome.USER_DEACTIVATED,
                Optional.of(userId), Optional.of(canonicalName), Optional.of(false));
    }
}
