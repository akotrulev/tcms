package com.tcms.tenancy;

/**
 * The names of the events that cross module boundaries — the whole vocabulary, in one file.
 *
 * <h2>Why the names live in {@code tenancy} when the meanings do not</h2>
 *
 * <p>An event type is matched by string: the producer writes it into a column and
 * {@link OutboxHandler#eventType()} claims it back, because plan §3 forbids the two modules from
 * sharing a type at all (see {@link OutboxEntry}). Left as literals, a producer's
 * {@code "execution.results_changed"} and a handler's {@code "execution.result_changed"} compile,
 * pass review, and produce a {@link OutboxStatus#DEAD} row in production — which is at least
 * visible, and is still a defect found by an operator rather than by a build.
 *
 * <p>So the names are constants and they live in the one module every other module may import.
 * That is a real cost — adding an event type means editing a foundation module for a feature
 * module's reason — and it buys the one property that matters here: <strong>there is exactly one
 * spelling of each name in the codebase</strong>, and the compiler enforces it on both sides of a
 * boundary the compiler is otherwise forbidden to see.
 *
 * <p>{@code tenancy} holds the <em>names</em> and nothing else. What the event means, what its
 * payload contains and what happens when it arrives all stay in the modules that produce and
 * consume it. The alternative reading — that this is the beginning of a domain vocabulary in the
 * isolation module — is the thing to refuse when the second and third entries arrive.
 *
 * <h2>The naming convention carries a behaviour</h2>
 *
 * <p>A name ending in a <strong>past-tense verb</strong> ({@code case.deleted}) is an occurrence:
 * each one is its own event, with its own payload, recorded through {@link Outbox#record}. A name
 * saying that something <strong>changed</strong> ({@code execution.results_changed}) is a state,
 * recorded through {@link Outbox#recordCoalesced} — several of them collapse into one, and it
 * therefore carries no payload and names no aggregate. Getting that backwards is not a compile
 * error: a coalesced occurrence silently loses events, and an uncoalesced state event delivers one
 * whole-company recompute per row.
 *
 * <p><del><strong>The vocabulary is one name, and it is the coalesced half (§153).</strong></del>
 * <del><strong>Two names since §167, and the second one decided the same way</strong> —
 * {@code documentation.links_changed} is a state, is recorded through
 * {@link Outbox#recordCoalesced}, and carries no payload. The past-tense form is <em>still</em> a
 * convention with no instance in it: {@code case.deleted} remains an example rather than an entry,
 * so the rule stated above has yet to discriminate anything, and {@link Outbox#record} still has no
 * caller in the product.</del>
 *
 * <p><del>That both entries landed on the same half is not a coincidence and is worth reading before
 * minting a third: <strong>a cross-module event in this codebase has so far always been consumed by
 * a whole-company recompute</strong>, and a recompute takes no arguments, so there is nothing
 * truthful for a payload to carry. The first name whose handler wants to know <em>which</em> row
 * moved is the one that has to answer {@link Outbox#record}'s standing debt — a consumer-side test
 * pinning the payload shape, because the compiler cannot see across that boundary.</del>
 *
 * <h2>EIGHT NAMES, AND THE PAST-TENSE HALF IS NOW THE MAJORITY — the paragraphs above are
 * answered</h2>
 *
 * <p>{@link #RUN_COMPLETED} and {@link #ITEM_ASSIGNED} are occurrences: each is its own event,
 * each carries a payload, and both are recorded through {@link Outbox#record}, which until this
 * slice had no product caller at all. The rule stated at the top of this file therefore starts
 * discriminating something rather than describing a half of the vocabulary nothing had used.
 *
 * <p><strong>Why notifications is the feature that had to be first.</strong> The observation above
 * — that a cross-module event here had always been consumed by an argument-less recompute — was
 * true and is not a law. A notification's entire content <em>is</em> the payload: "something in
 * this company finished" is not a message anybody can act on, so a coalesced form of either of
 * these names is unusable rather than merely lossy. Coalescing would also be untruthful in the
 * specific way {@link Outbox#recordCoalesced} describes: one event standing for several changes
 * could name only one of them.
 *
 * <p>The debt the paragraph above states as owed is paid where it is incurred, in
 * {@code com.tcms.integration}: a producer-side test and a consumer-side test pin each payload,
 * because the two modules may not share a class and a test on each side is what replaces the
 * compiler. See that package's own comment.
 *
 * <p><strong>Four more since the review-notifications slice</strong>, all occurrences and all
 * produced by {@code project.ReviewService}: {@link #REVIEW_REQUESTED}, {@link #REVIEW_ENDORSED},
 * {@link #REVIEW_CHANGES_REQUESTED} and {@link #REVIEW_APPROVED}. They are the first entries
 * produced by a <em>foundation</em> module, which is worth noting before a fifth: the rule that
 * this module holds names and not meanings is untouched, but a foundation module recording an
 * occurrence means the producer is a module every other module may call, so "who could have written
 * this row" is no longer answerable by module layer alone. It is still answerable by grep, which is
 * the property {@link OutboxHandler#eventType()} asks for.
 */
