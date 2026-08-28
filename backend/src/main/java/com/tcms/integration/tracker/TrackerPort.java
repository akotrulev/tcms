package com.tcms.integration.tracker;

import java.util.List;
import java.util.Optional;

/**
 * What this product asks of an issue tracker — three verbs, and the absences are the design.
 *
 * <h2>Search, get, create. There is no {@code statuses} and no {@code sync}</h2>
 *
 * <p>004 D8 deleted status sync-back outright: no poller, no scheduler, no company sweep, no
 * rate-limit budget. So nothing here reads a tracker's workflow, and nothing here is called on a
 * timer. Every method below runs on a <strong>request thread</strong>, with the requesting person's
 * own credential, and its result is rendered and dropped.
 *
 * <h2>Every method takes the credential. That is not a parameter-passing style</h2>
 *
 * <p>Under D2 there is no service credential in this product at all. An adapter that could reach a
 * token from a field would be an adapter that could make a call on nobody's behalf, which is the
 * shape this whole plan exists to avoid — so the credential arrives per call, from the person whose
 * request it is, and the port cannot be used any other way.
 *
 * <h2>Failure is an empty answer here and a state at the edge</h2>
 *
 * <p>An adapter throws {@link TrackerUnavailableException} for anything the caller cannot fix —
 * the tracker refused the token, the tracker is down, the issue is invisible to this person. The
 * service turns all of them into one {@link TrackerIssueAvailability#UNAVAILABLE}, deliberately
 * (D16), so nothing downstream can tell them apart and turn the preview into an oracle.
 *
 * @see FakeTrackerAdapter which implements this behind the {@code tracker-fake} profile, per-user,
 *      so the two-user leak of {@code TrackerCache} is testable without a real Jira
 */
public interface TrackerPort {

    /**
     * Whether this adapter speaks for {@code provider}.
     *
     * <p>A predicate rather than a {@code provider()} getter because <strong>one adapter serves two
     * members</strong>: Jira Cloud and Jira Data Center differ in their REST version and in how the
     * token is presented, and in nothing else this product asks for. Two classes for that would be
     * two copies of one parser, kept in step by intention.
     */
    boolean supports(TrackerProvider provider);

    /**
     * Free-text or key search, scoped by the binding.
     *
     * @param query what the person typed into the typeahead. Handed to the tracker's own search,
     *              narrowed by the binding's JQL/WIQL scope clause — not parsed here, because a
     *              query language this product invented would be one more thing to be wrong about.
     * @param limit how many to return. The typeahead's page, not a paging API: a person picking an
     *              issue from a dropdown refines the query rather than paging through it.
     */
    List<TrackerIssue> search(TrackerCredential credential, TrackerBindingTarget target,
            String query, int limit);

    /**
     * One issue by its <strong>stable id</strong> (004 D17).
     *
     * <p>Jira also answers to a key here and this product never sends one: a key moves, and the
     * whole reason the id is what a link stores is that the fetch does not need the key.
     *
     * @return empty when the tracker says the issue does not exist. An issue that exists and is
     *         invisible to this credential is <strong>also</strong> empty on the providers that
     *         answer 404 for it, which is theirs to decide and is why the caller renders one state.
     */
    Optional<TrackerIssue> get(TrackerCredential credential, TrackerBindingTarget target, String issueId);

    /**
     * Files an issue <strong>as the person whose credential this is</strong>.
     *
     * <p>That is the property, not a side effect: Jira's {@code reporter} and ADO's
     * {@code System.CreatedBy} become the human who saw the failed step. A service credential would
     * have filed every defect in the instance under one machine account, which is worse product and
     * worse audit.
     */
    TrackerIssue create(TrackerCredential credential, TrackerBindingTarget target, TrackerDraft draft);

    /**
     * One cheap call that proves the credential works — {@code POST …/tracker-identities/{id}/test}.
     *
     * <p>Throws {@link TrackerUnavailableException} with the tracker's own message when it does
     * not. The message is stored on {@code tracker_identity.last_error} and shown to the person
     * whose credential it is, and to nobody else.
     */
    void verify(TrackerCredential credential);
}
