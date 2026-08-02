package com.tcms.project;

/**
 * What is under review — plan §6g's three artifacts, and the whole of what this module knows about
 * any of them.
 *
 * <p>A review's subject is an <strong>opaque triple</strong>: a type, an id, and the version number
 * the submission was pinned to. There is no foreign key ({@code V1} declares none for
 * {@code review.subject_id}) and no lookup — nothing in this package resolves a case, a suite or a
 * plan, and that is what lets the reviewer apparatus live in a module both {@code repository} and
 * {@code execution} may call. See {@link ReviewService}'s header for why it lives here at all.
 *
 * <h2>The permission key is a property of the subject type</h2>
 *
 * <p>{@code case:review}, {@code suite:review} and {@code plan:review} have been seeded by
 * {@code V3} and correctly granted since M0 with nothing in Java ever asking for them — the same
 * shape as §62's {@code case:approve}, §64's {@code library:manage}, §69's
 * {@code company:vocabulary}, §71's {@code company:teams}, §72's {@code run:close} and §73's
 * {@code plan:edit}, and the sixth time a permission with no consumer named a feature nobody had
 * built. §73 and §75 both recorded that leaving these three unconsumed was <em>correct</em> rather
 * than an omission: §6g puts peer review <em>inside</em> {@code IN_REVIEW}, accumulating on the item
 * without moving it, so the key belongs to the reviewer apparatus and not to any transition. This is
 * that apparatus, and these are its first consumers.
 *
 * <p>They are three keys rather than one because the roles spec grants them separately, and because
 * §6g says a suite review and a case review are different judgements — the suite's is about
 * composition, the case's about the script. A team that wants peer review on cases and not on
 * folders expresses that by the grants it makes.
 */
public enum ReviewSubjectType {

    /** A test case. Reviewing it means the script is correct, complete and executable (§6g). */
    CASE("case:review"),

    /**
     * A suite. Reviewing it means its <em>composition</em> is right — and §6g is explicit that this
     * does not implicitly approve the cases inside it, or the audit trail becomes meaningless.
     */
    SUITE("suite:review"),

    /** A test plan. Reviewing it is the release-readiness signature (§6g). */
    PLAN("plan:review");

    private final String reviewPermission;

    ReviewSubjectType(String reviewPermission) {
        this.reviewPermission = reviewPermission;
    }

    /**
     * The permission a <em>peer</em> review of this subject costs, held at project scope.
     *
     * <p>Deliberately not the approve key. Recording LGTM or "changes requested" moves nothing —
     * {@link ReviewService} refuses to write an {@code APPROVAL}-kind row from the participation
     * endpoint — so gating it at the approve key would make peer review a privilege of the people
     * whose sign-off it exists to inform.
     */
    public String reviewPermission() {
        return reviewPermission;
    }
}