public final class OutboxEvents {

    /**
     * A company's execution results have moved — recorded, amended or ingested.
     *
     * <p>Coalesced, because its handlers are whole-company recomputes that take no arguments by
     * design ({@code ExecutionFactRefreshService}, {@code CaseHealthRecomputeService}), so fifty
     * verdicts in one run must not be fifty projections. It says which company and nothing else,
     * which is all a recompute needs and all a coalesced event can honestly carry.
     */
    public static final String RESULTS_CHANGED = "execution.results_changed";

    /**
     * A company's documentation links or the pages they point into have moved (§167).
     *
     * <p>Coalesced, for {@link #RESULTS_CHANGED}'s reason: its handler is a whole-company recompute
     * that takes no arguments, and a page edit that rewrites nine blocks must not be nine
     * projections.
     *
     * <p><strong>The name says "links" and the producers are wider than linking</strong>, which is
     * the part to read before adding a producer. {@code case_doc_link} copies a page's key, its
     * title and a block's text, so <em>editing a page</em> changes what a case's traceability panel
     * says exactly as <em>creating a link</em> does — and deleting a page removes rows the link
     * table still has. Under-recording is the defect (a panel quoting text nobody can find any
     * more); over-recording costs one idempotent recompute, which is what coalescing is for.
     */
    public static final String DOC_LINKS_CHANGED = "documentation.links_changed";

    /**
     * A test run reached {@code COMPLETED} — recorded by {@code execution.RunStatusService} inside
     * the transaction that closed it.
     *
     * <p>An occurrence, so it is {@link Outbox#record} and it carries a payload: which run, in
     * which project, under which display key, and the verdict counts. A consumer renders that
     * payload and nothing else — it may not read {@code test_run}, which is the constraint that
     * decides the payload's contents rather than a preference about them.
     *
     * <p><strong>Only {@code COMPLETED}.</strong> Aborting is not completing and reopening is not
     * an ending; a name that meant "the status moved" would make every subscriber re-derive which
     * moves it cared about, and the first one to get it wrong announces a reopened run as
     * finished.
     */
    public static final String RUN_COMPLETED = "execution.run_completed";

    /**
     * A board task was created with somebody on it — recorded by {@code work.BoardService} inside
     * the transaction that created it.
     *
     * <p>An occurrence with a payload for {@link #RUN_COMPLETED}'s reason. <strong>Creation is the
     * only producer, because it is the only assignment the product can perform</strong>:
     * {@code work.WorkItem} has exactly one mutator and it moves a card between columns, and its
     * own header records that editing a task's assignee "has no affordance anywhere in the SPA".
     * The name is past tense and stays correct when the field edit lands — that slice adds a
     * second producer here, not a second name.
     */
    public static final String ITEM_ASSIGNED = "work.item_assigned";

    /**
     * Somebody was asked to review an artifact — recorded by {@code project.ReviewService} inside
     * the transaction that wrote the {@code review_assignment} row.
     *
     * <p>An occurrence with a payload, for {@link #RUN_COMPLETED}'s reason. <strong>Only a request
     * that was actually written.</strong> Re-asking somebody already asked is idempotent and
     * records nothing: {@code ReviewService#store} returns early, and an event there would announce
     * a second time that a colleague had been asked once.
     *
     * <p>The four {@code review.*} names are all facts about a <em>review</em>, so none of them
     * fires in a project with §6g's review workflow switched off for that artifact type — there is
     * no review, nobody was asked, and there is nothing to have a verdict on.
     */
    public static final String REVIEW_REQUESTED = "review.requested";

    /**
     * A peer said the artifact looks good — {@code project.ReviewService}'s {@code LGTM} verdict.
     *
     * <p><strong>Not an approval, and the name is the whole reason this constant is
     * separate.</strong> {@code ReviewService#record}'s header refuses to write an
     * {@code APPROVAL} participation from the verdict endpoint, because that would be a second,
     * cheaper door to a sign-off gated on {@code *:approve}. A name that called it
     * {@code review.approved} would put that conflation on the wire, where a subscriber acts on it.
     * The sign-off is {@link #REVIEW_APPROVED}.
     */
    public static final String REVIEW_ENDORSED = "review.endorsed";

