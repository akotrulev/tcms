package com.tcms.execution;

import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires due {@link RunSchedule}s for every company, on a timer (§247).
 *
 * <h2>The company loop is the only way this job can see anything</h2>
 *
 * <p>{@code CaseHealthScheduler}'s and {@code OutboxScheduler}'s shared property, repeated here
 * rather than referenced because getting it wrong is <strong>silent</strong>: {@code run_schedule}
 * is company-owned and therefore under {@code FORCE ROW LEVEL SECURITY}, while a scheduled task
 * starts with <strong>no company at all</strong> — {@code CompanyContext} is deliberately not an
 * {@code InheritableThreadLocal} and {@code CompanyContextTaskDecorator} clears it on every task,
 * so a job that forgets fails closed. The tempting shape, one query for every due row in the
 * instance, <strong>returns zero rows and logs a successful tick having fired nothing.</strong>
 * {@code V4}'s {@code nullif(current_setting(…), '')} is what makes an unset context match nothing
 * rather than everything — the right failure, and a completely invisible one.
 * {@code AsyncCompanyContextTest} drives that shape rather than describing it.
 *
 * <p>{@code company} is one of the few tables not under RLS, precisely so it can be read before any
 * context exists. That is what makes this loop possible and why the loop is all this class does.
 *
 * <h2>Why this is {@code CaseHealthScheduler}'s shape and not {@code OutboxScheduler}'s</h2>
 *
 * <p>The two loops are nearly identical and the difference is entirely in what they call — which is
 * why this paragraph is here rather than a reference: the next reader will see two loops and pick
 * whichever they find first.
 *
 * <p>{@code OutboxScheduler} drives a <strong>lease-based queue drain</strong>: claim rows under a
 * lease, run a handler, record the failure on the row, retry with backoff, eventually dead-letter.
 * Its semantics are at-least-once and its duplicates are made harmless by
 * {@code NotificationLedger}. Nothing here is like that. A fire is <strong>at-most-once</strong>: a
 * duplicate is a second checklist, which is the outcome {@code RunCreateService}'s idempotency
 * comment calls out by name, and a missed slot is <em>not redelivered</em> — a regression pass that
 * did not happen on Tuesday cannot be made to have happened on Tuesday. Copying the outbox would
 * mean copying a lease column, a retry counter and a backoff into a table that needs none of them,
 * and framing a missed run as something owed.
 *
 * <p>{@code CaseHealthScheduler} is the precedent because it does what this does: iterate
 * companies, open each, do one idempotent thing, catch per company, log the id,
 * <strong>continue</strong>. Its header carries why the continue matters — losing the other
 * forty-nine companies because one has a lock timeout is a much worse outcome than a warning in
 * the log.
 *
 * <h2>One transaction per fire, not one for the loop</h2>
 *
 * <p>{@link RunScheduleFiringService#fire} carries the {@code @Transactional}. A single transaction
 * spanning the loop would have to change {@code app.current_company} inside itself — the one thing
 * {@code CompanyAwareTransactionManager} exists to prevent — and one company's failure would roll
 * back every company fired before it.
 *
 * <h2>Suspended companies are polled too</h2>
 *
 * <p>Both siblings make the same call for the same reason: suspension is reversible by policy
 * (§6s). Skipping them would produce a schedule that silently misses its slots for the length of a
 * suspension with no record of having done so, where {@code lastFiredAt} at least states the truth
 * a reader can compare against the cadence.
 */
@Component
@ConditionalOnProperty(prefix = "tcms.run-schedule", name = "enabled", matchIfMissing = true)
class RunScheduleScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduleScheduler.class);

    private final CompanyRepository companies;
    private final RunScheduleRepository schedules;
    private final RunScheduleFiringService firing;
    private final Clock clock;

    RunScheduleScheduler(
            CompanyRepository companies,
            RunScheduleRepository schedules,
            RunScheduleFiringService firing,
            Clock clock) {

        this.companies = companies;
        this.schedules = schedules;
        this.firing = firing;
        this.clock = clock;
    }

    /**
     * Every minute, after three minutes' grace.
     *
     * <p>A fixed delay rather than a cron, for {@code CaseHealthScheduler}'s stated reason: a cron
     * at a round time puts this on the same tick as every other job anyone adds later. One minute
     * is the <strong>resolution of the feature</strong> — a schedule can be no more precise than
     * the poll — and it is deliberately not configurable, because a knob here is a knob that gets
     * set to something that never runs.
     *
     * <p>The three-minute initial delay keeps it off the startup path, where it would compete with
     * Flyway and the first requests for the same pool, and off its two siblings' two and three
     * minutes so the jobs do not start in lockstep and stay there.
     */
    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT1M")
    void fireDueForAllCompanies() {
        Instant now = clock.instant();
        int fired = 0;
        for (Company company : companies.findAll()) {
            try (var ignored = CompanyContext.open(company.getId())) {
                for (RunSchedule due : schedules.findDue(now)) {
                    // An ID, never the entity. It was read here, outside any transaction; the
                    // firing service re-reads it inside its own, because passing the detached row
                    // across the boundary is how a stale nextFireAt gets written back over a
                    // fresher one.
                    firing.fire(due.getId(), now);
                    fired++;
                }
            } catch (RuntimeException failure) {
                // Named, not swallowed: without the company id this line is unactionable, and
                // without the continue one bad company silently costs every company after it.
                log.warn("Run-schedule firing failed for company {}", company.getId(), failure);
            }
        }
        if (fired > 0) {
            log.debug("Run schedules fired {} runs", fired);
        }
    }
}
