package com.tcms.project;

import com.tcms.api.UserRef;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>Who is this person</strong> — asked from this side of the module boundary, and never
 * <em>may they</em>.
 *
 * <h2>Why this is a port and {@code CaseOwners} is not</h2>
 *
 * <p>{@code repository.CaseOwners}' header answers the question this class raises: a module that
 * <em>may</em> depend on {@code identity} should just depend on it, because declaring a port would
 * make {@code identity} depend on the test repository in order to hand back a display name — an
 * arrow pointing the wrong way to avoid one that points the right way. That reasoning is unchanged
 * and this is the case it excludes: {@code project} may depend on {@code tenancy} <em>only</em>, so
 * the import is not available at any price, and this package's own comment already prescribes the
 * remedy — <em>"if Modulith ever fails on this edge, the fix is a port, never a wider
 * allowedDependencies"</em>. {@link ProjectAccess} is the first instance; this is the second.
 *
 * <h2>One implementation, deliberately</h2>
 *
 * <p>The rule that a person's name is their <em>company membership's</em> name, falling back to the
 * account's canonical one for somebody since offboarded, is plan §6u's and has to be applied
 * identically everywhere or one screen renders a client's own label for a colleague and the next
 * renders the name they use with a different client. It was written out twice before this — in
 * {@code CaseOwners} and in {@code execution.ExecutionPeople}, the second carrying the comment
 * <em>"the two classes must not disagree"</em>, which is §58's E1 exactly: two hand-maintained
 * copies under an instruction to keep them in step. Writing the third consumer is what made that
 * visible, so the copies now delegate here rather than a third one being added beside them.
 */
public interface PeopleDirectory {

    /**
     * Resolves each id, skipping any that names no account.
     *
     * <p>An unresolvable id yields <strong>no entry</strong>, and the caller renders whatever it
     * renders for an unattributed row. That is deliberate rather than defensive: {@code created_by}
     * columns are nullable and have no cascade, so "the account that did this has since been
     * removed" is a state real rows are in, and inventing a person there attributes an act to
     * somebody — the one thing an audit record must not do wrongly.
     *
     * <p>This answers about an <em>account on the instance</em>, not about membership of the
     * company: {@code app_user} is on ERD §0.1's exception list because one person may work with
     * several clients. It is therefore <strong>not</strong> a membership check and must never be
     * used as one — see {@code ExecutionPeople#isActiveMember}, which exists because that
     * substitution cost a slice a red test and would have assigned a client's work to a stranger.
     *
     * @param companyId the active company, passed rather than read from the context so the caller's
     *                  scope and this lookup's scope are visibly the same one
     * @return a mutable map answering {@code null} for an id it could not place — never
     *         {@code Map.of()}, whose {@code get(null)} throws by contract and made this exact
     *         lookup a 500 in §38
     */
    Map<UUID, UserRef> resolve(UUID companyId, Collection<UUID> userIds);

    /**
     * Every present member of this company, in display-name order.
     *
     * <p>Added for {@code staffing}'s directory (017 D-k), which lists <em>everybody</em> —
     * {@code directory.html}'s "3 of 11 people match" counts members, not people who happen to have
     * a staffing profile, and most people have no profile at all. No existing port answered it:
     * {@link #resolve} resolves ids the caller already has, and
     * {@code identity.AdminDirectoryService.listUsers} gates on admin reach, which an Account
     * Manager holding only {@code staffing:directory} does not have.
     *
     * <p>A {@code REMOVED} membership is left out, the same filter {@code OrgChartService.chartFor}
     * applies and for the same reason: offboarding is one status update rather than a deletion, so
     * the row is still there and the person is not.
     *
     * <p><strong>This is a naming read, not an access read.</strong> Whoever calls it has already
     * been gated; it answers "who is here", never "may they" — {@link #resolve}'s rule, and this
     * method is the easier one to misuse because a list of people looks like a list of people who
     * may do something.
     *
     * @param companyId the active company, passed rather than read from the context for
     *                  {@link #resolve}'s reason: the caller's scope and this lookup's scope are
     *                  visibly the same one
     */
    List<UserRef> membersOf(UUID companyId);
}
