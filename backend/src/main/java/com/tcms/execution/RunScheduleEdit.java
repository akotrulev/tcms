package com.tcms.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A validated, normalised write of one {@link RunSchedule} (§247).
 *
 * <h2>Field errors are COLLECTED, never short-circuited</h2>
 *
 * <p>{@code RunEdit}'s rule and {@code ExecutionService.validate}'s, for their reason: somebody
 * filling in a form should not be sent round the loop three times to discover three problems.
 *
 * <h2>What is checked here, and the one thing deliberately not checked</h2>
 *
 * <p>An expression this server cannot read, a zone it does not know, a name it cannot store, an
 * assignee who is not a member of this company, a configuration that is not in this project, and a
 * scope with no arm at all — every one of those is a 400 on the field the caller can act on.
 *
 * <p><strong>A {@code caseId}, {@code suiteId} or {@code planId} naming nothing live is NOT a field
 * error, and that is the one deliberate divergence from {@code RunEdit}.</strong> A run is created
 * once against a set the caller has just chosen, so an id resolving to nothing there means the two
 * of you disagree about what exists. A schedule is a <em>standing</em> request evaluated over
 * months: a suite it names may be deleted next quarter, and refusing the edit at that point would
 * make the schedule uneditable because of something the editor did not do — including the edit that
 * would have removed the dead arm. The emptiness is caught where it matters, <strong>at fire
 * time</strong>, as {@code last_fire_error}, which is the column that exists for exactly this.
 *
 * <p>What is still refused is a scope with <em>no arm at all</em>. That one is knowable now and can
 * only ever create empty runs, which {@code RunEdit} refuses anyway — refusing it once, here, beats
 * refusing it nightly and silently.
 *
 * @param scopeRequest {@code V61}'s document, already built by {@link RunScopeDocument#of} — so the
 *                     validated value carries the bytes that will be stored rather than the request
 *                     they came from, which is what makes the schedule's scope and its runs' scope
 *                     the same document by construction
 */
