package com.tcms.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link OutboxDispatcher} for every company, on a schedule.
 *
 * <h2>The company loop is not an optimisation — it is the only way this job can see anything</h2>
 *
 * <p>{@code ExecutionFactScheduler}'s property, and it is repeated here rather than referenced
 * because getting it wrong is silent: {@code outbox} is company-owned and therefore under
 * {@code FORCE ROW LEVEL SECURITY}, while a scheduled task starts with no company at all —
 * {@code CompanyContext} is not inheritable and {@link CompanyContextTaskDecorator} clears it on
 * every task, so a job that forgets fails closed. The tempting shape, one query for every due row in
 * the instance, <strong>returns zero rows and logs a successful tick having delivered nothing</strong>.
 * {@code V4}'s {@code nullif(current_setting(…), '')} is what makes an unset context match nothing
 * rather than everything, which is the right failure and a completely invisible one.
 *
 * <p>{@code company} is one of the few tables not under RLS, precisely so it can be read before any
 * context exists. That is what makes this loop possible and why the loop is all this class does.
 *
 * <h2>One transaction per company — in fact three per event, and none of them here</h2>
 *
 * <p>This class opens a company and calls; the transactions are the dispatcher's and the ledger's.
 * A transaction spanning the loop would have to change {@code app.current_company} inside itself,
 * which is the one thing {@code CompanyAwareTransactionManager} exists to prevent.
 *
 * <p>The {@code catch} is per company for the reason its two siblings state: losing the other
 * forty-nine companies because one has a lock timeout is a much worse outcome than a warning in the
 * log. Note that a <em>handler's</em> failure never reaches here at all — the dispatcher records it
 * on the row and moves to the next event, which is the entire point of the table.
 *
 * <h2>Suspended companies are dispatched too</h2>
 *
 * <p>Deliberate, and the same call the two sibling schedulers make: suspension is reversible by
 * policy (§6s), and an event recorded before a suspension still describes something that happened.
 * The alternative is a queue that silently accumulates for the length of the suspension and then
 * delivers all of it at once on the day somebody restores the company.
 *
 * @see OutboxDispatcher what happens to one company's events
 */
@Component
@ConditionalOnProperty(prefix = "tcms.outbox", name = "enabled", matchIfMissing = true)
class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final CompanyRepository companies;
    private final OutboxDispatcher dispatcher;

    OutboxScheduler(CompanyRepository companies, OutboxDispatcher dispatcher) {
        this.companies = companies;
        this.dispatcher = dispatcher;
    }

    /**
     * Every {@code tcms.outbox.interval}, after a minute's grace.
     *
     * <p>The interval is configured rather than literal — unlike its two sibling schedulers, whose
     * cadence is a property of what they compute. This one's is a property of how late a deployment
     * is willing for a delivery to be, which is the operator's question, and it has to agree with
     * {@code tcms.outbox.lease} (see {@link OutboxProperties}, which refuses the combination that
     * double-delivers).
     *
     * <p>A fixed delay rather than a cron, for {@code CaseHealthScheduler}'s stated reason: a cron at
     * a round time puts this on the same tick as every other job anyone adds later. The initial
     * delay keeps it off the startup path, where it would compete with Flyway and the first requests
     * for the same pool, and it is a minute rather than the siblings' two and three so the three do
     * not start in lockstep and stay there.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${tcms.outbox.interval}")
    void dispatchAllCompanies() {
        int delivered = 0;
        for (Company company : companies.findAll()) {
            try (var ignored = CompanyContext.open(company.getId())) {
                delivered += dispatcher.dispatchDue();
            } catch (RuntimeException failure) {
                // Named, not swallowed: without the company id this line is unactionable, and
                // without the continue one bad company silently costs every company after it.
                log.warn("Outbox dispatch failed for company {}", company.getId(), failure);
            }
        }
        if (delivered > 0) {
            log.debug("Outbox delivered {} events", delivered);
        }
    }
}
