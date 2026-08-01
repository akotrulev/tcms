package com.tcms.repository;

import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link CaseHealthRecomputeService} for every company, on a schedule.
 *
 * <h2>The company loop is the whole reason this class is separate</h2>
 *
 * <p>A scheduled task starts with <strong>no company at all</strong>, and that is by design:
 * {@code CompanyContext} is deliberately not an {@code InheritableThreadLocal} and
 * {@code CompanyContextTaskDecorator} clears the context on every task, so a job that forgets to
 * establish one fails closed rather than inheriting whichever company happened to submit it
 * (handoff §14). There is therefore no ambient company to recompute — the job has to enumerate them
 * and open each in turn.
 *
 * <p>{@code company} is one of the few tables <em>not</em> under RLS, precisely so it can be read
 * before any context exists ({@code V1}'s comment on the table says so). That is what makes this
 * loop possible and it is also why it is the only thing this class does: everything after
 * {@code CompanyContext.open} is isolated normally.
 *
 * <h2>One transaction per company, not one for the run</h2>
 *
 * <p>{@link CaseHealthRecomputeService#recompute()} carries the {@code @Transactional}, so each
 * company commits on its own. A single transaction spanning the loop would have to change
 * {@code app.current_company} inside itself, which is the one thing {@code CompanyAwareTransactionManager}
 * is built to prevent, and one company's failure would roll back every company recomputed before it.
 *
 * <p>Which is also why the loop catches per company rather than letting the first failure end the
 * run. A recompute is idempotent and the next tick retries; losing the other forty-nine companies
 * because one has a lock timeout is a much worse outcome than a warning in the log.
 *
 * <h2>Suspended companies are recomputed too</h2>
 *
 * <p>Deliberate, and cheap. Suspension is reversible by policy (suspend-never-delete, §6s) and a
 * company that comes back to a dashboard reporting health from before its suspension would be
 * reporting a number nobody can date. Skipping them would save nothing worth having.
 */
@Component
@ConditionalOnProperty(prefix = "tcms.case-health", name = "enabled", matchIfMissing = true)
class CaseHealthScheduler {

    private static final Logger log = LoggerFactory.getLogger(CaseHealthScheduler.class);

    private final CompanyRepository companies;
    private final CaseHealthRecomputeService recompute;

    CaseHealthScheduler(CompanyRepository companies, CaseHealthRecomputeService recompute) {
        this.companies = companies;
        this.recompute = recompute;
    }

    /**
     * Hourly, offset off the hour.
     *
     * <p>A fixed delay rather than a cron: the signals move on a scale of days, so <em>when</em> in
     * the hour this lands is worth nothing, and a cron at {@code 0 0 * * * *} puts it on the same
     * tick as every other hourly job anyone adds later. {@code initialDelay} keeps it off the
     * startup path, where it would compete with Flyway and the first requests for the same pool.
     *
     * <p>Not configurable, on purpose — a knob here is a knob that gets set to something that never
     * runs. The threshold it derives against <em>is</em> configurable, which is the number that
     * carries meaning ({@link CaseHealthProperties}).
     */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    void recomputeAllCompanies() {
        int done = 0;
        for (Company company : companies.findAll()) {
            try (var ignored = CompanyContext.open(company.getId())) {
                recompute.recompute();
                done++;
            } catch (RuntimeException failure) {
                // Named, not swallowed: without the company id this line is unactionable, and
                // without the continue one bad company silently costs every company after it.
                log.warn("Case-health recompute failed for company {}", company.getId(), failure);
            }
        }
        log.debug("Case-health recompute finished for {} companies", done);
    }
}
