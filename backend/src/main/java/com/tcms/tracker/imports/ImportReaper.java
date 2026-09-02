package com.tcms.tracker.imports;

import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves a run that nobody is running any more out of {@code RUNNING}.
 *
 * <h2>The failure this exists for is the one {@link ImportWriter} structurally cannot catch</h2>
 *
 * <p>The run is dispatched in memory and carries a credential that is written nowhere (D-a), so a
 * server restart mid-run <strong>cannot resume it</strong>. Nothing is half-written — the write is
 * one transaction (D-b), so the project is exactly as it was — but the row stays {@code RUNNING}
 * for ever, and the client polling it watches a spinner with no end and no error. That is the state
 * {@code ReportJobRunner.failStuckJobs} was added for one module over, and the argument is the
 * same one.
 *
 * <h2>It does not retry, and that is the decision rather than the limitation</h2>
 *
 * <p>The state machine only goes forwards and a re-request is a new row, so a stranded run becomes
 * {@code FAILED} with a sentence and somebody starts another. It could not retry even if the policy
 * said so: the credential the run needed is gone, by design.
 *
 * <h2>The company loop is not an optimisation — it is the only way this sees a row</h2>
 *
 * <p>{@code import_run} is under {@code FORCE ROW LEVEL SECURITY}, and a scheduled task starts with
 * no company at all: {@code CompanyContextTaskDecorator} clears it on every task, deliberately, so
 * work that forgets to establish one fails closed. The tempting shape — one "find every stuck run"
 * query — returns <strong>zero rows</strong> and logs a successful tick having done nothing.
 * {@code company} is one of the few tables not under RLS precisely so it can be read before any
 * context exists. {@code ReportJobScheduler}'s header makes this argument at length.
 */
@Component
public class ImportReaper {

    private static final Logger log = LoggerFactory.getLogger(ImportReaper.class);

    /**
     * What a reaped run is told to say.
     *
     * <p>Written for the person watching, like every other value of {@code failure_reason}: it says
     * what happened to <em>their project</em> rather than that a process died, because they cannot
     * act on the second and can act on the first. "Nothing was written" is the sentence D-b earns
     * and the reason it is safe to say — the whole write is one transaction, so an interrupted run
     * left the project unchanged.
     */
    static final String ABANDONED =
            "The server restarted or lost its connection to the source while this import was "
                    + "running. Nothing was written — the project is unchanged.";

    private final CompanyRepository companies;
    private final ImportRunRepository runs;
    private final ImportProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ImportReaper(
            CompanyRepository companies,
            ImportRunRepository runs,
            ImportProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.companies = companies;
        this.runs = runs;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Every five minutes.
     *
     * <p>Far less often than {@code ReportJobScheduler}'s thirty seconds, and the difference is
     * that this is not the latency of anything: a run is dispatched by the request that created it,
     * so this tick is only ever cleaning up after a process that died. The timeout it compares
     * against is {@code tcms.tracker.imports.run-timeout}, which is what actually decides how long
     * somebody waits.
     *
     * <p>A fixed delay rather than a cron, for {@code CaseHealthScheduler}'s reason: a cron at a
     * round time puts this on the same tick as every other job anybody adds later.
     * {@code initialDelay} keeps it off the startup path and is deliberately not a sibling's, so
     * the schedulers do not start in lockstep and then stay there.
     */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT5M")
    void failStuckRuns() {
        int reaped = 0;
        for (Company company : companies.findAll()) {
            try (var ignored = CompanyContext.open(company.getId())) {
                reaped += reapOpenCompany();
            } catch (RuntimeException failure) {
                // Named, not swallowed: without the company id this line is unactionable, and
                // without the continue one bad company silently costs every company after it.
                log.warn("Import reaper failed for company {}", company.getId(), failure);
            }
        }
        if (reaped > 0) {
            log.debug("Import reaper failed {} stranded runs", reaped);
        }
    }

    /**
     * @return how many rows were reaped — zero on every healthy tick.
     */
    int reapOpenCompany() {
        CompanyContext.require();
        Instant cutoff = clock.instant().minus(properties.runTimeout());

        List<UUID> stranded = transactions.execute(status ->
                runs.findByStatusAndStartedAtBefore(ImportRunStatus.RUNNING, cutoff).stream()
                        .map(ImportRun::getId)
                        .toList());

        if (stranded == null || stranded.isEmpty()) {
            return 0;
        }
        for (UUID runId : stranded) {
            // At WARN and one line per row, deliberately: this is a process that died, which is
            // the thing an operator wants to find in a log, and a count alone would not name the
            // migrations whose owners are about to be told they failed.
            log.warn("Import run {} started before {} and is still RUNNING — failing it",
                    runId, cutoff);
            transactions.executeWithoutResult(status -> runs.findById(runId).ifPresent(run -> {
                run.fail(ABANDONED, clock.instant());
                runs.save(run);
            }));
        }
        return stranded.size();
    }
}
