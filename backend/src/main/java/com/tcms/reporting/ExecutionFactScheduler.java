package com.tcms.reporting;

import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ExecutionFactRefreshService} for every company, on a schedule.
 *
 * <h2>The company loop is not an optimisation — it is the only way this job can see anything</h2>
 *
 * <p>{@code execution_fact} is company-owned and therefore under {@code FORCE ROW LEVEL SECURITY}.
 * A scheduled task starts with <strong>no company at all</strong>, deliberately:
 * {@code CompanyContext} is not an {@code InheritableThreadLocal} and
 * {@code CompanyContextTaskDecorator} clears the context on every task, so a job that forgets to
 * establish one fails closed rather than inheriting whichever company happened to submit it
 * (handoff §14).
 *
 * <p>So the tempting shape — one statement that projects every pending execution across the instance
 * — <strong>returns zero rows and logs a successful tick having done nothing</strong>. {@code V4}'s
 * {@code nullif(current_setting('app.current_company', true), '')} makes an unset GUC match nothing
 * rather than everything, which is the right failure and a completely invisible one: the log line
 * says the refresh finished, the fact table stays empty, and every report reads as a project where
 * nothing has ever been executed.
 *
 * <p>{@code company} is one of the few tables <em>not</em> under RLS, precisely so it can be read
 * before any context exists ({@code V1}'s comment on the table says so). That is what makes this
 * loop possible, and it is why the loop is the only thing this class does.
 *
 * <p>An {@code @Async} fire-and-forget is wrong here for the same reason and one more: it would
 * inherit no company either, and it would give up the property that makes the whole design
 * defensible — that a missed refresh is repaired by the next tick rather than lost for ever.
 *
 * <h2>One transaction per company, not one for the run</h2>
 *
 * <p>{@link ExecutionFactRefreshService#refresh()} carries the {@code @Transactional}, so each
 * company commits on its own. A single transaction spanning the loop would have to change
 * {@code app.current_company} inside itself, which is the one thing
 * {@code CompanyAwareTransactionManager} is built to prevent, and one company's failure would roll
 * back every company projected before it.
 *
 * <p>Which is also why the loop catches per company. The projection is idempotent and the next tick
 * retries; losing the other forty-nine companies because one has a lock timeout is a much worse
 * outcome than a warning in the log. {@code CaseHealthScheduler} is the precedent for all four
 * properties above and this class is deliberately its twin — two schedulers rather than one because
 * they write different read models owned by different modules, and {@code repository} and
 * {@code reporting} may not call each other.
 *
 * <h2>Suspended companies are refreshed too</h2>
 *
 * <p>Deliberate, and cheap. Suspension is reversible by policy (suspend-never-delete, §6s), and a
 * company that comes back to reports missing everything recorded before its suspension would be
 * reading numbers nobody can date.
 */
@Component
@ConditionalOnProperty(prefix = "tcms.reporting", name = "enabled", matchIfMissing = true)
class ExecutionFactScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionFactScheduler.class);

    private final CompanyRepository companies;
    private final ExecutionFactRefreshService refresh;

    ExecutionFactScheduler(CompanyRepository companies, ExecutionFactRefreshService refresh) {
        this.companies = companies;
        this.refresh = refresh;
    }

    /**
     * Every fifteen minutes, offset off the hour.
     *
     * <p>More often than {@code CaseHealthScheduler}'s hour, and the difference is the subject
     * rather than a preference: health signals move on a scale of days, while a fact is stale the
     * moment somebody records a result. Fifteen minutes is the floor on how far behind a report read
     * <em>outside</em> a job can be — a report job refreshes its own company synchronously before
     * computing (slice plan decision A), so the schedule never decides what a generated report says.
     *
     * <p>A fixed delay rather than a cron, for {@code CaseHealthScheduler}'s stated reason: a cron at
     * a round time puts this on the same tick as every other job anyone adds later.
     * {@code initialDelay} keeps it off the startup path, where it would compete with Flyway and the
     * first requests for the same pool — and it is deliberately not {@code PT2M}, so the two
     * schedulers do not start in lockstep and then stay there.
     */
    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT15M")
    void refreshAllCompanies() {
        int done = 0;
        for (Company company : companies.findAll()) {
            try (var ignored = CompanyContext.open(company.getId())) {
                refresh.refresh();
                done++;
            } catch (RuntimeException failure) {
                // Named, not swallowed: without the company id this line is unactionable, and
                // without the continue one bad company silently costs every company after it.
                log.warn("Execution-fact refresh failed for company {}", company.getId(), failure);
            }
        }
        log.debug("Execution-fact refresh finished for {} companies", done);
    }
}
