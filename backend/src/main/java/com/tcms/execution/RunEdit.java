package com.tcms.execution;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated, normalized create of one run — and, with it, the <strong>resolved case set</strong>
 * the run will materialise an execution for.
 *
 * <p>The case set is part of the validated value rather than something the service recomputes,
 * because deciding it is most of what validating a run means: it is what "is this run creatable"
 * actually asks. Splitting the decision from the check is how a run gets created with a scope
 * different from the one that was checked.
 *
 * <h2>Where the cases come from, in order</h2>
 *
 * <ol>
 *   <li><strong>The three explicit arms, when the body carries any of them</strong> —
 *       {@code caseIds}, {@code suiteIds} and {@code tags}, <strong>unioned</strong> and deduped by
 *       {@link CaseFacts#caseIdsInRunScope} (§99). An explicit selection wins, and it is how an
 *       ad-hoc run is created: three named cases, a whole suite, or everything tagged
 *       {@code smoke}.</li>
 *   <li><strong>Otherwise the plan's scope, expanded.</strong> The same expansion the plan's own
 *       {@code caseCount} displays ({@link CaseFacts#caseIdsInPlanScope}), so a run created from a
 *       plan has the length the previous screen said it would.</li>
 * </ol>
 *
 * <p>Not the union of the two. A body carrying both a plan and any explicit arm means "this run is
 * against that plan, and here is the subset we are doing this pass" — which is a real thing to want
 * during a stabilisation — and unioning them would silently add back the cases somebody explicitly
 * narrowed away. The run still records its {@code planId}, so the narrowing is visible as a run
 * shorter than its plan rather than as a lost association.
 *
 * <p><strong>The three arms union with each other and substitute for the plan, which is one rule
 * and not two.</strong> The arms are how the caller says what this run covers; the plan is a
 * default for when they did not say. §99 widened the first half from one arm to three and left the
 * second alone deliberately: the alternative — "cases replace the plan, but suites and tags add to
 * it" — is a rule nobody could predict from the request.
 *
 * <h2>The three arms are validated differently, and each difference is a decision</h2>
 *
 * <p>A {@code caseId} or a {@code suiteId} naming nothing live in this project is a
 * <strong>field error</strong>: an id is a reference to something the caller has already seen, so
 * one that resolves to nothing means the two of you disagree about what exists. A <strong>tag</strong>
 * that matches nothing is <strong>accepted</strong>, for {@code SuiteEdit}'s reason — there is no
 * vocabulary to check it against, and refusing a run because one of its three tags is currently
 * unused would be a validator inventing a workflow order. What catches the typo is the emptiness
 * check below, which is the honest place for it: the request is refused when it would produce
 * nothing, not when one arm of it happens to be quiet.
 *
 * <h2>A run with nothing to execute is refused</h2>
 *
 * <p>Unlike a plan, whose empty scope is an ordinary intermediate state. A run is a commitment to
 * test a specific set on a specific build; an empty one has no checklist, so every count on it is
 * zero and it renders <strong>as though it had finished</strong> — 0 of 0, 100%, complete. The
 * person who created it would have to notice the absence of rows to know it never started. Refusing
 * at the field the caller can act on is the honest answer, and it is the one shape of "successful
 * write that produced nothing usable" this module can still avoid.
 */
record RunEdit(
        String name,
        UUID planId,
        String releaseVersion,
        String buildNumber,
        String commitSha,
        String branch,
        UUID assigneeId,
        List<UUID> caseIds) {

    /** A run name is a label — "Regression 4.3.0", "Smoke, build 4471". */
    static final int MAX_NAME = 200;

    /** {@code release_version} and {@code build_number} are both short identifiers, not prose. */
    static final int MAX_RELEASE = 100;

    /**
     * A git object name — 7 to 40 hex characters, an abbreviated sha through a full SHA-1.
     *
     * <p><strong>This is a second copy of {@code documentation.ExternalLinkService.SHA} and it stays
     * a copy.</strong> The obvious home would be {@code api}, and that module's charter refuses it in
     * as many words: <em>"a helper two modules happen to share does not qualify — that is a copy, or
     * an event."</em> A regex is not a shape the contract defines and nothing here serves it on the
     * wire, so importing it would widen the one module in the application that declares no
     * dependencies into the grab bag §125 refused. The two modules may not import each other either
     * (plan §3), which leaves {@code RunEdit.MAX_TAG}'s answer: spell it again, name the other copy,
     * and let a test pin them equal — {@code RunEditTest.gitObjectNameRuleMatchesTheDocumentationLane}.
     *
     * <p>The bound is loose at the bottom on purpose and refuses 64-character SHA-256 names, both
     * for the reasons the other copy's header gives. Widening it is one number in two places, and
     * the test is what says so.
     */
    static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,40}");

    /**
     * The longest branch name a run may record.
     *
     * <p>Longer than {@link #MAX_RELEASE} and deliberately not the same number: a release version is
     * a short identifier a human types, where a ref name is a path a tool generates —
     * {@code dependabot/gradle/org.springframework.boot-3.5.6} is ordinary and already past 50. 255
     * is a single path component on every filesystem git stores loose refs on, so a name longer than
     * this cannot be a branch in the repository that produced it.
     *
     * <p><strong>There is no shape check beyond the length</strong>, which is the difference between
     * this field and {@link #SHA}'s. What a valid ref name is belongs to the team's git — refusing
     * one would be this product deciding how somebody's repository is configured — and unlike a
     * commit id, a branch name that turns out to be wrong is wrong in a way its reader can see.
     */
    static final int MAX_BRANCH = 255;

    /**
     * The longest tag a run's scope may name, and it <strong>must stay {@code SuiteEdit.MAX_TAG}'s
     * number</strong>: a scope entry longer than a tag can be is one that can never match anything.
     *
     * <p>Spelled again rather than imported, for {@code RunCreateService.APPROVE_CASE}'s reason —
     * the constant lives in {@code repository} and this module may not depend on it (plan §3). The
     * duplication is real and the alternative is a port whose only job is to hand back a number.
     * What holds them together is {@code CaseEdit}'s own cap, which both are copies of: a tag
     * longer than that cannot be stored, so a rule or a scope entry above it is unmatchable by
     * construction rather than by agreement.
     */
    static final int MAX_TAG = 60;

    /**
     * The most executions one create will materialise.
     *
     * <p>Every one is an inserted row inside a single transaction, and the transaction holds the
     * company's {@code key_sequence} lock for its whole duration — so an unbounded run create is an
     * unbounded stall on <em>every other creation in the company</em>. 5,000 is past any manual
     * regression pass and short of an import, which is the bulk slice's problem and has ERD §5's
     * range allocation waiting for it.
     */
    static final int MAX_EXECUTIONS = 5000;

    /** Sealed so the caller's mapping is exhaustive. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code TestRunInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(RunEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create against facts the service has already looked up.
     *
     * @param planExists     whether {@code planId} names a live plan <em>in this project</em>. False
     *                       both for a plan that is not there and for one in a sibling project — the
     *                       distinction is the one §6t refuses to publish, and the field error is
     *                       the same sentence either way.
     * @param liveCaseIds    of the requested {@code caseIds}, those naming a live case here
     * @param liveSuiteIds   of the requested {@code suiteIds}, those naming a live suite here.
     *                       Checked for the same reason and by the same predicate — an id from a
     *                       <em>sibling project in this company</em> passes the foreign key, the
     *                       company column and RLS, so this is the only thing that refuses it.
     * @param resolvedCases  the case set this run would cover, already resolved in the order it will
     *                       be materialised
     * @param assigneeExists whether {@code assigneeId} names a member of this company
     * @param pinnableCases  of {@code resolvedCases}, those that have a current version row to pin
     */
    static Outcome validateCreate(
            RunWriteRequest request,
            boolean planExists,
            Set<UUID> liveCaseIds,
            Set<UUID> liveSuiteIds,
            List<UUID> resolvedCases,
            boolean assigneeExists,
            Set<UUID> pinnableCases) {

        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();

        String name = PlanEdit.trimToNull(request.name());
        if (name == null) {
            PlanEdit.error(errors, "name", "Enter a run name.");
        } else if (name.length() > MAX_NAME) {
            PlanEdit.error(errors, "name", "Keep the name under " + MAX_NAME + " characters.");
        }

        if (request.planId() != null && !planExists) {
            PlanEdit.error(errors, "planId", "That plan is not in this project any more.");
        }

        String releaseVersion = PlanEdit.trimToNull(request.releaseVersion());
        if (releaseVersion != null && releaseVersion.length() > MAX_RELEASE) {
            PlanEdit.error(errors, "releaseVersion",
                    "Keep the release version under " + MAX_RELEASE + " characters.");
        }

        String buildNumber = PlanEdit.trimToNull(request.buildNumber());
        if (buildNumber != null && buildNumber.length() > MAX_RELEASE) {
            PlanEdit.error(errors, "buildNumber",
                    "Keep the build number under " + MAX_RELEASE + " characters.");
        }

        // Trimmed and stored as given — NOT lower-cased, which is a real choice with a cost. Two
        // runs on one commit whose shas differ in case would not group together, and nothing groups
        // by commit today. It stays uncased because `doc_link.external_commit_sha` stores what it was
        // given too, and one value with two normalizations in one product is the worse defect: the
        // day something does compare them, they must already agree.
        String commitSha = PlanEdit.trimToNull(request.commitSha());
        if (commitSha != null && !SHA.matcher(commitSha).matches()) {
            // ExternalLinkService's sentence, minus its clause about pinning lines — nothing here is
            // pinned to a line — and plus the field this caller almost certainly meant, which is
            // sitting immediately beside this one.
            PlanEdit.error(errors, "commitSha",
                    "That is not a commit id — 7 to 40 hexadecimal characters. A branch or tag name "
                            + "moves; record it as the branch instead.");
        }

        String branch = PlanEdit.trimToNull(request.branch());
        if (branch != null && branch.length() > MAX_BRANCH) {
            PlanEdit.error(errors, "branch",
                    "Keep the branch name under " + MAX_BRANCH + " characters.");
        }

        if (request.assigneeId() != null && !assigneeExists) {
            PlanEdit.error(errors, "assigneeId", "That person is not a member of this company.");
        }

        List<UUID> requestedCases = PlanEdit.distinct(request.caseIds());
        for (UUID caseId : requestedCases) {
            if (!liveCaseIds.contains(caseId)) {
                PlanEdit.error(errors, "caseIds",
                        "One of the selected cases is not in this project any more.");
                break;
            }
        }

        List<UUID> requestedSuites = PlanEdit.distinct(request.suiteIds());
        for (UUID suiteId : requestedSuites) {
            if (!liveSuiteIds.contains(suiteId)) {
                PlanEdit.error(errors, "suiteIds",
                        "One of the selected suites is not in this project any more.");
                break;
            }
        }

        List<String> requestedTags = tagsOf(request);
        for (String tag : requestedTags) {
            if (tag.length() > MAX_TAG) {
                PlanEdit.error(errors, "tags", "Keep each tag under " + MAX_TAG + " characters.");
                break;
            }
        }

        List<String> scopeFields = scopeFields(request);

        if (resolvedCases.isEmpty()) {
            // On every arm the caller actually used — the fields they can act on. A message on the
            // wrong field is one the form has nowhere to put, and with three arms "the wrong field"
            // stops being a guess between two: a run scoped only by a mistyped tag, answered on
            // caseIds, sends somebody to a control they never touched. When more than one arm was
            // used the message goes on all of them, because at least one has to change and nothing
            // here can know which.
            for (String field : scopeFields) {
                PlanEdit.error(errors, field,
                        "This run would have nothing to execute. Choose cases, suites or tags that "
                                + "match something, or a plan whose scope still has some.");
            }
        } else if (resolvedCases.size() > MAX_EXECUTIONS) {
            for (String field : scopeFields) {
                PlanEdit.error(errors, field,
                        "A run covers at most " + MAX_EXECUTIONS + " cases. Split it, or narrow "
                                + "the scope.");
            }
        } else if (!pinnableCases.containsAll(resolvedCases)) {
            // Refused rather than silently shortened: an execution must name the version it ran, so
            // a case with no version row cannot be in this run at all — and a checklist quietly
            // missing cases is one nobody can tell from a checklist that is complete. Unreachable
            // for any case authored since case-create wrote v1 with the case (§29).
            for (String field : scopeFields) {
                PlanEdit.error(errors, field,
                        "Some of these cases have no saved version yet, so a run cannot record "
                                + "which version it tested. Open and save them first.");
            }
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new RunEdit(
                        name, request.planId(), releaseVersion, buildNumber, commitSha, branch,
                        request.assigneeId(), resolvedCases))
                : new Outcome.Invalid(errors);
    }

    /**
     * The request's tags: trimmed, blanks dropped, duplicates collapsed, order kept —
     * {@code SuiteEdit#normalizeTagRules}' rules, minus its null-means-leave-alone distinction,
     * which a create has no use for.
     *
     * <p>Static and shared with {@link RunCreateService} on purpose: the service resolves the scope
     * <em>before</em> this class validates it, so the tags that are checked and the tags that are
     * queried must come from one function. Trimming in one place and not the other is how a scope
     * containing {@code " smoke"} resolves to nothing and is reported as a tag matching nothing.
     */
    static List<String> tagsOf(RunWriteRequest request) {
        if (request.tags() == null) {
            return List.of();
        }
        List<String> distinct = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String raw : request.tags()) {
            String tag = PlanEdit.trimToNull(raw);
            if (tag != null && seen.add(tag)) {
                distinct.add(tag);
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * The fields a scope-level refusal belongs on: every explicit arm the caller used, or
     * {@code planId} when they named only a plan.
     *
     * <p>{@code caseIds} is the fallback when the body named no scope at all, which is what the
     * form with no controls filled in sends — the same field the message landed on before there
     * were three arms.
     */
    private static List<String> scopeFields(RunWriteRequest request) {
        List<String> fields = new java.util.ArrayList<>(3);
        if (!PlanEdit.distinct(request.caseIds()).isEmpty()) {
            fields.add("caseIds");
        }
        if (!PlanEdit.distinct(request.suiteIds()).isEmpty()) {
            fields.add("suiteIds");
        }
        if (!tagsOf(request).isEmpty()) {
            fields.add("tags");
        }
        if (fields.isEmpty()) {
            fields.add(request.planId() != null ? "planId" : "caseIds");
        }
        return fields;
    }
}