record RunScheduleEdit(
        String name,
        String cronExpression,
        String zoneId,
        String scopeRequest,
        UUID configurationId,
        UUID assigneeId,
        boolean enabled) {

    /** {@code RunEdit.MAX_NAME}'s number, because this name becomes a run's name prefix. */
    static final int MAX_NAME = RunEdit.MAX_NAME;

    /** Sealed so the caller's mapping is exhaustive. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code RunScheduleInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(RunScheduleEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create or a replace against facts the service has already looked up.
     *
     * @param scopeRequest    the document {@link RunScopeDocument#of} built from this request's
     *                        scope, or null when the scope was absent entirely
     * @param anyScopeArm     whether the normalised scope names a plan, a case, a suite or a tag
     * @param assigneeExists  whether {@code assigneeId} names an <strong>active member of this
     *                        company</strong> — {@code ExecutionPeople#isActiveMember} and never
     *                        {@code resolve}, because {@code app_user} is instance-level (ERD
     *                        §0.1's exception list) and {@code resolve} would accept a user id
     *                        belonging to another client entirely
     * @param configurationOk whether {@code configurationId} names a configuration of this project
     */
    static Outcome validate(
            RunScheduleWriteRequest request,
            String scopeRequest,
            boolean anyScopeArm,
            boolean assigneeExists,
            boolean configurationOk) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = PlanEdit.trimToNull(request.name());
        if (name == null) {
            PlanEdit.error(errors, "name", "Enter a name for this schedule.");
        } else if (name.length() > MAX_NAME) {
            PlanEdit.error(errors, "name", "Keep the name under " + MAX_NAME + " characters.");
        }

        // The two halves of the cadence are asked SEPARATELY so each message lands on the control
        // the caller can act on. CronSchedule.parse answers one empty for both, which is right for
        // the poller and useless to a form: a typo in the zone would send somebody to the cron box.
        String cronExpression = PlanEdit.trimToNull(request.cronExpression());
        if (cronExpression == null) {
            PlanEdit.error(errors, "cronExpression", "Enter a schedule, for example "
                    + "\"0 0 7 * * MON-FRI\" — every weekday at 07:00.");
        } else if (cronExpression.length() > CronSchedule.MAX_EXPRESSION) {
            PlanEdit.error(errors, "cronExpression",
                    "Keep the expression under " + CronSchedule.MAX_EXPRESSION + " characters.");
        } else if (!CronSchedule.isExpression(cronExpression)) {
            PlanEdit.error(errors, "cronExpression",
                    "That is not a schedule this server can read. It takes six fields — second, "
                            + "minute, hour, day of month, month, day of week — for example "
                            + "\"0 0 7 * * MON-FRI\".");
        }

        String zoneId = PlanEdit.trimToNull(request.zoneId());
        if (zoneId == null) {
            PlanEdit.error(errors, "zoneId", "Choose a time zone. \"Every weekday at 07:00\" is "
                    + "not a time without one.");
        } else if (!CronSchedule.isZone(zoneId)) {
            PlanEdit.error(errors, "zoneId",
                    "That is not a time zone this server knows — use an IANA id such as "
                            + "\"Europe/Sofia\".");
        }

        // The same cap RunEdit applies, and for its reason: a scope entry longer than a tag can be
        // is one that can never match anything, on any night.
        for (String tag : tagsOf(request)) {
            if (tag.length() > RunEdit.MAX_TAG) {
                PlanEdit.error(errors, "scope",
                        "Keep each tag under " + RunEdit.MAX_TAG + " characters.");
                break;
            }
        }

        if (!anyScopeArm) {
            PlanEdit.error(errors, "scope",
                    "Choose what this schedule should run — a plan, cases, suites or tags. A "
                            + "schedule that asks for nothing can only ever create empty runs.");
        }

        if (request.assigneeId() != null && !assigneeExists) {
            PlanEdit.error(errors, "assigneeId", "That person is not a member of this company.");
        }

        if (request.configurationId() != null && !configurationOk) {
            PlanEdit.error(errors, "configurationId",
                    "That configuration is not in this project.");
        }

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }
        return new Outcome.Valid(new RunScheduleEdit(
                name,
                cronExpression,
                zoneId,
                scopeRequest,
                request.configurationId(),
                request.assigneeId(),
                // Absent means enabled. A schedule somebody just created and did not switch off is
                // one they meant to run, and the contract marks the field required anyway — this
                // is the defensive half, not a second default.
                !Boolean.FALSE.equals(request.enabled())));
    }

    /**
     * The request's scope as a {@link RunWriteRequest}, so one normalisation serves both halves.
     *
     * <p>{@link RunScopeDocument#of} and the emptiness check below both read this, and so does the
     * fire — which is the property that makes a schedule's stored document byte-equivalent to the
     * document its runs carry. The other nine components are the run's own and are supplied where
     * a run is actually created.
     */
    static RunWriteRequest scopeOf(RunScheduleWriteRequest request) {
        RunScopeRequestView scope = request.scope();
        return new RunWriteRequest(
                null,
                scope == null ? null : scope.planId(),
                null,
                null,
                null,
                null,
                null,
                scope == null ? null : scope.caseIds(),
                scope == null ? null : scope.suiteIds(),
                scope == null ? null : scope.tags(),
                null,
                null);
    }

    /**
     * Whether the scope names anything at all, after normalisation.
     *
     * <p>Asked of the <em>normalised</em> arms rather than of the raw ones, so a body carrying
     * {@code {"tags": ["  "]}} is empty rather than nominally populated — a blank tag is not an arm
     * and accepting it would produce a schedule that can only ever create empty runs, which is the
     * exact state this check exists to refuse.
     */
    static boolean anyArm(RunScheduleWriteRequest request) {
        RunWriteRequest scope = scopeOf(request);
        return scope.planId() != null
                || !PlanEdit.distinct(scope.caseIds()).isEmpty()
                || !PlanEdit.distinct(scope.suiteIds()).isEmpty()
                || !RunEdit.tagsOf(scope).isEmpty();
    }

    private static List<String> tagsOf(RunScheduleWriteRequest request) {
        return RunEdit.tagsOf(scopeOf(request));
    }
}
