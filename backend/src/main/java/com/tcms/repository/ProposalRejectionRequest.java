package com.tcms.repository;

/**
 * {@code ProposalRejection} in {@code docs/specs/openapi.yaml} — why a reviewer said no.
 *
 * <p>The whole body is optional, and so is the field: rejecting is a decision a reviewer must be
 * able to take quickly, and a mandatory reason on a queue of fifty is a form that gets filled with
 * the word "no". That is the opposite call from {@code case_flake}'s quarantine reason, and the
 * difference is what the row goes on to mean — a quarantine is live debt somebody must eventually
 * clear and cannot without knowing why, while a rejected proposal is finished. The note improves
 * the profile-quality signal §6w.6 wants; it does not unblock a person.
 */
public record ProposalRejectionRequest(String note) {
}
