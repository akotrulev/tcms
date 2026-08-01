package com.tcms.provisioning;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@link IssueInvitationService} did, in enough detail for the CLI to tell an operator
 * which of four different situations they are in.
 *
 * <p>Four outcomes rather than a boolean, because each needs a <em>different next action</em>
 * from the person running the command, and "failed" would hide which: a typo'd slug, a typo'd
 * address, an account that never needed an invitation, and a link to hand over are not the
 * same problem.
 *
 * @param link the activation link, present <strong>only</strong> for {@link Outcome#ISSUED}.
 *             It is the one and only time this value exists — {@code membership_invitation}
 *             stores a digest, so nothing can reprint it afterwards.
 */
public record IssueInvitationResult(
        Outcome outcome,
        Optional<UUID> companyId,
        Optional<UUID> userId,
        Optional<String> displayName,
        Optional<String> link,
        Optional<Instant> expiresAt) {

    public enum Outcome {
        /** A fresh token was minted. Any previous one is now dead. */
        ISSUED,
        /** No company with that slug. */
        NO_SUCH_COMPANY,
        /**
         * No account with that address, or one that exists but is not a member of this
         * company. Deliberately one outcome: the operator's next step is the same either
         * way — invite them through the UI first — and separating them would make this
         * command answer "does this address exist on the instance" for whoever can run it.
         */
        NOT_A_MEMBER,
        /**
         * The membership is already ACTIVE. Nothing is issued: an active account needs a
         * password reset, not an invitation, and minting one would be a supported way to
         * take over a live account.
         */
        ALREADY_ACTIVE
    }

    static IssueInvitationResult noSuchCompany() {
        return new IssueInvitationResult(Outcome.NO_SUCH_COMPANY,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    static IssueInvitationResult notAMember(UUID companyId) {
        return new IssueInvitationResult(Outcome.NOT_A_MEMBER,
                Optional.of(companyId), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    static IssueInvitationResult alreadyActive(UUID companyId, UUID userId, String displayName) {
        return new IssueInvitationResult(Outcome.ALREADY_ACTIVE,
                Optional.of(companyId), Optional.of(userId), Optional.ofNullable(displayName),
                Optional.empty(), Optional.empty());
    }

    static IssueInvitationResult issued(
            UUID companyId, UUID userId, String displayName, String link, Instant expiresAt) {
        return new IssueInvitationResult(Outcome.ISSUED,
                Optional.of(companyId), Optional.of(userId), Optional.ofNullable(displayName),
                Optional.of(link), Optional.of(expiresAt));
    }
}
