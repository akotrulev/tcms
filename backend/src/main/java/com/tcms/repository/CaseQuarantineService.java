package com.tcms.repository;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarantining a flaky test, and letting it back into the gate — <strong>M3's "quarantine flow",
 * and the only writer of {@code case_flake}'s four authored columns.</strong>
 *
 * <h2>The gate is {@code case:edit}, and no permission was invented</h2>
 *
 * <p>The roles spec is authoritative and has no {@code case:quarantine}; adding a key is a decision
 * that belongs in that document rather than in a service, and there is an existing key that means
 * the right thing — quarantining changes how a case is treated, which is what {@code case:edit} is
 * for.
 *
 * <p>The choice also carries §117's property for free, and it is the reason this is <strong>not</strong>
 * gated on {@code result:submit}. Automation Service is deliberately the smallest role in the
 * product and does not hold {@code case:edit} (roles spec §6b), so <strong>a leaked CI token can
 * post results and cannot quarantine a test to stop its own red results from gating.</strong> That
 * is the same argument §117 made for {@code automation_key_mapping}'s writer, and it is what makes
 * "the CI credential is the realistic breach" a design rather than a hope: the two capabilities that
 * would let a token launder a red suite into a green one are both behind a key it does not have.
 *
 * <h2>The reason is required, and the write is not a toggle</h2>
 *
 * <p>{@code case_flake_quarantine_complete} enforces it in the database, and this service could not
 * bypass it if it tried. The argument is what the board card has to say: <em>this test is excluded
 * from gating</em> is only actionable next to <em>why, and by whom</em>. Without those the only way
 * to evaluate a quarantine is to read the test, which is the work the quarantine was supposed to
 * defer.
 *
 * <p>Releasing needs no reason, and that asymmetry is deliberate rather than an oversight: putting a
 * test back into the gate is returning to the default, and the thing that needs justifying is the
 * exception.
 *
 * <h2>The row may not exist yet, and that is the ordinary sequence</h2>
 *
 * <p>{@code case_flake}'s derived half is written by a scheduled job. A run that has just gone red is
 * quarantined within the hour, so the common path is quarantining a case the recompute has never
 * produced a row for. {@link CaseFlake#unscored} is what makes that legal without inventing a score
 * — the derived columns take their {@code V38} defaults, which already mean "no evidence", and the
 * next tick fills them in without touching the decision recorded here.
 *
 * <p>Releasing a case with no row is a no-op that answers success rather than 404. The caller asked
 * for a state — not quarantined — and that is the state it is in; a 404 would be describing the read
 * model's schedule to somebody who did not ask about it.
 *
 * <h2>What this deliberately does NOT do</h2>
 *
 * <p><strong>Nothing downstream reads the flag yet.</strong> Quarantine is recorded and surfaced;
 * it does not exclude the case from a run, a report, or a pass rate. That is a real decision and not
 * a gap: excluding a case from a pass rate silently is how a green number stops meaning anything,
 * and §6e's whole argument about the aggregation policy is that a figure whose denominator moved
 * without saying so is worse than no figure. When gating reads this column it must say so on the
 * face of the report — a separate slice, with §6e's discipline.
 */
@Service
public class CaseQuarantineService {

    /**
     * Not {@code case:quarantine}, which does not exist. See the class header — and note this is a
     * constant at the call site rather than a value the request names, which is
     * {@code ProjectService.writeGranted}'s standing rule.
     */
    static final String EDIT_CASE = "case:edit";

    private static final String AUDIT_TARGET = "test_case";

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final CaseFlakeRepository flakes;
    private final AuditEventRepository auditEvents;
    private final CaseOwners people;
    private final Clock clock;

    CaseQuarantineService(
            ProjectService projects,
            TestCaseRepository cases,
            CaseFlakeRepository flakes,
            AuditEventRepository auditEvents,
            CaseOwners people,
            Clock clock) {
        this.projects = projects;
        this.cases = cases;
        this.flakes = flakes;
        this.auditEvents = auditEvents;
        this.people = people;
        this.clock = clock;
    }

    /**
     * Renders the row, naming the decider.
     *
     * <p>One lookup and only when the row names somebody — a release clears the column, so the
     * common path resolves nothing.
     */
    private CaseFlakeView render(UUID companyId, CaseFlake flake) {
        UUID decider = flake.getQuarantinedBy();
        return CaseFlakeView.of(
                flake,
                decider == null ? null : people.resolve(companyId, List.of(decider)).get(decider));
    }

    public sealed interface Outcome {

        /** Unreachable project, a case that is not in it, or a deleted case — one bodiless 404. */
        record NotFound() implements Outcome {
        }

        /** {@code case:edit} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** A quarantine with no reason. See the class header for why this is not permitted. */
        record Invalid(String field, String message) implements Outcome {
        }

        /**
         * Recorded. The row as it now stands, <strong>already rendered</strong>.
         *
         * <p>A view rather than the entity, so the decider is resolved to a name inside this
         * service's transaction. {@link CaseOwners} reads {@code app_user} through the tenancy
         * filter, and a controller resolving it after the transaction closed would run with no
         * {@code app.current_company} and name nobody — fail-closed, so the field would simply be
         * null on every successful quarantine and nothing would fail.
         */
        record Done(CaseFlakeView flake) implements Outcome {
        }
    }

    @Transactional
    public Outcome set(UUID userId, UUID projectId, UUID caseId, boolean quarantined,
            String reason) {

        GateResult<Outcome> gated = projects.writeGranted(
                userId, projectId, EDIT_CASE,
                project -> apply(userId, project, caseId, quarantined, reason));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID userId, Project project, UUID caseId, boolean quarantined,
            String reason) {

        String trimmed = reason == null ? null : reason.trim();
        if (quarantined && (trimmed == null || trimmed.isEmpty())) {
            // Checked before the case is looked up, and therefore before the 404 — the request is
            // malformed regardless of which case it names, and answering 404 for a body error would
            // send the caller to check the id.
            return new Outcome.Invalid("reason",
                    "Say why this test is being quarantined. The reason is what makes the "
                            + "quarantine reviewable by somebody who did not make the decision.");
        }

        // The second gate a two-segment path needs: is this case in THAT project. Without it,
        // /projects/{granted}/cases/{ungranted} quarantines a case the caller was never given.
        Optional<TestCase> found = cases.findLiveInProject(project.getId(), caseId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestCase testCase = found.get();

        Instant now = clock.instant();
        UUID companyId = CompanyContext.require();
        Optional<CaseFlake> existing = flakes.findScopedById(caseId);
        if (existing.isEmpty() && !quarantined) {
            // Releasing a case that was never quarantined. The requested state is the state it is
            // in, so this succeeds rather than 404s — and it does NOT create a row, because a row
            // whose every column is a default is a claim the recompute has not made yet.
            return new Outcome.Done(render(
                    companyId, CaseFlake.unscored(caseId, companyId, project.getId(), now)));
        }

        CaseFlake flake = existing.orElseGet(() -> flakes.save(CaseFlake.unscored(
                caseId, companyId, project.getId(), now)));

        if (quarantined) {
            flake.quarantine(trimmed, userId, now);
        } else {
            flake.release();
        }
        flakes.saveAndFlush(flake);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                quarantined ? "case.quarantined" : "case.quarantine_released",
                AUDIT_TARGET,
                List.of(caseId),
                // JSONB, not text — audit_event.changes is a jsonb column and a bare string is a
                // 500 at flush time rather than a compile error. AuditEvent.jsonString is the
                // escaper the sibling writers use; a reason containing a quote is the ordinary case
                // here, not an exotic one, since the field is free text somebody types in anger.
                //
                // The key and the reason. The key is what an audit reader recognises the case by,
                // and the reason is here because case_flake keeps only the CURRENT one — a release
                // clears it, so without this the record of why a test was excluded for three months
                // is destroyed by the act of fixing it.
                quarantined
                        ? "{\"key\":" + AuditEvent.jsonString(testCase.getKey())
                                + ",\"reason\":" + AuditEvent.jsonString(trimmed) + "}"
                        : "{\"key\":" + AuditEvent.jsonString(testCase.getKey()) + "}",
                now));

        return new Outcome.Done(render(companyId, flake));
    }
}