    /**
     * A peer asked for changes — {@code project.ReviewService}'s {@code CHANGES_REQUESTED} verdict.
     *
     * <p>Its own name rather than a verdict field on one "a peer spoke" event, for
     * {@link #RUN_COMPLETED}'s reason: one name would make every subscriber re-derive which
     * verdicts it cared about, and the two are opposite requests to act.
     *
     * <p>Advisory — it does not move the artifact off {@code IN_REVIEW}
     * ({@code ReviewParticipation.Verdict}).
     */
    public static final String REVIEW_CHANGES_REQUESTED = "review.changes_requested";

    /**
     * A review was signed off — recorded by {@code project.ReviewService#moved} on the arm that
     * closes an open review as {@code APPROVED} and writes the {@code APPROVAL} participation.
     *
     * <p><strong>One name for all three subject types.</strong> The three ladders — a case's, a
     * suite's, a plan's — converge on that one method, and approving a suite is the same act to a
     * subscriber as approving a case; the payload says which. A name per artifact would be three
     * constants, three payloads and three handlers for one fact.
     *
     * <p><strong>A bulk approval records one of these per artifact.</strong>
     * {@code CaseBulkService} moves up to 500 cases in one operation and calls {@code moved} for
     * each, so one act can produce 500 occurrences and, per subscribed channel, 500 messages. Each
     * is true and names a different subject, so coalescing them would be untruthful in exactly the
     * way {@link Outbox#recordCoalesced} describes; a digest belongs on the subscriber's side and
     * does not exist yet.
     */
    public static final String REVIEW_APPROVED = "review.approved";

    /**
     * An issue was created in somebody else's tracker — recorded by
     * {@code integration.tracker.TrackerIssueService} inside the transaction that wrote the
     * {@code tracker_issue_creation} ledger row (004, <em>The creation seam</em>).
     *
     * <p>An occurrence with a payload, for {@link #RUN_COMPLETED}'s reason — and the ninth name,
     * the first produced by {@code integration} rather than consumed by it. That direction is worth
     * noting: this module has until now been the outbox's only reader, and the paragraph above
     * about "who could have written this row" applies to it now too.
     *
     * <p><strong>It is a backstop, not the delivery path.</strong> The SPA files the issue and then
     * writes the {@code defect_link} itself, in two calls, because {@code execution} may not import
     * {@code integration}. This event covers the crash between them: {@code execution}'s handler
     * writes the same row from the payload, and the two writes collide on
     * {@code defect_link_execution_key_idx} rather than producing a twin. Which is also why the
     * follow-up call must carry its {@code bindingId} — without it the two rows name different
     * providers, the index does not collide, and one defect renders twice.
     */
    public static final String TRACKER_ISSUE_CREATED = "integration.tracker_issue_created";

    /**
     * A company's issues have moved — created, edited, transitioned or deleted (011, module M1).
     *
     * <p><strong>Coalesced</strong>, for {@link #RESULTS_CHANGED}'s reason and by the convention
     * stated at the top of this file: its one handler is a whole-company recompute that takes no
     * arguments ({@code tracker.IssueFactProjection}), so a person dragging fifty cards through a
     * board must not be fifty projections. It says which company and nothing else, which is all a
     * recompute needs and all a coalesced event can honestly carry.
     *
     * <p><strong>The plan proposed the other half and this is why it is not that.</strong> 011's
     * T3 Part B sketched {@code tracker.issue_changed} as an <em>occurrence</em> carrying an issue
     * id, arguing that a coalesced form "could name only one of fifty moves and leave forty-nine
     * rows stale". That argument holds only for a handler that rewrites the one row it is told
     * about. This handler rewrites every issue in the company, so there is nothing for a payload to
     * name and nothing left stale — and a name saying something <em>changed</em> recorded through
     * {@link Outbox#record} would be the backwards case this file's header warns about, delivering
     * one whole-company recompute per issue. {@code V3}'s own schema comment already names this
     * spelling ("fed by an event … tracker.IssueFactProjection, on the tracker.issues_changed
     * outbox event"), so the migration, the convention and the handler agree.
     *
     * <p>The producers are {@code tracker.IssueService} and {@code tracker.TransitionService}, each
     * inside the transaction that wrote the issue.
     */
    public static final String ISSUES_CHANGED = "tracker.issues_changed";

    private OutboxEvents() {
    }
}
