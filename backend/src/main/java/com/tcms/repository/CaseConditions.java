package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One case's two condition lists, resolved — <strong>the one implementation of the §6f resolution
 * order</strong>.
 *
 * <h2>The order, and why each step of it is the way it is</h2>
 *
 * <p>{@code V49}'s comment on {@code suite_condition} states it: "these attachments first, then the
 * case own {@code case_condition} rows, deduped by {@code condition_id} with the first occurrence
 * winning". <strong>It used to say "walking {@code suite.path} upward", and this header quoted that
 * for the fifteen sections between {@code V33} dropping the column and §190 noticing</strong> — the
 * order below is written for a chain and the chain has had exactly one element since §98. Spelled
 * out, per kind:
 *
 * <ol>
 *   <li><strong>Inherited, outermost ancestor first.</strong> The walk collects the case's own
 *       folder and every folder above it, then emits them root-downward — <em>which is now a list
 *       of one</em>; see {@link #ancestry} for why the shape is kept. A standing rule set at
 *       <em>Billing</em> applies before one set at <em>Billing › Refunds</em> because it is the
 *       broader precondition — "be logged in" precedes "have a settled payment", and a checklist
 *       that reverses them asks the tester to do the second thing first. Within one suite, the
 *       author's {@code position}.</li>
 *   <li><strong>Then the case's own</strong>, in its own {@code position} order. Last because the
 *       case is the innermost scope, on the same reasoning that ordered the ancestors.</li>
 *   <li><strong>Deduped by the library condition</strong>, first occurrence winning. Two folders
 *       attaching "Log in", or a case attaching what it already inherits, is one action to perform
 *       and one checkbox to tick — and a checklist that lists it twice trains the reader to stop
 *       reading it. First-wins means the surviving row is the <em>inherited</em> one, which is the
 *       more informative answer: it carries the ancestor's name and the warning that editing it
 *       reaches every case beneath. Dropping the inherited copy in favour of the case's own would
 *       hide an inheritance that is still in force.</li>
 * </ol>
 *
 * <p><strong>The two kinds are resolved separately and dedupe separately.</strong> A condition
 * attached as setup at the suite and as cleanup on the case is two genuinely different actions at
 * two different times — "reset the test payment data" before and after is exactly what a
 * {@code BOTH} library entry is for — and folding them together would silently discard one.
 *
 * <h2>The case's own folder counts as inherited, and that is not a rounding error</h2>
 *
 * <p>A condition attached to the suite the case sits in was not attached by the case. Editing it
 * still reaches every sibling in that folder, which is precisely what the SPA's "inherited from
 * <em>Billing</em>" marker warns about (plan §6f). Marking it local would remove the warning from
 * the most common case there is — the one where the standing rule lives on the folder the author
 * is actually looking at.
 *
 * <h2>Three ways a row disappears, all deliberate</h2>
 *
 * <ol>
 *   <li><strong>An opt-out.</strong> A {@code case_condition_optout} row against the inherited
 *       attachment — the toggle beside the inherited marker. Keyed on the attachment, so opting
 *       out at <em>Billing</em> leaves an identical attachment at <em>Refunds</em> in force; see
 *       {@link CaseConditionOptout}. <strong>It has had a writer only since §187</strong>
 *       ({@link #reconcileOptouts}); before that this paragraph, the entity's header and
 *       {@link CaseConditionOptoutRepository}'s all described a row no deployment contained. The
 *       rows it removes are still reported, in {@link Resolved#declinedPreconditions()} and its
 *       postcondition twin, because a decline nobody can see is one nobody can undo.</li>
 *   <li><strong>A retired library entry.</strong> Retirement is a soft delete because attachments
 *       survive it, so the attachment is still there pointing at a withdrawn procedure. Serving it
 *       would present the withdrawal as current — the same omission
 *       {@link SuiteConditionView} records, for the same reason.</li>
 *   <li><strong>An inline attachment.</strong> Excluded at both queries. The contract requires a
 *       {@code title} and an inline row has no column to hold one; nothing can create one today
 *       either. {@link SuiteConditionView} carries the whole argument and what closes it.</li>
 * </ol>
 *
 * <h2>Four queries, none of them per ancestor</h2>
 *
 * <p>The case's own attachments, the ancestors' attachments (one query for the whole chain), the
 * opt-outs, and the library entries behind everything that survived. A tree is where an N+1 stops
 * being cheap first, and walking up a chain is the shape that invites one query per level.
 */
@Component
public class CaseConditions {

    private final CaseConditionRepository own;
    private final SuiteConditionRepository inherited;
    private final CaseConditionOptoutRepository optouts;
    private final ConditionRepository library;

    public CaseConditions(
            CaseConditionRepository own,
            SuiteConditionRepository inherited,
            CaseConditionOptoutRepository optouts,
            ConditionRepository library) {

        this.own = own;
        this.inherited = inherited;
        this.optouts = optouts;
        this.library = library;
    }

    /**
     * Both lists for one case.
     *
     * @param liveSuites the project's live suites — the chain is walked in memory over these,
     *                   rather than through {@code ltree}'s {@code <@} operator, for the reason
     *                   {@code CaseService#suiteScope} gives: the tree is already loaded, the walk
     *                   is over tens of folders, and the operator needs a native query that would
     *                   drop the Hibernate company filter. Only live suites are ever in it, so a
     *                   soft-deleted folder stops inheriting to the cases still beneath it —
     *                   consistent with the case list, which drops those cases entirely.
     */
    Resolved forCase(TestCase testCase, List<Suite> liveSuites) {
        List<Suite> chain = ancestry(testCase.getSuiteId(), liveSuites);

        List<SuiteCondition> reaching = chain.isEmpty()
                // The case's folder is soft-deleted, so nothing above it is reachable either. Not
                // merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
                // error.
                ? List.of()
                : inherited.findForSuites(chain.stream().map(Suite::getId).toList());

        Set<UUID> declined = declinedAmong(testCase.getId(), reaching);

        List<SuiteCondition> inheritedRows = reaching.stream()
                .filter(attachment -> !declined.contains(attachment.getId()))
                .toList();
        List<SuiteCondition> declinedRows = declined.isEmpty()
                ? List.of()
                : reaching.stream()
                        .filter(attachment -> declined.contains(attachment.getId()))
                        .toList();

        List<CaseCondition> ownRows = own.findForCase(testCase.getId());

        // Resolved over EVERY reaching attachment rather than only the surviving ones, because a
        // declined row still has to be rendered — with its title, so the author can see what it is
        // they turned off. Resolving twice would be a second query and a second answer.
        Map<UUID, Condition> resolved = resolve(reaching, ownRows);
        Map<UUID, String> suiteNames = new HashMap<>();
        chain.forEach(suite -> suiteNames.put(suite.getId(), suite.getName()));

        return new Resolved(
                list(ConditionKind.PRE, chain, inheritedRows, ownRows, resolved, suiteNames),
                list(ConditionKind.POST, chain, inheritedRows, ownRows, resolved, suiteNames),
                declinedList(ConditionKind.PRE, chain, declinedRows, resolved, suiteNames),
                declinedList(ConditionKind.POST, chain, declinedRows, resolved, suiteNames));
    }

    /**
     * The attachments a case filed in {@code suiteId} inherits, in the two bounds §6f needs —
     * <strong>one query answering both</strong>.
     *
     * <p>Read once per save and used for three things that must not disagree: {@link #declinable()}
     * is what {@link CaseEdit#validate} checks a submitted id against and the removal bound
     * {@link #reconcileOptouts} applies, and {@link Reaching#all()} is the <em>retention</em> bound
     * both of those also take (§192). Two reads would be two answers to "what does this folder
     * impose" a few lines apart, and the write would be checked against one while being applied
     * under the other — {@link CaseUpdateService}'s reason for resolving the vocabulary before
     * validating, at a second table.
     *
     * <p><strong>The two sets differ by exactly the retired entries</strong>, and each bound wants
     * a different one. Declining is an authoring act, so it is bounded by what the author can see
     * ({@link #reconcile}'s rule, transferred): a retired entry's attachment is on no list, so it
     * can be neither declined nor un-declined. <em>Retention</em> is not an authoring act and must
     * not use that bound — a decline whose entry was retired is still a decline of a rule this
     * folder imposes, and the entry can be restored.
     *
     * <p>Takes a {@code suiteId} rather than a {@link TestCase} because both callers that matter
     * ask about a folder the case is not in yet: a create has no row to ask, and an edit that moves
     * the case asks about the folder it is going to. Inventing a half-built {@link TestCase} to
     * satisfy a signature is how a row that does not exist reaches code that assumes a stored one.
     */
    Reaching reachingAt(UUID suiteId, List<Suite> liveSuites) {
        List<Suite> chain = ancestry(suiteId, liveSuites);
        if (chain.isEmpty()) {
            return Reaching.NONE;
        }
        List<SuiteCondition> reaching =
                inherited.findForSuites(chain.stream().map(Suite::getId).toList());
        if (reaching.isEmpty()) {
            return Reaching.NONE;
        }

        Set<UUID> referenced = new LinkedHashSet<>();
        reaching.forEach(attachment -> referenced.add(attachment.getConditionId()));
        Set<UUID> live = new HashSet<>();
        library.findLiveByIds(referenced).forEach(condition -> live.add(condition.getId()));

        Set<UUID> all = new LinkedHashSet<>();
        Set<UUID> declinable = new LinkedHashSet<>();
        for (SuiteCondition attachment : reaching) {
            all.add(attachment.getId());
            if (live.contains(attachment.getConditionId())) {
                declinable.add(attachment.getId());
            }
        }
        return new Reaching(Set.copyOf(all), Set.copyOf(declinable));
    }

    /**
     * One folder's imposition on a case filed in it.
     *
     * @param all        every {@code suite_condition} reaching the case, retired entries included —
     *                   the <strong>retention</strong> bound (§192). A stored decline outside this
     *                   set is a decision about a folder the case is not in.
     * @param declinable the subset whose library entry is still live — the <strong>authoring</strong>
     *                   bound, which is what a client can name.
     */
    record Reaching(Set<UUID> all, Set<UUID> declinable) {

        static final Reaching NONE = new Reaching(Set.of(), Set.of());
    }

    /**
     * Every decline this case carries — <strong>unrestricted</strong>, including the ones no list
     * shows.
     *
     * <p>{@link #liveAttachedIdsOf}'s twin for the other half of §6f, and the one place the two
     * differ, deliberately. That method is bounded by what the project still offers because its
     * value is a <em>reconcile</em> input; this one feeds {@link CaseEdit#normalizeDeclines}, whose
     * output is hashed, and a hash input must be a function of the stored row and nothing else.
     *
     * <p>Since §192 the rows outside a client's view are the retired-entry ones alone, so this
     * differs from what any list shows by less than it did — but it is still not the same set, and
     * the reason to read it unrestricted is unchanged.
     *
     * <p>Bound it here instead and the bulk path breaks in the way {@link CaseBulkService} keeps
     * warning about: {@link CaseEdit#of} would have to resolve a suite chain and a library per case
     * to reproduce the same restriction, and any case whose declines it restricted differently
     * would hash differently from the one the next ordinary save computes for identical content —
     * crediting that save with a change nobody made.
     */
    List<UUID> storedDeclinedIds(UUID caseId) {
        List<UUID> stored = new ArrayList<>();
        for (CaseConditionOptout row : optouts.findForCase(caseId)) {
            stored.add(row.getSuiteConditionId());
        }
        return stored;
    }

    /**
     * Brings this case's declines in line with the submitted set, in the caller's transaction —
     * <strong>the writer {@code case_condition_optout} shipped in {@code V1} without</strong>.
     *
     * <h2>Two bounds, and they answer opposite questions</h2>
     *
     * <p><strong>The retention bound, {@code retained} (§192).</strong> A decline is an exception to
     * a rule this case's folder imposes, so it lives exactly as long as its {@code suite_condition}
     * hangs off that folder. A row outside {@code retained} is deleted <em>whatever the request
     * said</em>, because the only way out of that set is the case being moved — and a move is a
     * statement about which standing rules govern the case, since inheritance follows filing and
     * nothing else (§98). The clone path has answered this question the same way since §129:
     * {@link #declinedAtSuite} carries a clone's declines only when it lands in the source's own
     * folder, and a move that kept them would have the same case declining a rule after relocation
     * that a copy of it would not.
     *
     * <p><strong>The removal bound, {@code declinable}.</strong> {@link #reconcile}'s
     * retired-attachment rule, transferred to the other table. Among the rows that survive the
     * first bound, only those in {@code declinable} are candidates for deletion — so a decline whose
     * library entry has been retired survives a save that does not mention it, because that row was
     * on no list the client saw and reading its absence as an instruction would delete a decision
     * the client never had the chance to restate. That is a genuinely temporary invisibility:
     * restoring the entry brings the attachment, the decline and the toggle back together.
     *
     * <p>~~<strong>The cost is stated rather than discovered</strong>: an inert decline is invisible
     * state. Move the case back under <em>Billing</em> and a rule the author turned off six months
     * ago is off again, with nothing on the screen having said so in between. The alternative —
     * deleting declines on a move — loses the decision on an action that said nothing about
     * conditions, which is the worse of the two~~ → <strong>superseded by §192, and the reversal
     * turns on that last clause being wrong</strong>. A move is not an action that says nothing
     * about conditions; it is the only action that decides which ones apply. What §187 protected
     * was a decision the author could not see, could not undo and could not be warned about — it
     * re-armed itself the day anything moved the case back. The trade is not symmetric with the
     * retired-entry one above, because retirement is reversible and a move is a new decision.
     *
     * @param retained every attachment reaching the case's suite <em>after this save</em>, retired
     *                 entries included — {@link Reaching#all()} at the post-save folder
     * @param wanted the complete set, already validated against {@code declinable} and already
     *               intersected with {@code retained} by {@link CaseEdit}
     * @param companyId written onto every new row for {@link #reconcile}'s reason: RLS refuses an
     *                  insert whose value does not match {@code app.current_company}
     */
    void reconcileOptouts(
            UUID caseId,
            Set<UUID> declinable,
            Set<UUID> retained,
            List<UUID> wanted,
            UUID companyId) {

        List<CaseConditionOptout> stored = optouts.findForCase(caseId);

        Set<UUID> keep = new HashSet<>(wanted);
        List<CaseConditionOptout> stale = new ArrayList<>();
        Set<UUID> alreadyDeclined = new HashSet<>();

        for (CaseConditionOptout row : stored) {
            alreadyDeclined.add(row.getSuiteConditionId());
            if (!retained.contains(row.getSuiteConditionId())) {
                // The case has left the folder that imposed the rule. Deleted rather than kept —
                // see the header; this is the half §187 got wrong.
                stale.add(row);
                continue;
            }
            if (!declinable.contains(row.getSuiteConditionId())) {
                // Still imposed by this folder, but behind a retired entry, so it was on no list
                // the client saw. Protected rather than deleted — see the header.
                continue;
            }
            if (!keep.contains(row.getSuiteConditionId())) {
                stale.add(row);
            }
        }

        List<CaseConditionOptout> added = new ArrayList<>();
        for (UUID attachmentId : wanted) {
            if (!alreadyDeclined.contains(attachmentId)) {
                added.add(CaseConditionOptout.of(caseId, attachmentId, companyId));
            }
        }

        if (!stale.isEmpty()) {
            optouts.deleteAll(stale);
            optouts.flush();
        }
        if (!added.isEmpty()) {
            optouts.saveAll(added);
        }
    }

    /**
     * The declines a clone should carry from its source — every one whose attachment hangs off
     * {@code suiteId} itself.
     *
     * <p>{@link CaseCloneService}'s rule, written there before there was a writer to obey it: "a
     * clone landing in the source's suite should carry the opt-outs whose {@code suite_condition}
     * belongs to that suite, and one landing elsewhere should carry none." Keyed on the attachment
     * rather than the folder chain because that is what the row is keyed on — a clone landing two
     * folders down inherits <em>Billing</em>'s attachment through a different path, but the
     * decision to decline it was made about the case's own filing and does not obviously travel.
     */
    List<UUID> declinedAtSuite(UUID caseId, UUID suiteId) {
        List<CaseConditionOptout> stored = optouts.findForCase(caseId);
        if (stored.isEmpty() || suiteId == null) {
            return List.of();
        }
        Set<UUID> declined = new HashSet<>();
        stored.forEach(row -> declined.add(row.getSuiteConditionId()));

        List<UUID> carried = new ArrayList<>();
        for (SuiteCondition attachment : inherited.findForSuites(List.of(suiteId))) {
            if (declined.contains(attachment.getId())) {
                carried.add(attachment.getId());
            }
        }
        return carried;
    }

    /**
     * The case's OWN attachments of one kind, in order, restricted to library entries the project
     * still offers — the <strong>one</strong> implementation of that filter, read by both places
     * that need it.
     *
     * <p>{@link CaseClassifications#liveValueIdsOf}'s twin, and the bound is the same one for the
     * same reason. A condition is retired by a soft delete because attachments to it survive, and
     * {@link #forCase} above <em>drops</em> an attachment whose entry no longer resolves — so a
     * client is never shown a retired attachment and therefore cannot resubmit one.
     *
     * <p>This feeds {@link CaseEdit#validate} as the value an edit carries when the request omits
     * the field, and it must agree with what {@code GET} serves: two implementations would let the
     * attachments a client is served differ from the ones a save preserves, which shows up as a
     * condition that disappears on a save that did not mention it.
     */
    List<UUID> liveAttachedIdsOf(UUID caseId, ConditionKind kind) {
        List<CaseCondition> rows = own.findForCase(caseId);
        Set<UUID> live = liveLibraryIdsAmong(rows);

        List<UUID> ids = new ArrayList<>();
        for (CaseCondition row : rows) {
            if (row.getKind() == kind && live.contains(row.getConditionId())) {
                ids.add(row.getConditionId());
            }
        }
        return ids;
    }

    /**
     * Brings the case's own attachments in line with the two submitted lists, in the caller's
     * transaction.
     *
     * <h2>The retired bound, which is the whole reason this is not a delete-all-and-reinsert</h2>
     *
     * <p>{@link CaseClassifications#reconcile}'s rule, transferred: only attachments whose library
     * entry the project <em>still offers</em> are candidates for removal. Without it, the first save
     * of any case attaching a since-retired condition would silently discard it — an attachment
     * nobody chose to drop, removed by a save that never mentioned it, and invisible afterwards
     * because the thing that would have shown it is the thing that was dropped.
     *
     * <p>The consequence is the same one stated there: a retired entry's attachment is
     * <strong>immutable through this API</strong>. It cannot be added (validation refuses an entry
     * the project does not offer) and cannot be removed (this does not reach it). It stops existing
     * when the entry is restored and then detached, or when the case row is deleted.
     *
     * <h2>Rows are rewritten in place where they can be, because POSITION is the content</h2>
     *
     * <p>Unlike a classification assignment, these rows carry an order and that order is the
     * procedure. A row already attaching the wanted entry keeps its identity and takes its new
     * position; only genuinely new attachments are inserted and genuinely removed ones deleted.
     *
     * <p>Nothing references {@code case_condition.id} — {@code case_condition_optout} keys on
     * {@code suite_condition_id}, not this table — so a delete-and-reinsert would in fact be
     * <em>safe</em> here, unlike {@link CaseSteps} where results hang off the id. It is still not
     * what happens, for the reason {@link CaseClassifications} gives about churn: rewriting every
     * row of every case on every save makes the table's history unreadable and buys nothing.
     *
     * @param submitted one list per kind, already validated, in the author's order
     * @param companyId written onto every new row — RLS refuses an insert whose value does not match
     *                  {@code app.current_company} (ERD §0.1), so this is what makes the row
     *                  readable again rather than one this connection can write once and never see
     */
    void reconcile(
            TestCase testCase,
            List<UUID> preconditions,
            List<UUID> postconditions,
            UUID companyId) {

        List<CaseCondition> rows = own.findForCase(testCase.getId());
        Set<UUID> live = liveLibraryIdsAmong(rows);

        List<CaseCondition> stale = new ArrayList<>();
        List<CaseCondition> added = new ArrayList<>();

        for (ConditionKind kind : ConditionKind.values()) {
            List<UUID> wanted = kind == ConditionKind.PRE ? preconditions : postconditions;

            Map<UUID, CaseCondition> reusable = new LinkedHashMap<>();
            for (CaseCondition row : rows) {
                if (row.getKind() != kind) {
                    continue;
                }
                if (!live.contains(row.getConditionId())) {
                    // Retired since it was attached, or an inline row nothing renders. Not a
                    // candidate for removal — see the header. Deliberately not reusable either: it
                    // cannot be in `wanted`, because validation would have refused it.
                    continue;
                }
                // First row wins for a duplicate attachment; the extra is stale, which is also how
                // a list that used to carry the same entry twice collapses to one.
                if (reusable.putIfAbsent(row.getConditionId(), row) != null) {
                    stale.add(row);
                }
            }

            int position = 0;
            for (UUID conditionId : wanted) {
                CaseCondition existing = reusable.remove(conditionId);
                if (existing != null) {
                    existing.moveTo(position);
                } else {
                    added.add(CaseCondition.of(
                            testCase.getId(), conditionId, kind, position, companyId));
                }
                position++;
            }
            // Whatever is left was attached, is still offered, and was not resubmitted.
            stale.addAll(reusable.values());
        }

        if (!stale.isEmpty()) {
            own.deleteAll(stale);
            own.flush();
        }
        if (!added.isEmpty()) {
            own.saveAll(added);
        }
    }

    /**
     * Which of these rows' library entries are still live.
     *
     * <p>One query for the whole set. Inline rows carry a null {@code condition_id} and are
     * therefore never in the result, which is what makes them fall on the protected side of the
     * bound above — correct, since nothing renders them and so nothing can resubmit them either.
     */
    private Set<UUID> liveLibraryIdsAmong(List<CaseCondition> rows) {
        Set<UUID> referenced = new HashSet<>();
        for (CaseCondition row : rows) {
            if (row.getConditionId() != null) {
                referenced.add(row.getConditionId());
            }
        }
        if (referenced.isEmpty()) {
            // An empty IN () is a syntax error rather than an empty result.
            return Set.of();
        }
        Set<UUID> live = new HashSet<>();
        for (Condition condition : library.findLiveByIds(referenced)) {
            live.add(condition.getId());
        }
        return live;
    }

    /**
     * The four lists, in the shape {@link TestCaseDetailView} takes them.
     *
     * <p>The declined two are <strong>not</strong> a flag on the first two, and the reason is the
     * version snapshot: {@link CaseVersions} serializes the whole detail view as the authored
     * version's body and {@code CaseFacts} pins a run's checklist out of that snapshot's
     * {@code preconditions}. A flag would put declined rows into every checklist of every run
     * started after the decline, distinguished by a boolean the pin's reader has never heard of.
     * Two lists keep {@code preconditions} meaning exactly what it meant — the procedure — and say
     * the rest beside it.
     */
    record Resolved(List<CaseConditionRefView> preconditions,
                    List<CaseConditionRefView> postconditions,
                    List<CaseConditionRefView> declinedPreconditions,
                    List<CaseConditionRefView> declinedPostconditions) {
    }

    /**
     * The case's folder and every live folder above it, <strong>outermost first</strong>.
     *
     * <p>Empty when the folder is not live — a case whose suite was soft-deleted inherits nothing,
     * rather than inheriting from whatever survives above the gap. Half a chain is not a rule
     * anyone wrote.
     *
     * <p><strong>It is no longer a walk (§98), and the method survives its own name.</strong> It
     * climbed {@code parent_id} to the root, guarded against a cycle — which no constraint in
     * {@code V1} prevented — by bounding on the number of live suites rather than the chain length.
     * A flat suite has no ancestors, so the chain is the case's own suite or nothing, and what was
     * a loop is a lookup. Kept as a method, and kept returning a list, because everything below
     * reads a chain and the shape of an inheritance that could one day widen is a list.
     *
     * <p><strong>Inheritance deliberately did NOT widen to the other two membership arms.</strong>
     * A case added to a suite, or matched by one of its tag rules, does not pick up that suite's
     * conditions — only the case's filing does. Filing says where a case lives and the standing
     * rules of that place apply to it; a tag rule says a collection wants to see it, which is not a
     * claim about how it is run. Widening is a decision with a slice behind it, not a side effect
     * of flattening — and it would make a case's preconditions depend on who tagged what, changing
     * under an author who never touched the case.
     */
    private static List<Suite> ancestry(UUID suiteId, List<Suite> liveSuites) {
        if (suiteId == null) {
            return List.of();
        }
        for (Suite suite : liveSuites) {
            if (suite.getId().equals(suiteId)) {
                return List.of(suite);
            }
        }
        // The case's suite is soft-deleted, or belongs to another project. Nothing inherits.
        return List.of();
    }

    /**
     * Which of these attachments this case has declined — usually none.
     *
     * <p>Restricted to the attachments passed in rather than returning every stored row, because a
     * decline whose library entry has been retired is not renderable — it has no title — and naming
     * it would invite a caller to render it. Since §192 that is the <em>only</em> stored row this
     * restriction can drop: a decline against a folder the case has left no longer exists, because
     * the move that took the case out of the folder deleted it ({@link #reconcileOptouts}).
     */
    private Set<UUID> declinedAmong(UUID caseId, List<SuiteCondition> attachments) {
        if (attachments.isEmpty()) {
            return Set.of();
        }
        Set<UUID> stored = new HashSet<>();
        optouts.findForCase(caseId).forEach(row -> stored.add(row.getSuiteConditionId()));
        if (stored.isEmpty()) {
            // The ordinary state: a case inherits everything above it unless it says otherwise.
            return Set.of();
        }
        Set<UUID> declined = new HashSet<>();
        for (SuiteCondition attachment : attachments) {
            if (stored.contains(attachment.getId())) {
                declined.add(attachment.getId());
            }
        }
        return declined;
    }

    /**
     * One kind's declined list — the attachments this case turned off, in chain order.
     *
     * <p><strong>Not de-duplicated by library entry, unlike {@link #list}.</strong> "Log in"
     * attached at <em>Billing</em> and again at <em>Refunds</em> is two attachments and two
     * independent decisions, so declining one leaves the other in force; a list keyed on the
     * condition would render one row for two toggles and make the second unreachable. Nor is it
     * deduplicated <em>against</em> the resolved list: a case that declined the inner attachment
     * still inherits the outer one, so "Log in" legitimately appears in both lists at once, once as
     * a rule that applies and once as one that was turned off.
     */
    private static List<CaseConditionRefView> declinedList(
            ConditionKind kind,
            List<Suite> chain,
            List<SuiteCondition> declinedRows,
            Map<UUID, Condition> resolved,
            Map<UUID, String> suiteNames) {

        if (declinedRows.isEmpty()) {
            return List.of();
        }
        List<CaseConditionRefView> entries = new ArrayList<>();
        for (Suite suite : chain) {
            for (SuiteCondition attachment : declinedRows) {
                if (attachment.getKind() != kind || !attachment.getSuiteId().equals(suite.getId())) {
                    continue;
                }
                Condition condition = resolved.get(attachment.getConditionId());
                if (condition == null) {
                    // Retired since it was declined. Dropped for {@link #list}'s reason — serving a
                    // withdrawn procedure presents it as current — and the decline itself survives,
                    // because reconcileOptouts' bound protects what no list shows.
                    continue;
                }
                entries.add(CaseConditionRefView.inheritedFrom(
                        attachment, condition, suiteNames.get(suite.getId())));
            }
        }
        return List.copyOf(entries);
    }

    /** Every referenced library entry that is still live, in one query, keyed by its id. */
    private Map<UUID, Condition> resolve(
            List<SuiteCondition> inheritedRows, List<CaseCondition> ownRows) {

        // LinkedHashSet: de-duplicated, because one condition referenced from several places is
        // the ordinary case — that reuse is what the library is for.
        Set<UUID> referenced = new LinkedHashSet<>();
        inheritedRows.forEach(row -> referenced.add(row.getConditionId()));
        ownRows.forEach(row -> referenced.add(row.getConditionId()));
        if (referenced.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Condition> byId = new HashMap<>();
        library.findLiveByIds(referenced).forEach(condition -> byId.put(condition.getId(), condition));
        return byId;
    }

    /**
     * One kind's list: the ancestors' attachments root-downward, then the case's own, deduped.
     *
     * <p>Ordered by walking the chain rather than by sorting the attachments, because the ordering
     * key is the ancestor's <em>depth</em> and no column carries it. {@code suite_condition.position}
     * is sibling order within one suite and says nothing about which suite comes first.
     */
    private static List<CaseConditionRefView> list(
            ConditionKind kind,
            List<Suite> chain,
            List<SuiteCondition> inheritedRows,
            List<CaseCondition> ownRows,
            Map<UUID, Condition> resolved,
            Map<UUID, String> suiteNames) {

        List<CaseConditionRefView> entries = new ArrayList<>();
        Set<UUID> emitted = new HashSet<>();

        for (Suite suite : chain) {
            for (SuiteCondition attachment : inheritedRows) {
                if (attachment.getKind() != kind || !attachment.getSuiteId().equals(suite.getId())) {
                    continue;
                }
                Condition condition = resolved.get(attachment.getConditionId());
                if (condition == null || !emitted.add(condition.getId())) {
                    // Retired, or already emitted from a folder further up. Both drop out; see the
                    // class header for why first-wins means the inherited copy survives.
                    continue;
                }
                entries.add(CaseConditionRefView.inheritedFrom(
                        attachment, condition, suiteNames.get(suite.getId())));
            }
        }

        for (CaseCondition attachment : ownRows) {
            if (attachment.getKind() != kind) {
                continue;
            }
            Condition condition = resolved.get(attachment.getConditionId());
            if (condition == null || !emitted.add(condition.getId())) {
                continue;
            }
            entries.add(CaseConditionRefView.own(attachment, condition));
        }

        return List.copyOf(entries);
    }
}
