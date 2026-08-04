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
 * Runs {@link ReportJobRunner} for every company, on a schedule — {@link ExecutionFactScheduler}'s
 * twin, and the same four properties for the same four reasons.
 *
 * <h2>The company loop is not an optimisation — it is the only way this worker sees a job</h2>
 *
 * <p>{@code report_job} is company-owned and therefore under {@code FORCE ROW LEVEL SECURITY}. A
 * scheduled task starts with <strong>no company at all</strong>, deliberately: {@code CompanyContext}
 * is not an {@code InheritableThreadLocal} and {@code CompanyContextTaskDecorator} clears the context
 * on every task, so a job that forgets to establish one fails closed rather than inheriting whichever
 * company happened to submit it (§14).
 *
 * <p>So the tempting shape — one "find all pending jobs" query across the instance — returns
 * <strong>zero rows</strong> and logs a successful tick having done nothing. {@code V4}'s
 * {@code nullif(current_setting('app.current_company', true), '')} makes an unset GUC match nothing
 * rather than everything, which is the right failure and a completely invisible one: the log says
 * the tick finished, and every client polls a job that stays {@code PENDING} for ever with no error
 * anywhere. {@code company} is one of the few tables <em>not</em> under RLS, precisely so it can be
 * read before any context exists ({@code V1}'s comment on the table says so), and that is what makes
 * this loop possible.
 *
 * <h2>One transaction per job, none spanning the loop</h2>
 *
 * <p>Nothing here is transactional: {@link ReportJobRunner} opens its own, one per step, so changing
 * {@code app.current_company} between companies never happens inside a transaction — the one thing
 * {@code CompanyAwareTransactionManager} exists to prevent — and one company's failure cannot roll
 * back the reports another company already generated.
 *
 * <p>Which is also why the loop catches per company. A lock timeout in one company must not cost the
 * other forty-nine their queue; the next tick retries, because a claimed job is a row and not a
 * dispatch.
 *
 * <h2>Suspended companies are served too</h2>
 *
 * <p>Deliberate, and the same call {@link ExecutionFactScheduler} makes: suspension is reversible by
 * policy (suspend-never-delete, §6s), and a job requested the day before a suspension that resolves
 * to nothing after it is a client watching a spinner over a decision they were not party to.
 */
@Component
@ConditionalOnProperty(prefix = "tcms.reporting", name = "enabled", matchIfMissing = true)
class ReportJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportJobScheduler.class);

    private final CompanyRepository companies;
    private final ReportJobRunner runner;

    ReportJobScheduler(CompanyRepository companies, ReportJobRunner runner) {
        this.companies = companies;
        this.runner = runner;
    }

    /**
     * Every thirty seconds.
     *
     * <p>Far more often than either sibling scheduler, and the difference is that this one is not a
     * refresh — <strong>it is the whole latency of the feature</strong>. Nothing dispatches a job
     * when it is requested (see {@link ReportJobService}), so the interval is exactly how long a
     * client watches a spinner before anything at all begins, and §6e's {@code 202}-and-poll only
     * reads as a working product if that is seconds. A tick that finds nothing is one indexed query
     * per company against {@code ix_report_job_claim}.
     *
     * <p>A fixed delay rather than a cron, for {@code CaseHealthScheduler}'s stated reason: a cron at
     * a round time puts this on the same tick as every other job anyone adds later.
     * {@code initialDelay} keeps it off the startup path, where it would compete with Flyway and the
     * first requests for the same pool, and it is deliberately not either sibling's, so the three
     * schedulers do not start in lockstep and then stay there.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT30S")
    void runPendingJobs() {
        int run = 0;
        for (Company company : companies.findAll()) {
            try (var ignored = CompanyContext.open(company.getId())) {
                run += runner.runPendingJobs();
            } catch (RuntimeException failure) {
                // Named, not swallowed: without the company id this line is unactionable, and
                // without the continue one bad company silently costs every company after it.
                // A failing *job* never arrives here — the runner records it on the row — so
                // anything that does is the claim itself, which is a database fault.
                log.warn("Report job worker failed for company {}", company.getId(), failure);
            }
        }
        if (run > 0) {
            log.debug("Report job worker ran {} jobs", run);
        }
    }
}
